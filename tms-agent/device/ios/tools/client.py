import json
import traceback

from typing import Optional, Tuple
from logzero import logger
from tornado import httpclient

"""
注意tidevice中WDAService类的_is_alive存在问题，可手动将其返回值改为True，使用我们的健康检查即可。

控制链路：Web → Agent(WebSocket) → AsyncHTTPClient(单例连接池) localhost:port(wdaproxy 转发) → 设备 WDA。
"""


class WDAClient:
    """WDA 客户端

    使用单例 AsyncHTTPClient 实现连接复用。
    Web 端已保证操作串行化，agent 端无需加锁。
    """

    def __init__(self, host: str = "localhost", port: int = 8100):
        self.host = host
        self.port = port
        self.base_url = f"http://{host}:{port}"
        self.session_id = None
        self._http_client = httpclient.AsyncHTTPClient()

    async def _request(self, method: str, path: str, body=None, headers=None, timeout=30.0):
        """统一的 HTTP 请求方法"""
        request = httpclient.HTTPRequest(
            url=f"{self.base_url}{path}",
            method=method,
            body=body,
            headers=headers or {},
            request_timeout=timeout,
            connect_timeout=1.0,  # 本地连接，1秒足够
        )
        response = await self._http_client.fetch(request, raise_error=False)
        return response

    async def _control_request(self, method: str, path: str, body=None, timeout=10.0):
        """控制命令请求"""
        try:
            response = await self._request(
                method, path, body=body,
                headers={"Content-Type": "application/json"},
                timeout=timeout
            )
            if response.code == 200:
                return response
            logger.warning(f"WDA {path} 返回 {response.code}: {response.body}")
            return response
        except Exception as e:
            logger.warning(f"WDA {path} 请求失败: {e}")
            return None

    @staticmethod
    def _json(response) -> dict:
        if not response.body:
            return {}
        return json.loads(response.body.decode("utf-8"))

    async def health_check(self) -> bool:
        """健康检查：GET /status"""
        try:
            response = await self._request("GET", "/status", timeout=3.0)
            return response.code == 200
        except Exception:
            return False
            return False

    async def create_session(self) -> bool:
        """创建会话：POST /session"""
        try:
            response = await self._request(
                "POST", "/session",
                body=json.dumps({"capabilities": {}}),
                headers={"Content-Type": "application/json"}
            )
            if response.code == 200:
                data = self._json(response)
                self.session_id = data.get("sessionId")
                return True
            return False
        except Exception as e:
            logger.error(f"创建 WDA 会话失败: {e}")
            return False

    async def get_window_size(self) -> Optional[Tuple[int, int]]:
        """获取屏幕尺寸"""
        if not self.session_id:
            return None
        try:
            response = await self._request("GET", f"/session/{self.session_id}/window/size")
            if response.code == 200:
                data = self._json(response)
                value = data.get("value", {})
                return int(value["width"]), int(value["height"])
            return None
        except Exception:
            return None

    async def tap(self, x: int, y: int) -> bool:
        """点击"""
        resp = await self._control_request(
            "POST", f"/session/{self.session_id}/wda/tap",
            body=json.dumps({"x": x, "y": y})
        )
        return resp is not None and resp.code == 200

    async def swipe(self, from_x: int, from_y: int, to_x: int, to_y: int, duration: float = 0.1) -> bool:
        """滑动"""
        resp = await self._control_request(
            "POST", f"/session/{self.session_id}/wda/dragfromtoforduration",
            body=json.dumps({
                "fromX": from_x, "fromY": from_y,
                "toX": to_x, "toY": to_y,
                "duration": duration
            })
        )
        return resp is not None and resp.code == 200

    async def touch_and_hold(self, x: int, y: int, duration: float = 1.0) -> bool:
        """长按"""
        resp = await self._control_request(
            "POST", f"/session/{self.session_id}/wda/touchAndHold",
            body=json.dumps({"x": x, "y": y, "duration": duration}),
            timeout=max(10.0, duration + 5.0)
        )
        return resp is not None and resp.code == 200

    async def home(self) -> bool:
        """HOME 键"""
        resp = await self._control_request(
            "POST", f"/session/{self.session_id}/wda/pressButton",
            body=json.dumps({"name": "home"})
        )
        return resp is not None and resp.code == 200

    async def unlock(self) -> bool:
        resp = await self._control_request(
            "POST", f"/session/{self.session_id}/wda/pressButton",
            body=json.dumps({"name": "home"})
        )
        return resp is not None and resp.code == 200

    async def screenshot(self) -> Optional[str]:
        try:
            response = await self._request("GET", "/screenshot")
            if response.code == 200:
                data = self._json(response)
                return data.get("value")
            return None
        except Exception:
            return None

    async def get_source(self) -> Optional[str]:
        try:
            response = await self._request("GET", "/source")
            if response.code == 200:
                data = self._json(response)
                return data.get("value")
            return None
        except Exception:
            return None

    async def close(self):
        # 单例 AsyncHTTPClient 不需要手动关闭
        pass
