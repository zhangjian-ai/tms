import os
import json
import tempfile

from logzero import logger
import tornado.web

from utils.http import CorsRequestHandler


class _AppBaseHandler(CorsRequestHandler):
    device_manager = None  # 由 IOSProxyServer 注入

    def _check_device(self, udid):
        """返回 None 表示可用，否则返回 (status, error)。"""
        dm = self.device_manager
        if not dm or udid not in dm.devices:
            return 404, "设备不存在"
        device = dm.devices[udid]
        if not device.online or not device.init:
            return 409, "设备未就绪"
        return None


class IOSAppsHandler(_AppBaseHandler):
    """GET 列出应用"""

    async def get(self, udid):
        err = self._check_device(udid)
        if err:
            return self.write_json(err[0], {"ok": False, "error": err[1]})
        dm = self.device_manager
        try:
            apps = await dm._idb_call(dm.idb.list_apps, udid)
            self.write_json(200, {"apps": apps})
        except Exception as e:
            logger.error(f"[{udid}] 列出应用失败: {e}")
            self.write_json(500, {"ok": False, "error": str(e)})


class IOSAppUninstallHandler(_AppBaseHandler):
    """POST {id} 卸载应用"""

    async def post(self, udid):
        err = self._check_device(udid)
        if err:
            return self.write_json(err[0], {"ok": False, "error": err[1]})
        try:
            body = json.loads(self.request.body or b"{}")
        except Exception as e:
            return self.write_json(400, {"ok": False, "error": str(e)})
        bundle_id = (body.get("id") or "").strip()
        if not bundle_id:
            return self.write_json(400, {"ok": False, "error": "缺少 id"})
        dm = self.device_manager
        try:
            await dm._idb_call(dm.idb.uninstall_app, udid, bundle_id)
            self.write_json(200, {"ok": True})
        except Exception as e:
            logger.error(f"[{udid}] 卸载 {bundle_id} 失败: {e}")
            self.write_json(500, {"ok": False, "error": str(e)})


@tornado.web.stream_request_body
class IOSAppInstallHandler(_AppBaseHandler):
    """POST 原始 IPA 文件体(流式落盘)→ go-ios install。?filename= 决定后缀。"""

    def prepare(self):
        self._tmp = None
        self._tmp_path = None
        if self.request.method != "POST":
            return
        filename = self.get_query_argument("filename", "app.ipa")
        suffix = os.path.splitext(filename)[1] or ".ipa"
        fd, self._tmp_path = tempfile.mkstemp(suffix=suffix)
        self._tmp = os.fdopen(fd, "wb")

    def data_received(self, chunk):
        if self._tmp:
            self._tmp.write(chunk)

    async def post(self, udid):
        if self._tmp:
            self._tmp.close()
            self._tmp = None
        err = self._check_device(udid)
        if err:
            self._cleanup()
            return self.write_json(err[0], {"ok": False, "error": err[1]})
        if not self._tmp_path or not os.path.exists(self._tmp_path) or os.path.getsize(self._tmp_path) == 0:
            self._cleanup()
            return self.write_json(400, {"ok": False, "error": "空文件"})
        dm = self.device_manager
        try:
            await dm._idb_call(dm.idb.install_app, udid, self._tmp_path)
            self.write_json(200, {"ok": True})
        except Exception as e:
            logger.error(f"[{udid}] 安装失败: {e}")
            self.write_json(500, {"ok": False, "error": str(e)})
        finally:
            self._cleanup()

    def _cleanup(self):
        try:
            if self._tmp:
                self._tmp.close()
        except Exception:
            pass
        if self._tmp_path and os.path.exists(self._tmp_path):
            try:
                os.remove(self._tmp_path)
            except Exception:
                pass
        self._tmp = None
        self._tmp_path = None

    def on_connection_close(self):
        # 客户端上传中途断开时 post 不会执行，在此清理临时文件
        self._cleanup()
