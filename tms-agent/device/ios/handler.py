import json
import gzip
import base64
import asyncio
import socket
import io
import time
import urllib.request
import tornado.web
import tornado.ioloop
import tornado.websocket
from logzero import logger
from typing import Any
from tornado import httputil
from tornado.iostream import IOStream
from datetime import datetime
from PIL import Image

from device.ios.tools.client import WDAClient
from utils.variables import settings


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

    # 设备管理器实例（由 IOSProxyServer 设置）
    device_manager = None

    # 投屏参数
    TARGET_FPS = 25
    JPEG_QUALITY = 60  # JPEG 压缩质量（1-95，越低体积越小）

    def __init__(self, application: tornado.web.Application, request: httputil.HTTPServerRequest, **kwargs: Any):
        super().__init__(application, request, **kwargs)
        self.udid = None
        self.mjpeg_port = None
        self.streaming = False
        self.stream_task = None
        self._frame_interval = 1.0 / self.TARGET_FPS

    def check_origin(self, origin):
        """允许跨域"""
        return True

    def open(self, udid):
        """WebSocket 连接建立"""
        self.udid = udid
        self.streaming = False

        # 发送连接确认
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

            # 从设备管理器获取设备的 MJPEG 端口
            if self.device_manager and self.udid in self.device_manager.devices:
                device = self.device_manager.devices[self.udid]
                if not device.online or not device.init:
                    raise Exception(f"设备 {self.udid} 未就绪")
                self.mjpeg_port = device.mjpeg_port
                if not self.mjpeg_port:
                    raise Exception(f"设备 {self.udid} MJPEG 端口未分配")
            else:
                # 兼容旧逻辑：从客户端消息获取（不推荐）
                self.mjpeg_port = data.get("mjpeg_port", 9100)
                logger.warning(f"无法从设备管理器获取端口，使用客户端提供的端口: {self.mjpeg_port}")

            self.streaming = True

            # 发送流开始通知
            await self.write_message(json.dumps({
                "type": "stream_started",
                "fps": self.TARGET_FPS
            }))

            # 启动 MJPEG 流任务
            self.stream_task = tornado.ioloop.IOLoop.current().spawn_callback(self._stream_mjpeg)

        except Exception as e:
            logger.error(f"启动 iOS 投屏失败: {e}")
            self.streaming = False
            await self.write_message(json.dumps({
                "type": "error",
                "message": f"Failed to start stream: {e}"
            }))

    async def _stream_mjpeg(self):
        """MJPEG 流式传输 - 限帧 + 压缩"""
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

                # JPEG 重压缩降低传输体积
                try:
                    img = Image.open(io.BytesIO(jpeg_data))
                    buf = io.BytesIO()
                    img.save(buf, format="JPEG", quality=self.JPEG_QUALITY)
                    jpeg_data = buf.getvalue()
                except Exception:
                    pass  # 压缩失败则发送原始帧

                await self.write_message(jpeg_data, binary=True)

        except Exception as e:
            logger.error(f"iOS MJPEG 流异常: {e}")
        finally:
            self.streaming = False

    async def _stop_stream(self):
        """停止投屏流"""
        try:
            logger.info(f"停止 iOS 投屏: {self.udid}")
            self.streaming = False

            await self.write_message(json.dumps({
                "type": "stream_stopped"
            }))

        except Exception as e:
            logger.error(f"停止 iOS 投屏失败: {e}")

    def on_close(self):
        """WebSocket 连接关闭"""
        self.streaming = False


class IOSDeviceControlWebSocket(tornado.websocket.WebSocketHandler):
    """iOS 设备控制 WebSocket"""

    # 设备管理器实例（由 IOSProxyServer 设置）
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
            if not self.device_manager or  udid not in self.device_manager.devices:
                await self.write_message(json.dumps({
                    "type": "error",
                    "message": f"Device not found: {udid}"
                }))
                return

            device = self.device_manager.devices[udid]
            if not device.online or not device.init:
                raise Exception(f"设备 {udid} 未就绪")

            # 从设备管理器获取 WDA 客户端
            wda_client = device.wda_client
            if not wda_client or not wda_client.session_id:
                raise Exception(f"设备 {udid} WDA 客户端未初始化")

            self.wda_client = wda_client

            # 获取屏幕尺寸
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
        """处理长按请求 - iOS 通过 touchAndHold 实现"""
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
        """处理滑动请求 - 带节流"""
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
        """处理点亮/唤醒屏幕：调用 WDA unlock（若支持）"""
        try:
            if not self.wda_client:
                raise Exception("设备未连接")
            success = await self.wda_client.unlock()
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
                # 压缩 XML
                compressed = gzip.compress(xml.encode('utf-8'))
                encoded = base64.b64encode(compressed).decode('utf-8')

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
    """iOS 元素检查器 WebSocket - 独立通道，避免占用控制 WebSocket"""

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
                compressed = gzip.compress(xml.encode('utf-8'))
                encoded = base64.b64encode(compressed).decode('utf-8')

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
                compressed = gzip.compress(xml.encode('utf-8'))
                encoded = base64.b64encode(compressed).decode('utf-8')

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
        """创建 Tornado 应用 - 三个 WebSocket 接口"""
        # 将设备管理器传递给 WebSocket handler
        if self.device_manager:
            IOSScreenStreamWebSocket.device_manager = self.device_manager
            IOSDeviceControlWebSocket.device_manager = self.device_manager
            IOSElementInspectorWebSocket.device_manager = self.device_manager

        return tornado.web.Application([
            (r"/devices/([^/]+)/screen", IOSScreenStreamWebSocket),      # 投屏
            (r"/devices/([^/]+)/control", IOSDeviceControlWebSocket),    # 控制
            (r"/devices/([^/]+)/inspector", IOSElementInspectorWebSocket),  # 元素检查器
        ], debug=self.config['proxy']['debug'])

    def run(self):
        """启动服务器"""
        host = self.config['proxy'].get('host', '0.0.0.0')
        port = self.config['proxy']['port']
        logger.info(f"启动 iOS 代理服务: {host}:{port}")

        self.app.listen(port, address=host)
        logger.info("iOS 设备代理服务器已启动! 🚀")
