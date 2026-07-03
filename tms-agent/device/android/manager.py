import os
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
from device.android.tools.install import AndroidDeviceInstaller


@dataclass
class DeviceState:
    """设备状态信息"""
    serial: str
    online: bool = False
    init: bool = False
    t2u: Tcp2Usb = None
    last_seen: datetime = field(default_factory=datetime.now)
    error: str = ""


class AndroidDeviceManager:
    """Android设备管理器"""

    def __init__(self):
        self.config = settings["android"]
        # 配置里的 host 用于上报（0.0.0.0 时替换为 LAN IP）；客户端连接一律用 127.0.0.1
        self.report_host = self.config["adb"].get("host", "0.0.0.0")
        self.port = self.config["adb"].get("port", 5538)
        self.host = "127.0.0.1"

        # 统一 adb 客户端目标地址与版本变量，避免多版本客户端触发 server 重启
        os.environ["ANDROID_ADB_SERVER_HOST"] = self.host
        os.environ["ANDROID_ADB_SERVER_PORT"] = str(self.port)
        os.environ["ADB_SERVER_HOST"] = self.host
        os.environ["ADB_SERVER_PORT"] = str(self.port)

        self.adb = adbutils.AdbClient(host=self.host, port=self.port)
        adbutils.adb = self.adb

        self.devices: Dict[str, DeviceState] = {}
        self.installer = AndroidDeviceInstaller()
        self.ws: websocket.WebSocketClientConnection = ...

    async def _ensure_ws(self):
        """确保后端 WS 已连接"""
        if not self.ws or isinstance(self.ws, Ellipsis.__class__):
            self.ws = await ws_client.connect()
        return self.ws

    async def sync(self):
        """同步设备状态到服务端（3秒轮询 adb 实时设备列表为权威状态，与 iOS 一致）"""
        while True:
            await asyncio.sleep(3)
            try:
                await self._ensure_ws()

                # 以 adb 实时设备列表为准（adb 异常时不误判下线）
                try:
                    current = {d.serial for d in self.adb.device_list()}
                except Exception as e:
                    logger.warning(f"获取 adb 设备列表失败: {e}")
                    continue

                # 上线：标记在线，未完成初始化的设备执行初始化
                for serial in current:
                    if serial not in self.devices:
                        self.devices[serial] = DeviceState(serial=serial, online=True)
                    self.devices[serial].online = True
                    self.devices[serial].last_seen = datetime.now()
                    if not self.devices[serial].init or not self.devices[serial].t2u:
                        await self._on_online(serial)

                # 标记不在实时列表中的设备为离线
                for serial, device in list(self.devices.items()):
                    if serial not in current:
                        device.online = False

                # 上报状态：在线发 online；离线则清理资源、发 offline、成功后移除
                for serial, device in list(self.devices.items()):
                    if device.online:
                        await self.ws.write_message({"type": "status", "serial": serial, "status": "online"})
                    else:
                        await self._on_offline(serial)
                        await self.ws.write_message({"type": "status", "serial": serial, "status": "offline"})
                        self.devices.pop(serial, None)

            except Exception as e:
                logger.error(f"Android 设备同步失败: {e}")
                if "websocket" in str(e).lower():
                    self.ws = None

    async def _on_online(self, serial: str):
        """设备上线：安装接入工具、启动 Tcp2Usb 代理并上报"""
        try:
            if serial not in self.devices:
                logger.info(f"发现新设备: {serial}")
                self.devices[serial] = DeviceState(serial=serial, online=True)
            device = self.devices[serial]
            device.online = True
            device.last_seen = datetime.now()

            await self._ensure_ws()
            await self.ws.write_message({"type": "status", "serial": serial, "status": "online"})

            # 安装 apk + 取设备信息为阻塞调用，放线程池避免卡住事件循环/事件流
            if not device.init:
                if await asyncio.to_thread(self.installer.install_to_device, serial):
                    device.init = True
                    info = await asyncio.to_thread(self.get_device_info, self.adb.device(serial))
                    if info:
                        await self.ws.write_message({"type": "device_info", "serial": serial, "device_info": info})

            if not device.t2u:
                t2u = Tcp2Usb(serial, self.host, self.port)
                t2u.start()
                device.t2u = t2u
                await self.ws.write_message({
                    "type": "connection_info", "serial": serial,
                    "connection_info": self.get_connection_info(device)
                })
        except Exception as e:
            logger.error(f"设备上线处理失败 {serial}: {e}")

    async def _on_offline(self, serial: str):
        """设备下线：标记离线并清理 scrcpy / Tcp2Usb 资源。

        offline 上报与从字典移除交由心跳循环完成，保证上报成功后才移除，避免漏报。
        """
        device = self.devices.get(serial)
        if not device:
            return
        device.online = False
        device.init = False
        try:
            await scrcpy_manager.cleanup_device(serial, device_offline=True)
            if device.t2u:
                device.t2u.stop()  # 线程安全停止（在其自身事件循环里关闭 server）
                device.t2u = None
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
                "device_sys": "android",  # 默认为android
                "os_version": os_version,
                "width": width,
                "height": height
            }

            return device_info

        except Exception as e:
            logger.error(f"获取设备信息失败 {adb_device.serial}: {e}")

    def get_connection_info(self, device: DeviceState):
        """
        从ADB设备获取基础信息
        """
        try:
            adb_host = self.report_host
            if adb_host == "0.0.0.0":
                adb_host = Host.get()

            info = {
                "adb_host": adb_host,
                "adb_port": self.port,
                "proxy_host": Host.get(),
                "proxy_port": self.config["proxy"]["port"],
                "connection": f"{Host.get()}:{device.t2u.proxy_port}"
            }

            return info

        except Exception as e:
            logger.error(f"获取设备连接信息失败 {device.serial}: {e}")
