import asyncio
import subprocess
from typing import Dict
from logzero import logger
from tornado import websocket
from datetime import datetime
from dataclasses import dataclass, field

from utils.network import Host, Port
from utils.server import ws_client
from utils.variables import settings
from device.ios.tools.idb import Idb
from device.ios.tools.client import WDAClient


@dataclass
class IOSDeviceState:
    """iOS 设备状态信息"""
    udid: str
    online: bool = False
    init: bool = False
    wda_process: subprocess.Popen = None
    wda_port: int = 0
    mjpeg_process: subprocess.Popen = None
    mjpeg_port: int = 0
    wda_client: WDAClient = None
    last_seen: datetime = field(default_factory=datetime.now)
    error: str = ""


class IOSDeviceManager:
    """iOS 设备管理器"""

    def __init__(self):
        self.config = settings["ios"]
        self.devices: Dict[str, IOSDeviceState] = {}
        self.tidevice = Idb()
        self.ws: websocket.WebSocketClientConnection = ...

    async def sync(self):
        """同步设备状态到服务端（3秒轮询）"""
        while True:
            await asyncio.sleep(3)

            if not self.ws or isinstance(self.ws, Ellipsis.__class__):
                self.ws = await ws_client.connect()

            try:
                # 1. 设备发现
                devices = self.tidevice.list_devices()
                device_udids = [d["udid"] for d in devices]

                current_time = datetime.now()

                # 2. 更新在线设备
                for udid in device_udids:
                    if udid not in self.devices:
                        logger.info(f"发现新 iOS 设备: {udid}")
                        self.devices[udid] = IOSDeviceState(
                            udid=udid,
                            online=True,
                            last_seen=current_time
                        )
                    else:
                        if not self.devices[udid].online:
                            self.devices[udid].online = True
                            self.devices[udid].last_seen = current_time

                    await self.ws.write_message({"type": "status", "serial": udid, "status": "online"})

                # 3. 标记离线设备
                for udid, device_info in self.devices.items():
                    if udid not in device_udids:
                        device_info.online = False
                        device_info.init = False

                # 4. 处理设备状态
                for udid, device in self.devices.items():
                    if not device.online:
                        # 清理离线设备资源
                        if device.wda_process:
                            device.wda_process.terminate()
                            device.wda_process = None
                        if device.mjpeg_process:
                            device.mjpeg_process.terminate()
                            device.mjpeg_process = None
                        if device.wda_client:
                            await device.wda_client.close()
                            device.wda_client = None
                        device.wda_port = 0
                        device.mjpeg_port = 0

                        await self.ws.write_message({"type": "status", "serial": udid, "status": "offline"})
                        continue

                    # 5. 设备初始化 - 启动 WDA 代理和 MJPEG 服务器
                    if not device.init:
                        # 检查 WDA 是否已安装
                        if not self.tidevice.get_wda_status(udid):
                            logger.warning(f"设备 {udid} 未安装 WDA，跳过初始化")
                            device.error = "WDA not installed"
                            continue

                        # 分配端口并启动 WDA 代理
                        wda_bundle_id = self.config.get("wda_bundle_id", "com.facebook.WebDriverAgentRunner.xctrunner")
                        local_port = Port.get("ios")

                        wda_process = self.tidevice.start_wda_proxy(udid, wda_bundle_id, local_port)
                        if not wda_process:
                            logger.error(f"启动 WDA 代理失败: {udid}")
                            continue

                        device.wda_process = wda_process
                        device.wda_port = local_port

                        # 启动 MJPEG 服务器（用于投屏）
                        mjpeg_port = Port.get("ios")
                        mjpeg_process = self.tidevice.start_mjpeg_server(udid, mjpeg_port)
                        if mjpeg_process:
                            device.mjpeg_process = mjpeg_process
                            device.mjpeg_port = mjpeg_port
                        else:
                            logger.warning(f"启动 MJPEG 服务器失败: {udid}")

                        # 创建 WDA 客户端（经 wdaproxy 转发）
                        device.wda_client = WDAClient("127.0.0.1", local_port)

                        # 等待代理和 WDA 就绪（最多重试 3 次，每次 2 秒）
                        wda_ready = False
                        for retry in range(3):
                            await asyncio.sleep(2)
                            if await device.wda_client.health_check():
                                wda_ready = True
                                break
                            logger.debug(f"等待 WDA 代理就绪... 重试 {retry + 1}/10")

                        if not wda_ready:
                            logger.error(f"WDA 代理启动超时: {udid}")
                            # 清理资源
                            device.wda_process.terminate()
                            device.wda_process = None
                            device.wda_client = None
                            device.wda_port = 0
                            continue

                        # 创建会话
                        if not await device.wda_client.create_session():
                            logger.error(f"创建 WDA 会话失败: {udid}")
                            continue

                        # 获取设备信息并更新屏幕尺寸
                        device_info = self.get_device_info(udid)
                        if device_info:
                            size = await device.wda_client.get_window_size()
                            if size:
                                device_info["width"] = size[0]
                                device_info["height"] = size[1]

                            await self.ws.write_message({
                                "type": "device_info",
                                "serial": udid,
                                "device_info": device_info
                            })

                        # 上报连接信息
                        connection_info = self.get_connection_info(device)
                        await self.ws.write_message({
                            "type": "connection_info",
                            "serial": udid,
                            "connection_info": connection_info
                        })

                        device.init = True

            except Exception as e:

                logger.error(f"iOS 设备同步失败: {e}")
                if "websocket" in str(e).lower():
                    self.ws = None

    def get_device_info(self, udid: str) -> Dict:
        """获取设备基础信息"""
        try:
            info = self.tidevice.get_device_info(udid)
            if not info:
                return None

            device_info = {
                "name": info.get("DeviceName", udid),
                "serial": udid,
                "brand": "Apple",
                "model": info.get("ProductType", "iPhone"),
                "device_sys": "ios",
                "os_version": info.get("ProductVersion", "Unknown"),
                "width": 0,  # 需要通过 WDA 获取
                "height": 0
            }

            return device_info
        except Exception as e:
            logger.error(f"获取 iOS 设备信息失败 {udid}: {e}")
            return None

    def get_connection_info(self, device: IOSDeviceState):
        """获取连接信息"""
        try:
            host =  Host.get()
            info = {
                "adb_host": host,
                "adb_port": device.wda_port,
                "proxy_host": host,
                "proxy_port": self.config["proxy"]["port"],
                "connection": f"{host}:{device.wda_port}"
            }
            return info
        except Exception as e:
            logger.error(f"获取 iOS 连接信息失败 {device.udid}: {e}")
            return None
