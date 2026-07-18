import os
import json
import asyncio
import tempfile
import subprocess

from logzero import logger
import tornado.web

from utils.http import CorsRequestHandler
from device.android.tools.adb import adb_cmd


async def _run_adb(args, serial=None, timeout=60):
    return await asyncio.to_thread(
        subprocess.run,
        adb_cmd(*args, serial=serial),
        capture_output=True, text=True, timeout=timeout,
    )


async def list_apps(serial: str) -> list:
    """列出可管理应用：仅三方应用(pm list packages -3)，系统应用不返回。

    Android 无廉价 label，name 退化为包名。
    """
    result = await _run_adb(["shell", "pm", "list", "packages", "-3"], serial=serial, timeout=15)
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip() or "pm list packages 失败")
    apps = []
    for line in result.stdout.splitlines():
        line = line.strip()
        if line.startswith("package:"):
            pkg = line[len("package:"):].strip()
            if pkg:
                apps.append({"id": pkg, "name": pkg, "version": "", "system": False})
    apps.sort(key=lambda a: a["id"])
    return apps


async def uninstall(serial: str, package: str):
    result = await _run_adb(["uninstall", package], serial=serial, timeout=120)
    if result.returncode != 0 or "Success" not in result.stdout:
        raise RuntimeError((result.stdout + result.stderr).strip() or "卸载失败")


async def install(serial: str, apk_path: str):
    result = await _run_adb(["install", "-r", apk_path], serial=serial, timeout=600)
    if result.returncode != 0 or "Success" not in result.stdout:
        raise RuntimeError((result.stdout + result.stderr).strip() or "安装失败")


class _AppBaseHandler(CorsRequestHandler):
    device_manager = None  # 由 AndroidProxyServer 注入

    def _check_device(self, serial):
        """返回 None 表示可用，否则返回 (status, error) 供直接回写。"""
        dm = self.device_manager
        if not dm or serial not in dm.devices:
            return 404, "设备不存在"
        if not dm.devices[serial].online:
            return 409, "设备不在线"
        return None


class AndroidAppsHandler(_AppBaseHandler):
    """GET 列出应用"""

    async def get(self, serial):
        err = self._check_device(serial)
        if err:
            return self.write_json(err[0], {"ok": False, "error": err[1]})
        try:
            apps = await list_apps(serial)
            self.write_json(200, {"apps": apps})
        except Exception as e:
            logger.error(f"[{serial}] 列出应用失败: {e}")
            self.write_json(500, {"ok": False, "error": str(e)})


class AndroidAppUninstallHandler(_AppBaseHandler):
    """POST {id} 卸载应用"""

    async def post(self, serial):
        err = self._check_device(serial)
        if err:
            return self.write_json(err[0], {"ok": False, "error": err[1]})
        try:
            body = json.loads(self.request.body or b"{}")
        except Exception:
            return self.write_json(400, {"ok": False, "error": "请求体非法"})
        pkg = (body.get("id") or "").strip()
        if not pkg:
            return self.write_json(400, {"ok": False, "error": "缺少 id"})
        try:
            await uninstall(serial, pkg)
            self.write_json(200, {"ok": True})
        except Exception as e:
            logger.error(f"[{serial}] 卸载 {pkg} 失败: {e}")
            self.write_json(500, {"ok": False, "error": str(e)})


@tornado.web.stream_request_body
class AndroidAppInstallHandler(_AppBaseHandler):
    """POST 原始 APK 文件体(流式落盘)→ adb install。?filename= 决定后缀。"""

    def prepare(self):
        self._tmp = None
        self._tmp_path = None
        if self.request.method != "POST":
            return
        filename = self.get_query_argument("filename", "app.apk")
        suffix = os.path.splitext(filename)[1] or ".apk"
        fd, self._tmp_path = tempfile.mkstemp(suffix=suffix)
        self._tmp = os.fdopen(fd, "wb")

    def data_received(self, chunk):
        if self._tmp:
            self._tmp.write(chunk)

    async def post(self, serial):
        if self._tmp:
            self._tmp.close()
            self._tmp = None
        err = self._check_device(serial)
        if err:
            self._cleanup()
            return self.write_json(err[0], {"ok": False, "error": err[1]})
        if not self._tmp_path or not os.path.exists(self._tmp_path) or os.path.getsize(self._tmp_path) == 0:
            self._cleanup()
            return self.write_json(400, {"ok": False, "error": "空文件"})
        try:
            await install(serial, self._tmp_path)
            self.write_json(200, {"ok": True})
        except Exception as e:
            logger.error(f"[{serial}] 安装失败: {e}")
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
