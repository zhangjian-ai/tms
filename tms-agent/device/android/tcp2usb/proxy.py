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
    """TCP-USB代理主类"""

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
        self.serial = serial
        self.proxy_port = Port.get("android")
        self.bypass_auth = bypass_auth
        self.max_connections = max_connections
        self.adb_host = adb_host
        self.adb_port = adb_port

        # 服务器将在 open() 方法中创建（需要异步环境）
        self.server = None

    def __del__(self):
        self.close()

    async def open(self, host: str = '0.0.0.0'):
        """启动代理"""
        logger.info(f"Starting TCP-USB proxy for device {self.serial}")
        logger.info(f"Listening on {host}:{self.proxy_port}")
        logger.info(f"Auth bypass: {'enabled' if self.bypass_auth else 'disabled'}")
        logger.info(f"Max connections: {self.max_connections}")

        try:
            # 创建ADB客户端
            client = AdbClient(self.adb_host, self.adb_port)
            
            # 预先获取设备ID
            device_id = await self._fetch_device_id(client)

            # 创建代理服务器
            self.server = TcpProxyServer(client, self.serial, self.bypass_auth, self.max_connections, device_id)
            
            # 启动监听
            await self.server.listen(host, self.proxy_port)
        except KeyboardInterrupt:
            logger.info("Proxy stopped by user")
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

    async def close(self):
        """停止代理"""
        if self.server:
            await self.server.stop()
