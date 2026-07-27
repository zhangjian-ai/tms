import os
import json
import asyncio
import adbutils

from typing import Dict
from logzero import logger
from tornado import websocket
from datetime import datetime
from dataclasses import dataclass, field

from utils.network import Host
from utils.server import ws_client
from utils.variables import settings
from device.android.tcp2usb import Tcp2Usb
from device.android.scrcpy import scrcpy_manager
from device.android.tools.adb import restart_adb_server, pin_adbutils_to_bundled, adb_server_alive
from device.android.tools.install import AndroidDeviceInstaller
from device.forward import PortForwardManager


@dataclass
class DeviceState:
    """设备状态信息"""
    serial: str
    online: bool = False
    init: bool = False  # 接入工具（uiautomator apk）是否已安装
    t2u: Tcp2Usb = None
    occupied: bool = False  # 是否已被占用
    cast: bool = False      # 占用是否允许投屏（web=True，自动化=False）
    info_reported: bool = False  # device_info 是否已上报
    last_seen: datetime = field(default_factory=datetime.now)
    error: str = ""


class AndroidDeviceManager:
    """Android设备管理器"""

    def __init__(self):
        self.config = settings["android"]
        # host 用于上报（0.0.0.0 时替换为 LAN IP）；客户端连接一律用 127.0.0.1
        self.report_host = self.config["adb"].get("host", "0.0.0.0")
        self.port = self.config["adb"].get("port", 5538)
        self.host = "127.0.0.1"

        # 统一 adb 客户端目标地址与端口
        os.environ["ANDROID_ADB_SERVER_HOST"] = self.host
        os.environ["ANDROID_ADB_SERVER_PORT"] = str(self.port)
        os.environ["ADB_SERVER_HOST"] = self.host
        os.environ["ADB_SERVER_PORT"] = str(self.port)

        # adbutils 客户端统一使用自带 adb
        pin_adbutils_to_bundled()

        self.adb = adbutils.AdbClient(host=self.host, port=self.port)
        adbutils.adb = self.adb

        self.devices: Dict[str, DeviceState] = {}
        self.installer = AndroidDeviceInstaller()
        self.ws: websocket.WebSocketClientConnection = None
        self._locks: Dict[str, asyncio.Lock] = {}  # 每设备串行化 start/stop_proxy
        self.forward_manager = PortForwardManager("android", self)  # 额外端口转发（内聚到本模块）
        self._adb_down = 0  # adb server 连续探活失败次数

    def _lock_for(self, serial: str) -> asyncio.Lock:
        lock = self._locks.get(serial)
        if lock is None:
            lock = asyncio.Lock()
            self._locks[serial] = lock
        return lock

    async def _ensure_ws(self):
        """确保后端 WS 已连接"""
        if not self.ws:
            self.ws = await ws_client.connect()
        return self.ws

    async def sync(self):
        """同步设备状态到服务端（3秒轮询 adb 实时设备列表）。

        懒代理：发现设备只上报存在，不启动任何代理；代理由 backend 下发 start_proxy 才启动。
        """
        while True:
            await asyncio.sleep(3)
            try:
                await self._ensure_ws()
                if not self.ws:
                    continue

                # 探活：server 存活（哪怕繁忙）就列设备；只有连续多次连接被拒才判定真挂掉，
                # 做一次受控重启，避免误杀繁忙 server，也避开 adbutils 的自动 start-server。
                if not await asyncio.to_thread(adb_server_alive, self.port):
                    self._adb_down += 1
                    if self._adb_down >= 2:
                        logger.warning("adb server 连续探活失败，执行受控重启")
                        await asyncio.to_thread(restart_adb_server, self.port)
                        self._adb_down = 0
                    continue
                self._adb_down = 0

                # 以 adb 实时设备列表为准，仅取 state=="device" 的正常设备
                try:
                    current = {d.serial for d in self.adb.list() if d.state == "device"}
                except Exception as e:
                    logger.warning(f"获取 adb 设备列表失败: {e}")
                    continue

                # 上线：标记在线并上报 online 与（首次）device_info
                for serial in current:
                    device = self.devices.get(serial)
                    if device is None:
                        logger.info(f"发现新设备: {serial}")
                        device = DeviceState(serial=serial, online=True)
                        self.devices[serial] = device
                    device.online = True
                    device.last_seen = datetime.now()

                    await self.ws.write_message({"type": "status", "serial": serial, "status": "online"})

                    # 首次上报 device_info 供列表展示
                    if not device.info_reported:
                        info = await asyncio.to_thread(self.get_device_info, self.adb.device(serial))
                        if info:
                            await self.ws.write_message({"type": "device_info", "serial": serial, "device_info": info})
                            device.info_reported = True

                # 离线：清理代理、上报 offline，成功后移除
                for serial, device in list(self.devices.items()):
                    if serial not in current:
                        await self._on_offline(serial)
                        await self.ws.write_message({"type": "status", "serial": serial, "status": "offline"})
                        self.devices.pop(serial, None)

            except Exception as e:
                logger.error(f"Android 设备同步失败: {e}")
                if "websocket" in str(e).lower():
                    self.ws = None

    async def command_loop(self):
        """接收 backend 反向下发的指令（start_proxy / stop_proxy）。

        与 sync() 共用 self.ws：sync 只写、本循环只读。指令处理放到独立 task。
        """
        while True:
            ws = self.ws
            if not ws:
                await asyncio.sleep(1)
                continue
            try:
                msg = await ws.read_message()
            except Exception as e:
                logger.warning(f"读取后端指令失败: {e}")
                if self.ws is ws:
                    self.ws = None
                await asyncio.sleep(1)
                continue
            if msg is None:  # 连接已关闭
                if self.ws is ws:
                    self.ws = None
                await asyncio.sleep(1)
                continue
            await self._handle_command(msg)

    async def _handle_command(self, msg):
        try:
            data = json.loads(msg) if isinstance(msg, (str, bytes)) else msg
        except Exception:
            logger.warning(f"无法解析后端指令: {msg}")
            return
        mtype = data.get("type")
        serial = data.get("serial")
        if not serial:
            return
        if mtype == "start_proxy":
            asyncio.create_task(self.start_proxy(serial, bool(data.get("cast", False))))
        elif mtype == "stop_proxy":
            asyncio.create_task(self.stop_proxy(serial))

    async def start_proxy(self, serial: str, cast: bool = False):
        """占用时启动设备代理：安装接入工具 + 启动 Tcp2Usb 并上报连接信息。幂等。"""
        async with self._lock_for(serial):
            device = self.devices.get(serial)
            if device is None or not device.online:
                logger.warning(f"启动代理失败：设备不在线 {serial}")
                return
            try:
                await self._ensure_ws()

                if not device.init:
                    if await asyncio.to_thread(self.installer.install_to_device, serial):
                        device.init = True
                    else:
                        logger.error(f"接入工具安装失败（adb 仍可用）: {serial}")

                # 僵尸引用（线程已退出）先丢弃，避免复用死代理
                if device.t2u is not None and not device.t2u.is_alive():
                    device.t2u = None

                if not device.t2u:
                    # 建链前清理该序列号残留/孤儿代理，从根源杜绝端口泄漏
                    Tcp2Usb.stop_existing(serial)
                    t2u = Tcp2Usb(serial, self.host, self.port)
                    t2u.start()
                    device.t2u = t2u

                device.occupied = True
                device.cast = cast

                await self.ws.write_message({
                    "type": "connection_info", "serial": serial,
                    "connection_info": self.get_connection_info(device)
                })
                logger.info(f"设备 {serial} 代理已启动 (cast={cast})")
            except Exception as e:
                logger.error(f"启动代理失败 {serial}: {e}")

    async def stop_proxy(self, serial: str):
        """释放时停止设备代理：清理 scrcpy 与 Tcp2Usb。幂等。"""
        async with self._lock_for(serial):
            device = self.devices.get(serial)
            if device is None:
                return
            try:
                await scrcpy_manager.cleanup_device(serial, device_offline=False)
                if device.t2u:
                    device.t2u.stop()
                    device.t2u = None
            except Exception as e:
                logger.error(f"停止代理失败 {serial}: {e}")
            device.occupied = False
            device.cast = False
            logger.info(f"设备 {serial} 代理已停止")

    async def _on_offline(self, serial: str):
        """
        设备下线：标记离线并清理 scrcpy / Tcp2Usb 资源。
        offline 上报与移除交由心跳循环完成。
        """
        device = self.devices.get(serial)
        if not device:
            return
        device.online = False
        device.init = False
        device.occupied = False
        device.cast = False
        try:
            await scrcpy_manager.cleanup_device(serial, device_offline=True)
            if device.t2u:
                device.t2u.stop()
                device.t2u = None
            if self.forward_manager:
                await self.forward_manager.remove_forwards(serial)
        except Exception as e:
            logger.error(f"设备下线清理失败 {serial}: {e}")

    @staticmethod
    def get_device_info(adb_device: adbutils.AdbDevice) -> Dict:
        """
        从ADB设备获取基础信息
        :param adb_device: AdbDevice实例
        :return: 设备信息字典
        """
        try:
            # 基础信息
            serial = adb_device.serial
            brand = adb_device.shell("getprop ro.product.brand").strip()
            model = adb_device.shell("getprop ro.product.model").strip()
            os_version = adb_device.shell("getprop ro.build.version.release").strip()

            # 设备名称 (brand + model 或使用产品名)
            product_name = adb_device.shell("getprop ro.product.name").strip()
            name = f"{brand} {model}" if brand and model else product_name or serial

            # 屏幕分辨率
            width, height = adb_device.window_size()

            device_info = {
                "name": name,
                "serial": serial,
                "brand": brand,
                "model": model,
                "device_sys": "android",
                "os_version": os_version,
                "width": width,
                "height": height
            }

            return device_info

        except Exception as e:
            logger.error(f"获取设备信息失败 {adb_device.serial}: {e}")

    def get_connection_info(self, device: DeviceState):
        """获取设备连接信息"""
        try:
            host = Host.get()
            adb_host = self.report_host if self.report_host != "0.0.0.0" else host

            info = {
                "adb_host": adb_host,
                "adb_port": self.port,
                "proxy_host": host,
                "proxy_port": self.config["proxy"]["port"],
                "connection": f"{host}:{device.t2u.proxy_port}"
            }

            return info

        except Exception as e:
            logger.error(f"获取设备连接信息失败 {device.serial}: {e}")
