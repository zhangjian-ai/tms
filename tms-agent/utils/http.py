import tornado.web


class CorsRequestHandler(tornado.web.RequestHandler):
    """允许跨源直连的 HTTP 基类。

    agent 代理端点被浏览器/自动化脚本从不同源直接调用，需放开 CORS。
    与现有 WebSocket 的 check_origin=True 模型一致，不额外做鉴权（信任内网）。
    """

    def set_default_headers(self):
        self.set_header("Access-Control-Allow-Origin", "*")
        self.set_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.set_header("Access-Control-Allow-Headers", "Content-Type")

    def options(self, *args):
        self.set_status(204)
        self.finish()

    def write_json(self, status: int, payload: dict):
        self.set_status(status)
        self.set_header("Content-Type", "application/json")
        self.finish(payload)
