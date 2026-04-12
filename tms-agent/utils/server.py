from logzero import logger
from tornado import httpclient, websocket

from utils.variables import settings


class WSClient:
    """WebSocket客户端 - 用于与服务端通信"""

    def __init__(self, host: str = None, port: int = None):
        self.config = settings.get("server", {})
        host = host or self.config.get("host", "127.0.0.1")
        port = port or self.config.get("port", 8888)

        self.url = f"ws://{host}:{port}"

    async def connect(self):
        """连接到WebSocket服务器"""
        try:
            # 构建完整的WebSocket URL
            full_url = self.url + self.config.get("uri", "/ws/device")
            logger.info(f"正在连接WebSocket服务器: {full_url}")

            # 创建连接请求
            request = httpclient.HTTPRequest(
                full_url,
                validate_cert=False,
                connect_timeout=10.0,
                request_timeout=30.0
            )

            # 建立WebSocket连接
            ws = await websocket.websocket_connect(request)
            msg = await ws.read_message()

            if msg == "OKAY":
                logger.info("WebSocket服务器连接成功")
                return ws
            else:
                return None

        except Exception as e:
            logger.error(f"WebSocket连接失败: {e}")
            logger.error(f"服务器配置: {self.config}")
            # 返回None而不是抛出异常，这样调用方可以处理连接失败的情况
            return None


ws_client = WSClient()
