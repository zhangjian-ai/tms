"""
TCP-USB Proxy Main Class
"""
import asyncio
import threading

from logzero import logger

from device.android.tcp2usb.adb import AdbClient
from device.android.tcp2usb.server import TcpProxyServer
from utils.network import Port


class Tcp2Usb(threading.Thread):
    """TCP-USB代理主类（独立线程内运行自己的事件循环）"""

    def __init__(self, serial: str, adb_host: str = 'localhost', adb_port: int = 5037,
                 bypass_auth: bool = True, max_connections: int = 10):
        """
        初始化代理

        Args:
            serial: 设备序列号
            adb_host: ADB服务器地址
            adb_port: ADB服务器端口
            bypass_auth: 是否绕过认证
            max_connections: 最大连接数，默认10个
        """
        super().__init__()
        self.daemon = True  # 进程退出时不被该线程阻塞
        self.serial = serial
        self.proxy_port = Port.get("android")
        self.bypass_auth = bypass_auth
        self.max_connections = max_connections
        self.adb_host = adb_host
        self.adb_port = adb_port

        # 服务器与所属事件循环将在 open() 内创建（需要异步环境）
        self.server = None
        self._loop = None
        self._serve_task = None

    async def open(self, host: str = '0.0.0.0'):
        """启动代理"""
        logger.info(f"Starting TCP-USB proxy for device {self.serial}")

        # 记录本线程事件循环，供其他线程 stop() 时跨线程调度
        self._loop = asyncio.get_running_loop()
        try:
            client = AdbClient(self.adb_host, self.adb_port)
            device_id = await self._fetch_device_id(client)

            self.server = TcpProxyServer(client, self.serial, self.bypass_auth, self.max_connections, device_id)

            self._serve_task = asyncio.create_task(self.server.listen(host, self.proxy_port))
            await self._serve_task
        except asyncio.CancelledError:
            logger.info(f"Proxy for {self.serial} cancelled")
        except Exception as e:
            logger.error(f"Proxy for {self.serial} error: {e}")
        finally:
            await self.close()

    async def _fetch_device_id(self, client: AdbClient) -> bytes:
        """获取设备ID"""
        try:
            properties = await client.get_device_properties(self.serial)
            id_parts = []
            for prop in ['ro.product.brand', 'ro.product.model', 'ro.product.device']:
                if prop in properties:
                    id_parts.append(f"{prop}={properties[prop]};")

            device_id = f"device::{'/'.join(id_parts)}\0"
            return device_id.encode('utf-8')
        except Exception as e:
            logger.warning(f"获取设备属性失败: {e}，使用默认ID")
            return b"device::ro.product.brand=android;ro.product.model=device;ro.product.device=generic;\0"

    def run(self):
        """线程入口"""
        asyncio.run(self.open())

    def stop(self):
        """线程安全停止：在代理自身的事件循环里取消监听任务，触发 close()。

        可从其他线程/事件循环调用，避免跨事件循环操作 asyncio.Server。
        """
        loop = self._loop
        if loop and loop.is_running() and self._serve_task:
            loop.call_soon_threadsafe(self._serve_task.cancel)

    async def close(self):
        """停止代理（仅在自身事件循环内调用）"""
        if self.server:
            await self.server.stop()
            self.server = None
