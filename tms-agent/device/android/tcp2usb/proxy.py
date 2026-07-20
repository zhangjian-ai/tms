"""
TCP-USB Proxy Main Class
"""
import asyncio
import threading

from typing import Dict, List

from logzero import logger

from device.android.tcp2usb.adb import AdbClient
from device.android.tcp2usb.server import TcpProxyServer
from utils.network import Port


class Tcp2Usb(threading.Thread):
    """TCP-USB代理主类（独立线程内运行自己的事件循环）"""

    # 全局注册表：serial -> 存活代理列表。用于建链前清理残留/孤儿代理。
    _registry: Dict[str, List["Tcp2Usb"]] = {}
    _registry_lock = threading.Lock()

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
        # 停止标志：支持在启动竞态（线程/循环尚未就绪）中被安全请求停止
        self._stop_requested = threading.Event()

        # 登记到全局注册表，确保竞态下丢失引用的孤儿仍可被后续清理
        self._register(self)

    async def open(self, host: str = '0.0.0.0'):
        """启动代理"""
        logger.info(f"Starting TCP-USB proxy for device {self.serial}")

        # 记录本线程事件循环，供其他线程 stop() 时跨线程调度
        self._loop = asyncio.get_running_loop()

        # 若在循环就绪前已请求停止，直接放弃，绝不绑定端口
        if self._stop_requested.is_set():
            logger.info(f"Proxy for {self.serial} 启动前已被请求停止，放弃")
            return
        try:
            client = AdbClient(self.adb_host, self.adb_port)
            device_id = await self._fetch_device_id(client)

            # 拉取设备信息期间可能已被请求停止，此时不再绑定端口，避免遗留监听 socket
            if self._stop_requested.is_set():
                logger.info(f"Proxy for {self.serial} 启动中被请求停止，绑定前放弃")
                return

            self.server = TcpProxyServer(client, self.serial, self.bypass_auth, self.max_connections, device_id)

            self._serve_task = asyncio.create_task(self.server.listen(host, self.proxy_port))
            # 建任务到真正 await 之间若已请求停止，立即取消
            if self._stop_requested.is_set():
                self._serve_task.cancel()
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
        try:
            asyncio.run(self.open())
        finally:
            # 线程真正退出时才注销，确保停止前引用一直可达
            self._unregister(self)

    def stop(self):
        """线程安全停止：置停止标志，并在代理自身的事件循环里取消监听任务。

        可从其他线程/事件循环调用；即便在启动竞态（_loop/_serve_task 尚未就绪）中被调用，
        也会通过停止标志让 open() 在绑定端口前放弃，杜绝遗留监听端口。
        """
        self._stop_requested.set()
        loop = self._loop
        if loop and loop.is_running() and self._serve_task:
            loop.call_soon_threadsafe(self._serve_task.cancel)

    async def close(self):
        """停止代理（仅在自身事件循环内调用）"""
        if self.server:
            await self.server.stop()
            self.server = None

    @classmethod
    def _register(cls, proxy: "Tcp2Usb"):
        with cls._registry_lock:
            cls._registry.setdefault(proxy.serial, []).append(proxy)

    @classmethod
    def _unregister(cls, proxy: "Tcp2Usb"):
        with cls._registry_lock:
            proxies = cls._registry.get(proxy.serial)
            if proxies and proxy in proxies:
                proxies.remove(proxy)
                if not proxies:
                    cls._registry.pop(proxy.serial, None)

    @classmethod
    def stop_existing(cls, serial: str):
        """建链前清理该序列号名下所有已登记代理（含竞态产生的孤儿）。非阻塞。"""
        with cls._registry_lock:
            existing = list(cls._registry.get(serial, []))
        for proxy in existing:
            try:
                proxy.stop()
            except Exception as e:
                logger.warning(f"清理已有代理失败 {serial}: {e}")
