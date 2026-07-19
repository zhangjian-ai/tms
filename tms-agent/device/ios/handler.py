import json
import gzip
import base64
import socket
import io
import time
import urllib.request
import tornado.web
import tornado.ioloop
import tornado.httpserver
import tornado.websocket
from logzero import logger
from typing import Any
from tornado import httputil
from tornado.iostream import IOStream, StreamClosedError
from datetime import datetime
from PIL import Image

from device.ios.tools.client import WDAClient
from device.ios.apps import IOSAppsHandler, IOSAppUninstallHandler, IOSAppInstallHandler
from utils.variables import settings


def _encode_source(xml: str) -> str:
    """gzip 压缩后 base64 编码 UI 源，前端 pako 解压。"""
    return base64.b64encode(gzip.compress(xml.encode("utf-8"))).decode("utf-8")


class MjpegReader:
    """
    MJPEG 流读取器

    MJPEG format:
    Content-Type: multipart/x-mixed-replace; boundary=--BoundaryString
    --BoundaryString
    Content-type: image/jpg
    Content-Length: 12390

    ... image-data here ...
    """
    def __init__(self, url: str):
        self._url = url

    async def aiter_content(self):
        """异步迭代 MJPEG 内容"""
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM, 0)
        stream = IOStream(s)
        try:
            url = urllib.request.urlparse(self._url)
            host, port = url.netloc.split(":")
            port = int(port)
            path = url.path or "/"
            await stream.connect((host, port))
            await stream.write(
                "GET {path} HTTP/1.0\r\nHost: {netloc}\r\n\r\n".format(
                    path=path, netloc=url.netloc).encode('utf-8'))
            await stream.read_until(b"\r\n\r\n")

            while True:
                line = await stream.read_until(b'\r\n')
                if not line.startswith(b"Content-Length"):
                    continue
                length = int(line.decode('utf-8').split(": ")[1])
                await stream.read_until(b"\r\n")
                yield await stream.read_bytes(length)
        finally:
            stream.close()


class IOSScreenStreamWebSocket(tornado.websocket.WebSocketHandler):
    """iOS 投屏 WebSocket - 使用 MJPEG 流式传输"""

    device_manager = None

    TARGET_FPS = 25
    # MJPEG 重压缩质量：0 表示透传原始帧，1-95 才重编码
    JPEG_QUALITY = settings.get("ios", {}).get("proxy", {}).get("mjpeg_quality", 0)

    # 每个 udid 当前活跃的投屏客户端
    _active_stream_clients = {}

    def __init__(self, application: tornado.web.Application, request: httputil.HTTPServerRequest, **kwargs: Any):
        super().__init__(application, request, **kwargs)
        self.udid = None
        self.mjpeg_port = None
        self.streaming = False
        self._frame_interval = 1.0 / self.TARGET_FPS

    def check_origin(self, origin):
        """允许跨域"""
        return True

    def open(self, udid):
        """WebSocket 连接建立"""
        self.udid = udid
        self.streaming = False

        if self.ws_connection and not self.ws_connection.is_closing():
            self.write_message(json.dumps({
                "type": "connected",
                "serial": udid,
                "service": "screen_stream",
                "timestamp": datetime.now().isoformat()
            }))

    def on_message(self, message):
        """处理 WebSocket 消息"""
        try:
            data = json.loads(message)
            msg_type = data.get("type")

            if msg_type == "ping":
                if self.ws_connection and not self.ws_connection.is_closing():
                    self.write_message(json.dumps({"type": "pong"}))

            elif msg_type == "start_stream":
                tornado.ioloop.IOLoop.current().add_callback(self._start_stream, data)

            elif msg_type == "stop_stream":
                tornado.ioloop.IOLoop.current().add_callback(self._stop_stream)

            else:
                logger.warning(f"iOS 投屏 WebSocket 不支持的消息类型: {msg_type}")

        except Exception as e:
            logger.error(f"iOS 投屏 WebSocket 消息处理失败: {e}")
            if self.ws_connection and not self.ws_connection.is_closing():
                self.write_message(json.dumps({
                    "type": "error",
                    "message": str(e)
                }))

    async def _start_stream(self, data):
        """启动投屏流 - 使用 MJPEG"""
        try:
            if self.streaming:
                await self.write_message(json.dumps({
                    "type": "error",
                    "message": "Stream already running"
                }))
                return

            # 同一设备只允许一个投屏
            active = self._active_stream_clients.get(self.udid)
            if active is not None and active is not self \
                    and getattr(active, "ws_connection", None) is not None:
                await self.write_message(json.dumps({
                    "type": "error",
                    "message": "设备已在其他页面投屏"
                }))
                return

            if self.device_manager and self.udid in self.device_manager.devices:
                device = self.device_manager.devices[self.udid]
                if not device.online or not device.init:
                    raise Exception(f"设备 {self.udid} 未就绪")
                if not (device.occupied and device.cast):
                    await self.write_message(json.dumps({
                        "type": "error",
                        "message": "设备未开启投屏"
                    }))
                    return
                self.mjpeg_port = device.mjpeg_port
                if not self.mjpeg_port:
                    raise Exception(f"设备 {self.udid} MJPEG 端口未分配")
            else:
                raise Exception(f"设备 {self.udid} 不存在或代理未启动")

            self.streaming = True
            self._active_stream_clients[self.udid] = self

            await self.write_message(json.dumps({
                "type": "stream_started",
                "fps": self.TARGET_FPS
            }))

            tornado.ioloop.IOLoop.current().spawn_callback(self._stream_mjpeg)

        except Exception as e:
            logger.error(f"启动 iOS 投屏失败: {e}")
            self.streaming = False
            await self.write_message(json.dumps({
                "type": "error",
                "message": f"Failed to start stream: {e}"
            }))

    async def _stream_mjpeg(self):
        """MJPEG 流式传输 - 限帧 + 压缩"""
        disconnected = False
        try:
            mjpeg_url = f"http://localhost:{self.mjpeg_port}"
            mjpeg_reader = MjpegReader(mjpeg_url)
            last_frame_time = 0

            async for jpeg_data in mjpeg_reader.aiter_content():
                if not self.streaming or not self.ws_connection or self.ws_connection.is_closing():
                    break

                # 帧率限制
                now = time.monotonic()
                if now - last_frame_time < self._frame_interval:
                    continue
                last_frame_time = now

                if self.JPEG_QUALITY:
                    try:
                        img = Image.open(io.BytesIO(jpeg_data))
                        buf = io.BytesIO()
                        img.save(buf, format="JPEG", quality=self.JPEG_QUALITY)
                        jpeg_data = buf.getvalue()
                    except Exception:
                        pass  # 压缩失败则发送原始帧

                await self.write_message(jpeg_data, binary=True)

            # 流意外结束（生成器耗尽）
            if self.streaming:
                disconnected = True

        except tornado.websocket.WebSocketClosedError:
            # 前端 WS 已关闭（客户端主动断开）；须先于 StreamClosedError 捕获
            logger.info(f"iOS 投屏客户端断开（页面关闭/切走）: {self.udid}")
        except StreamClosedError:
            # MJPEG 转发 socket 关闭（设备释放/拔线）；仅在仍推流时才视为源断开
            if self.streaming:
                logger.info(f"iOS MJPEG 源已关闭，结束投屏（设备释放或断开）: {self.udid}")
                disconnected = True
        except Exception as e:
            logger.error(f"iOS MJPEG 流异常: {e}")
            disconnected = True
        finally:
            self.streaming = False

        if disconnected:
            try:
                if self.ws_connection and not self.ws_connection.is_closing():
                    await self.write_message(json.dumps({"type": "device_disconnected", "serial": self.udid}))
                    self.close()
            except Exception:
                pass

    async def _stop_stream(self):
        """停止投屏流"""
        try:
            logger.info(f"停止 iOS 投屏: {self.udid}")
            self.streaming = False
            self._release_active_client()

            await self.write_message(json.dumps({
                "type": "stream_stopped"
            }))

        except Exception as e:
            logger.error(f"停止 iOS 投屏失败: {e}")

    def _release_active_client(self):
        """释放本客户端对该设备投屏槽位的占用。"""
        if self._active_stream_clients.get(self.udid) is self:
            self._active_stream_clients.pop(self.udid, None)

    def on_close(self):
        """WebSocket 连接关闭"""
        self.streaming = False
        self._release_active_client()


class IOSDeviceControlWebSocket(tornado.websocket.WebSocketHandler):
    """iOS 设备控制 WebSocket"""

    device_manager = None

    def __init__(self, application: tornado.web.Application, request: httputil.HTTPServerRequest, **kwargs: Any):
        super().__init__(application, request, **kwargs)
        self.udid = None
        self.wda_client: WDAClient = None
        self.device_resolution = None

    def check_origin(self, origin):
        """允许跨域"""
        return True

    async def open(self, udid):
        """WebSocket 连接建立"""
        self.udid = udid
        try:
            if not self.device_manager or udid not in self.device_manager.devices:
                await self.write_message(json.dumps({
                    "type": "error",
                    "message": f"Device not found: {udid}"
                }))
                return

            device = self.device_manager.devices[udid]
            if not device.online or not device.init:
                raise Exception(f"设备 {udid} 未就绪")

            wda_client = device.wda_client
            if not wda_client or not wda_client.session_id:
                raise Exception(f"设备 {udid} WDA 客户端未初始化")

            self.wda_client = wda_client

            size = await wda_client.get_window_size()
            if size:
                self.device_resolution = size

            if self.ws_connection and not self.ws_connection.is_closing():
                await self.write_message(json.dumps({
                    "type": "connected",
                    "serial": udid,
                    "service": "device_control",
                    "device_resolution": self.device_resolution,
                    "timestamp": datetime.now().isoformat()
                }))

        except Exception as e:
            logger.error(f"连接 iOS 设备失败: {e}")
            if self.ws_connection and not self.ws_connection.is_closing():
                await self.write_message(json.dumps({
                    "type": "error",
                    "message": f"Failed to connect: {e}"
                }))

    def on_message(self, message):
        """处理 WebSocket 消息"""
        try:
            data = json.loads(message)
            msg_type = data.get("type")

            if msg_type == "ping":
                if self.ws_connection and not self.ws_connection.is_closing():
                    self.write_message(json.dumps({"type": "pong"}))

            elif msg_type == "screenshot":
                tornado.ioloop.IOLoop.current().add_callback(self._handle_screenshot, data)

            elif msg_type == "click":
                tornado.ioloop.IOLoop.current().add_callback(self._handle_click, data)

            elif msg_type == "long_click":
                tornado.ioloop.IOLoop.current().add_callback(self._handle_long_click, data)

            elif msg_type == "swipe":
                tornado.ioloop.IOLoop.current().add_callback(self._handle_swipe, data)

            elif msg_type == "dump_hierarchy":
                tornado.ioloop.IOLoop.current().add_callback(self._handle_dump_hierarchy, data)

            elif msg_type == "home":
                tornado.ioloop.IOLoop.current().add_callback(self._handle_home, data)

            elif msg_type == "wake_screen":
                tornado.ioloop.IOLoop.current().add_callback(self._handle_wake_screen, data)

            else:
                logger.warning(f"iOS 设备控制 WebSocket 不支持的消息类型: {msg_type}")
                if self.ws_connection and not self.ws_connection.is_closing():
                    self.write_message(json.dumps({
                        "type": "error",
                        "message": f"Unsupported message type: {msg_type}"
                    }))

        except Exception as e:
            logger.error(f"iOS 设备控制 WebSocket 消息处理失败: {e}")
            if self.ws_connection and not self.ws_connection.is_closing():
                self.write_message(json.dumps({
                    "type": "error",
                    "message": str(e)
                }))

    async def _handle_screenshot(self, data):
        """处理截图请求"""
        try:
            if not self.wda_client:
                raise Exception("设备未连接")

            img_base64 = await self.wda_client.screenshot()
            if img_base64:
                await self.write_message(json.dumps({
                    "type": "screenshot_result",
                    "success": True,
                    "data": {
                        "image": img_base64,
                        "format": "png",
                        "timestamp": datetime.now().isoformat()
                    }
                }))
            else:
                raise Exception("截图失败")

        except Exception as e:
            logger.error(f"截图失败: {e}")
            await self.write_message(json.dumps({
                "type": "screenshot_result",
                "success": False,
                "error": str(e)
            }))

    async def _handle_click(self, data):
        """处理点击请求"""
        try:
            if not self.wda_client:
                raise Exception("设备未连接")

            x = data.get("x")
            y = data.get("y")

            if x is None or y is None:
                raise Exception("缺少坐标参数")

            device_x = int(x)
            device_y = int(y)

            success = await self.wda_client.tap(device_x, device_y)

            await self.write_message(json.dumps({
                "type": "click_result",
                "success": success,
                "error": None if success else "WDA tap 请求失败",
                "data": {
                    "device_x": device_x,
                    "device_y": device_y,
                    "device_resolution": f"{self.device_resolution[0]}x{self.device_resolution[1]}" if self.device_resolution else "unknown"
                }
            }))

        except Exception as e:
            logger.error(f"点击失败: {e}")
            await self.write_message(json.dumps({
                "type": "click_result",
                "success": False,
                "error": str(e)
            }))

    async def _handle_long_click(self, data):
        """处理长按请求"""
        try:
            if not self.wda_client:
                raise Exception("设备未连接")

            x = data.get("x")
            y = data.get("y")
            duration = data.get("duration", 1.0)

            if x is None or y is None:
                raise Exception("缺少坐标参数")

            device_x = int(x)
            device_y = int(y)

            success = await self.wda_client.touch_and_hold(device_x, device_y, duration)

            await self.write_message(json.dumps({
                "type": "long_click_result",
                "success": success,
                "error": None if success else "WDA touch_and_hold 请求失败",
                "data": {
                    "device_x": device_x,
                    "device_y": device_y,
                    "device_resolution": f"{self.device_resolution[0]}x{self.device_resolution[1]}" if self.device_resolution else "unknown"
                }
            }))

        except Exception as e:
            logger.error(f"长按失败: {e}")
            await self.write_message(json.dumps({
                "type": "long_click_result",
                "success": False,
                "error": str(e)
            }))

    async def _handle_swipe(self, data):
        """处理滑动请求"""
        try:
            if not self.wda_client:
                raise Exception("设备未连接")

            start_x = data.get("start_x")
            start_y = data.get("start_y")
            end_x = data.get("end_x")
            end_y = data.get("end_y")
            duration = data.get("duration", 0.1)

            if any(v is None for v in [start_x, start_y, end_x, end_y]):
                raise Exception("缺少滑动坐标参数")

            device_start_x = int(start_x)
            device_start_y = int(start_y)
            device_end_x = int(end_x)
            device_end_y = int(end_y)

            success = await self.wda_client.swipe(
                device_start_x, device_start_y,
                device_end_x, device_end_y,
                duration
            )

            await self.write_message(json.dumps({
                "type": "swipe_result",
                "success": success,
                "error": None if success else "WDA swipe 请求失败",
                "data": {
                    "device_start_x": device_start_x,
                    "device_start_y": device_start_y,
                    "device_end_x": device_end_x,
                    "device_end_y": device_end_y,
                    "duration": duration,
                    "device_resolution": f"{self.device_resolution[0]}x{self.device_resolution[1]}" if self.device_resolution else "unknown"
                }
            }))

        except Exception as e:
            logger.error(f"滑动失败: {e}")
            await self.write_message(json.dumps({
                "type": "swipe_result",
                "success": False,
                "error": str(e)
            }))

    async def _handle_home(self, data):
        """处理 HOME 键：回到主屏"""
        try:
            if not self.wda_client:
                raise Exception("设备未连接")
            success = await self.wda_client.home()
            await self.write_message(json.dumps({
                "type": "home_result",
                "success": success,
                "timestamp": datetime.now().isoformat()
            }))
        except Exception as e:
            logger.error(f"HOME 失败: {e}")
            await self.write_message(json.dumps({
                "type": "home_result",
                "success": False,
                "error": str(e)
            }))

    async def _handle_wake_screen(self, data):
        """处理点亮/锁屏：切换屏幕状态"""
        try:
            if not self.wda_client:
                raise Exception("设备未连接")
            success = await self.wda_client.toggle_screen()
            await self.write_message(json.dumps({
                "type": "wake_screen_result",
                "success": success,
                "timestamp": datetime.now().isoformat()
            }))
        except Exception as e:
            logger.error(f"唤醒屏幕处理失败: {e}")
            await self.write_message(json.dumps({
                "type": "wake_screen_result",
                "success": False,
                "error": str(e)
            }))

    async def _handle_dump_hierarchy(self, data):
        """处理 UI 层次结构导出请求"""
        try:
            if not self.wda_client:
                raise Exception("设备未连接")

            xml = await self.wda_client.get_source()
            if xml:
                encoded = _encode_source(xml)

                await self.write_message(json.dumps({
                    "type": "dump_hierarchy_result",
                    "success": True,
                    "data": {
                        "hierarchy": encoded,
                        "compressed": True,
                        "encoding": "base64",
                        "timestamp": datetime.now().isoformat()
                    }
                }))
            else:
                raise Exception("获取页面源失败")

        except Exception as e:
            logger.error(f"UI 层次结构导出失败: {e}")
            await self.write_message(json.dumps({
                "type": "dump_hierarchy_result",
                "success": False,
                "error": str(e)
            }))

    def on_close(self):
        """WebSocket 连接关闭"""
        self.wda_client = None


class IOSElementInspectorWebSocket(tornado.websocket.WebSocketHandler):
    """iOS 元素检查器 WebSocket - 独立通道。"""

    device_manager = None

    def check_origin(self, origin):
        return True

    def open(self, udid):
        self.udid = udid
        self.wda_client = None
        tornado.ioloop.IOLoop.current().add_callback(self._init_connection, udid)

    async def _init_connection(self, udid):
        try:
            if not self.device_manager or udid not in self.device_manager.devices:
                raise Exception(f"设备 {udid} 不存在")

            device = self.device_manager.devices[udid]
            if not device.online or not device.init:
                raise Exception(f"设备 {udid} 未就绪")

            wda_client = device.wda_client
            if not wda_client or not wda_client.session_id:
                raise Exception(f"设备 {udid} WDA 客户端未初始化")

            self.wda_client = wda_client

            size = await wda_client.get_window_size()
            device_resolution = size if size else (0, 0)

            if self.ws_connection and not self.ws_connection.is_closing():
                await self.write_message(json.dumps({
                    "type": "connected",
                    "serial": udid,
                    "service": "element_inspector",
                    "device_resolution": device_resolution,
                    "timestamp": datetime.now().isoformat()
                }))

        except Exception as e:
            logger.error(f"元素检查器连接失败: {e}")
            if self.ws_connection and not self.ws_connection.is_closing():
                await self.write_message(json.dumps({
                    "type": "error",
                    "message": f"Failed to connect: {e}"
                }))

    def on_message(self, message):
        try:
            data = json.loads(message)
            msg_type = data.get("type")

            if msg_type == "ping":
                if self.ws_connection and not self.ws_connection.is_closing():
                    self.write_message(json.dumps({"type": "pong"}))

            elif msg_type == "get_ui_hierarchy":
                tornado.ioloop.IOLoop.current().add_callback(self._handle_get_ui_hierarchy, data)

            elif msg_type == "get_xml_only":
                tornado.ioloop.IOLoop.current().add_callback(self._handle_get_xml_only, data)

            else:
                logger.warning(f"iOS 元素检查器不支持的消息类型: {msg_type}")

        except Exception as e:
            logger.error(f"iOS 元素检查器消息处理失败: {e}")

    async def _handle_get_ui_hierarchy(self, data):
        """获取完整 UI 层次结构"""
        try:
            if not self.wda_client:
                raise Exception("设备未连接")

            xml = await self.wda_client.get_source()
            if xml:
                encoded = _encode_source(xml)

                await self.write_message(json.dumps({
                    "type": "ui_hierarchy",
                    "success": True,
                    "data": {
                        "xml": encoded,
                        "compressed": True,
                        "encoding": "base64",
                        "timestamp": datetime.now().isoformat()
                    }
                }))
            else:
                raise Exception("获取页面源失败")

        except Exception as e:
            logger.error(f"获取 UI 层次结构失败: {e}")
            if self.ws_connection and not self.ws_connection.is_closing():
                await self.write_message(json.dumps({
                    "type": "ui_hierarchy",
                    "success": False,
                    "error": str(e)
                }))

    async def _handle_get_xml_only(self, data):
        """仅获取 XML 内容（用于变化检测）"""
        try:
            if not self.wda_client:
                raise Exception("设备未连接")

            xml = await self.wda_client.get_source()
            if xml:
                encoded = _encode_source(xml)

                await self.write_message(json.dumps({
                    "type": "xml_only",
                    "success": True,
                    "data": {
                        "xml": encoded,
                        "compressed": True,
                        "encoding": "base64",
                        "timestamp": datetime.now().isoformat()
                    }
                }))
            else:
                raise Exception("获取 XML 内容失败")

        except Exception as e:
            logger.error(f"获取 XML 内容失败: {e}")
            if self.ws_connection and not self.ws_connection.is_closing():
                await self.write_message(json.dumps({
                    "type": "xml_only",
                    "success": False,
                    "error": str(e)
                }))

    def on_close(self):
        self.wda_client = None


class IOSProxyServer:
    """iOS 代理服务器"""

    def __init__(self, device_manager=None):
        self.config = settings["ios"]
        self.device_manager = device_manager
        self.app = self.make_app()

    def make_app(self):
        """创建 Tornado 应用 - WebSocket 投屏/控制/检查器 + HTTP 应用管理"""
        if self.device_manager:
            IOSScreenStreamWebSocket.device_manager = self.device_manager
            IOSDeviceControlWebSocket.device_manager = self.device_manager
            IOSElementInspectorWebSocket.device_manager = self.device_manager
            IOSAppsHandler.device_manager = self.device_manager
            IOSAppUninstallHandler.device_manager = self.device_manager
            IOSAppInstallHandler.device_manager = self.device_manager

        return tornado.web.Application([
            (r"/devices/([^/]+)/screen", IOSScreenStreamWebSocket),      # 投屏
            (r"/devices/([^/]+)/control", IOSDeviceControlWebSocket),    # 控制
            (r"/devices/([^/]+)/inspector", IOSElementInspectorWebSocket),  # 元素检查器
            (r"/devices/([^/]+)/apps", IOSAppsHandler),                  # 应用列表
            (r"/devices/([^/]+)/apps/uninstall", IOSAppUninstallHandler),  # 卸载应用
            (r"/devices/([^/]+)/apps/install", IOSAppInstallHandler),    # 安装应用
        ], debug=self.config['proxy']['debug'])

    def run(self):
        """启动服务器"""
        host = self.config['proxy'].get('host', '0.0.0.0')
        port = self.config['proxy']['port']
        logger.info(f"启动 iOS 代理服务: {host}:{port}")

        # 显式 HTTPServer 以放开安装包(IPA)上传体积上限
        server = tornado.httpserver.HTTPServer(self.app, max_body_size=2 * 1024 * 1024 * 1024)
        server.listen(port, address=host)
        logger.info("iOS 设备代理服务器已启动! 🚀")
