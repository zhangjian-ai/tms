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
            full_url = self.url + self.config.get("uri", "/ws/device")
            logger.info(f"正在连接WebSocket服务器: {full_url}")

            request = httpclient.HTTPRequest(
                full_url,
                validate_cert=False,
                connect_timeout=10.0,
                request_timeout=30.0
            )

            ws = await websocket.websocket_connect(request)
            msg = await ws.read_message()

            if msg == "OKAY":
                return ws
            else:
                return None

        except Exception as e:
            logger.error(f"WebSocket连接失败: {e}")
            logger.error(f"服务器配置: {self.config}")
            # 返回 None 供调用方处理失败
            return None


ws_client = WSClient()
