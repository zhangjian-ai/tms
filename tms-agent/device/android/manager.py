import os
import asyncio
import adbutils
from adbutils.errors import AdbConnectionError

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
        self.host = self.config["adb"].get("host", "0.0.0.0")
        self.port = self.config["adb"].get("port", 5538)

        os.environ.setdefault("ANDROID_ADB_SERVER_HOST", "0.0.0.0")
        os.environ.setdefault("ANDROID_ADB_SERVER_PORT", "5538")

        self.adb = adbutils.AdbClient(host=self.host, port=self.port)
        adbutils.adb = self.adb

        # 设备状态管理
        self.devices: Dict[str, DeviceState] = {}

        # 工具实例
        self.installer = AndroidDeviceInstaller()

        # 后端的ws上报信息
        self.ws: websocket.WebSocketClientConnection = ...

    async def sync(self):
        """
        同步设备状态到服务端
        :return:
        """

        while True:
            # 3s同步一次状态，不能使用同步等待，同步会导致代理服务没法处理请求
            await asyncio.sleep(3)

            if not self.ws or isinstance(self.ws, Ellipsis.__class__):
                self.ws = await ws_client.connect()

            try:
                # 设备监听
                devices = self.adb.device_list()
                device_serials = [device.serial for device in devices]

                # 更新设备状态
                current_time = datetime.now()
                for serial in device_serials:
                    if serial not in self.devices:
                        logger.info(f"发现新设备: {serial}")
                        self.devices[serial] = DeviceState(
                            serial=serial,
                            online=True,
                            last_seen=current_time)
                    else:
                        if not self.devices[serial].online:
                            self.devices[serial].online = True
                            self.devices[serial].last_seen = current_time

                    await self.ws.write_message({"type": "status", "serial": serial, "status": "online"})

                # 标记离线设备
                for serial, device_info in self.devices.items():
                    if serial not in device_serials:
                        device_info.online = False
                        device_info.init = False

                # 信息同步
                for serial, device in self.devices.items():
                    if not device.online:
                        await scrcpy_manager.cleanup_device(serial, device_offline=True)

                        if device.t2u:
                            await device.t2u.close()
                            device.t2u = None

                        await self.ws.write_message({"type": "status", "serial": serial, "status": "offline"})
                        continue

                    # 设备初始化
                    if not device.init:
                        # 首次连接时，安装apk
                        result = self.installer.install_to_device(serial)
                        if result:
                            device.init = True

                            # 获取并同步设备基础信息
                            device_info = self.get_device_info(self.adb.device(serial))

                            # 同步到服务端
                            await self.ws.write_message(
                                {"type": "device_info", "serial": serial, "device_info": device_info})

                    if not device.t2u:
                        # 启动设备connect代理
                        t2u = Tcp2Usb(serial, self.host, self.port)
                        t2u.start()
                        device.t2u = t2u

                        connection_info = self.get_connection_info(device)

                        # 同步到服务端
                        await self.ws.write_message(
                            {"type": "connection_info", "serial": serial, "connection_info": connection_info})

            except AdbConnectionError:
                logger.warning("ADB服务连接失败，等待恢复...")
            except Exception as e:
                logger.error(f"设备同步失败: {e.__class__}")
                if "websocket" in str(e).lower():
                    logger.warning("检测到WebSocket连接问题，尝试重连...")
                    self.ws = None

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
            adb_host = self.config["adb"]["host"]

            if adb_host == "0.0.0.0":
                adb_host = Host.get()

            info = {
                "adb_host": adb_host,
                "adb_port": self.config["adb"]["port"],
                "proxy_host": Host.get(),
                "proxy_port": self.config["proxy"]["port"],
                "connection": f"{Host.get()}:{device.t2u.proxy_port}"
            }

            return info

        except Exception as e:
            logger.error(f"获取设备连接信息失败 {device.serial}: {e}")
