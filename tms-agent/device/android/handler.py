import re
import io
import gzip
import json
import base64
import asyncio
import subprocess

import tornado.web
import tornado.ioloop
from typing import Any
import tornado.websocket
from logzero import logger
from tornado import httputil
from datetime import datetime

import uiautomator2 as u2
import xml.etree.ElementTree as ET

from device.android.scrcpy import scrcpy_manager
from utils.variables import settings


# 通用XML获取函数
def get_device_hierarchy_xml(device, compress=True):
    """获取设备UI层次结构XML的通用函数"""
    if not device:
        raise Exception("设备未连接")

    hierarchy = device.dump_hierarchy()

    if compress:
        # 压缩并编码
        compressed = gzip.compress(hierarchy.encode('utf-8'))
        encoded = base64.b64encode(compressed).decode('utf-8')
        return {
            "xml": encoded,
            "compressed": True,
            "encoding": "base64"
        }
    else:
        return {
            "xml": hierarchy,
            "compressed": False,
            "encoding": "utf-8"
        }


class ScrcpyWebSocket(tornado.websocket.WebSocketHandler):
    """Scrcpy专用WebSocket - 专门负责投屏功能"""

    def __init__(self, application: tornado.web.Application, request: httputil.HTTPServerRequest, **kwargs: Any):
        super().__init__(application, request, **kwargs)
        self.serial = None
        self.streaming = False

    def check_origin(self, origin):
        """允许跨域"""
        return True

    def open(self, serial):
        """WebSocket连接建立"""
        self.serial = serial
        self.streaming = False

        # 发送连接确认
        if self.ws_connection and not self.ws_connection.is_closing():
            self.write_message(json.dumps({
                "type": "connected",
                "serial": serial,
                "service": "scrcpy",
                "timestamp": datetime.now().isoformat()
            }))

    def on_message(self, message):
        """处理WebSocket消息 - 仅处理scrcpy投屏相关"""
        try:
            # 检查是否为二进制数据（scrcpy控制指令）
            if isinstance(message, bytes):
                self._handle_scrcpy_binary_control(message)
                return

            # 处理JSON消息
            data = json.loads(message)
            msg_type = data.get("type")

            if msg_type == "ping":
                if self.ws_connection and not self.ws_connection.is_closing():
                    self.write_message(json.dumps({"type": "pong"}))

            elif msg_type == "start_stream":
                tornado.ioloop.IOLoop.current().add_callback(self._start_scrcpy_stream)

            elif msg_type == "stop_stream":
                tornado.ioloop.IOLoop.current().add_callback(self._stop_scrcpy_stream)
            else:
                logger.warning(f"Scrcpy WebSocket不支持的消息类型: {msg_type}")
                if self.ws_connection and not self.ws_connection.is_closing():
                    self.write_message(json.dumps({
                        "type": "error",
                        "message": f"Scrcpy WebSocket only supports streaming and precise control"
                    }))

        except Exception as e:
            logger.error(f"Scrcpy WebSocket消息处理失败: {e}")
            if self.ws_connection and not self.ws_connection.is_closing():
                self.write_message(json.dumps({
                    "type": "error",
                    "message": str(e)
                }))

    def _handle_scrcpy_binary_control(self, message):
        """处理scrcpy二进制控制指令"""
        try:
            tornado.ioloop.IOLoop.current().add_callback(
                scrcpy_manager.handle_binary_control,
                self.serial,
                message
            )
        except Exception as e:
            logger.error(f"处理scrcpy二进制消息失败: {e}")

    async def _start_scrcpy_stream(self):
        """启动scrcpy投屏流"""
        try:
            if self.streaming:
                if self.ws_connection and not self.ws_connection.is_closing():
                    await self.write_message(json.dumps({
                        "type": "error",
                        "message": "Scrcpy stream already running"
                    }))
                return

            # 使用scrcpy管理器启动流
            success = await scrcpy_manager.prepare_device_stream(self.serial, self)

            if success:
                self.streaming = True
                # 获取设备分辨率信息
                scrcpy_device = await scrcpy_manager.get_device_client(self.serial)
                resolution_data = None
                if scrcpy_device and scrcpy_device.resolution:
                    resolution_data = {
                        "width": scrcpy_device.resolution[0],
                        "height": scrcpy_device.resolution[1]
                    }

                # 发送流开始通知，先告知分辨率
                message_data = {
                    "type": "stream_started"
                }
                if resolution_data:
                    message_data["resolution"] = resolution_data

                await self.write_message(json.dumps(message_data))
                await scrcpy_device.start()

                logger.info(f"设备{self.serial}投屏已启动")
            else:
                await self.write_message(json.dumps({
                    "type": "error",
                    "message": "Failed to start scrcpy stream"
                }))

        except Exception as e:
            logger.error(f"启动scrcpy失败: {e}")
            await self.write_message(json.dumps({
                "type": "error",
                "message": f"Failed to start scrcpy: {e}"
            }))

    async def _stop_scrcpy_stream(self):
        """停止scrcpy投屏流"""
        try:
            self.streaming = False

            # 使用scrcpy管理器停止流
            await scrcpy_manager.stop_device_stream(self.serial, self)

            await self.write_message(json.dumps({
                "type": "stream_stopped"
            }))

        except Exception as e:
            logger.error(f"停止scrcpy失败: {e}")

    def on_close(self):
        """WebSocket连接关闭"""

        if self.streaming and self.serial:
            self.streaming = False
            tornado.ioloop.IOLoop.current().add_callback(
                scrcpy_manager.cleanup_device,
                self.serial,
                True
            )


class DeviceControlWebSocket(tornado.websocket.WebSocketHandler):
    """设备控制WebSocket"""

    def __init__(self, application: tornado.web.Application, request: httputil.HTTPServerRequest, **kwargs: Any):
        super().__init__(application, request, **kwargs)
        self.serial = None
        self.device: u2.Device = None
        self.device_resolution = None  # 缓存设备分辨率 (width, height)

    def check_origin(self, origin):
        """允许跨域"""
        return True

    def open(self, serial):
        """WebSocket连接建立"""
        self.serial = serial

        try:
            # 通过ADB客户端获取设备
            self.device = u2.connect(self.serial)

            # 获取并缓存设备分辨率
            try:
                w, h = self.device.window_size()
                self.device_resolution = (w, h)
            except Exception as e:
                logger.warning(f"获取设备分辨率失败: {e}")

            # 发送连接确认
            if self.ws_connection and not self.ws_connection.is_closing():
                self.write_message(json.dumps({
                    "type": "connected",
                    "serial": serial,
                    "service": "device_control",
                    "device_resolution": self.device_resolution,
                    "timestamp": datetime.now().isoformat()
                }))

        except Exception as e:
            logger.error(f"连接设备失败: {e}")
            if self.ws_connection and not self.ws_connection.is_closing():
                self.write_message(json.dumps({
                    "type": "error",
                    "message": f"Failed to connect to device: {e}"
                }))

    def on_message(self, message):
        """处理WebSocket消息 - 设备控制相关"""
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

            elif msg_type == "input_text":
                tornado.ioloop.IOLoop.current().add_callback(self._handle_input_text, data)

            elif msg_type == "key_event":
                tornado.ioloop.IOLoop.current().add_callback(self._handle_key_event, data)

            elif msg_type == "dump_hierarchy":
                tornado.ioloop.IOLoop.current().add_callback(self._handle_dump_hierarchy, data)

            elif msg_type == "get_xml_only":
                tornado.ioloop.IOLoop.current().add_callback(self._handle_get_xml_only, data)

            elif msg_type == "device_info":
                tornado.ioloop.IOLoop.current().add_callback(self._handle_device_info, data)

            else:
                logger.warning(f"设备控制 WebSocket不支持的消息类型: {msg_type}")
                if self.ws_connection and not self.ws_connection.is_closing():
                    self.write_message(json.dumps({
                        "type": "error",
                        "message": f"Unsupported message type: {msg_type}"
                    }))

        except Exception as e:
            logger.error(f"设备控制 WebSocket消息处理失败: {e}")
            if self.ws_connection and not self.ws_connection.is_closing():
                self.write_message(json.dumps({
                    "type": "error",
                    "message": str(e)
                }))

    async def _handle_screenshot(self, data):
        """处理截屏请求"""
        try:
            if not self.device:
                raise Exception("设备未连接")

            # PIL Image对象
            screenshot_image = self.device.screenshot()

            # 将PIL Image转换为字节流
            buffer = io.BytesIO()
            screenshot_image.save(buffer, format='PNG')
            screenshot_bytes = buffer.getvalue()

            # 转换为base64
            img_base64 = base64.b64encode(screenshot_bytes).decode('utf-8')

            await self.write_message(json.dumps({
                "type": "screenshot_result",
                "success": True,
                "data": {
                    "image": img_base64,
                    "format": "png",
                    "timestamp": datetime.now().isoformat()
                }
            }))

        except Exception as e:
            logger.error(f"截屏失败: {e}")
            await self.write_message(json.dumps({
                "type": "screenshot_result",
                "success": False,
                "error": str(e)
            }))

    async def _handle_click(self, data):
        """处理点击请求"""
        try:
            if not self.device:
                raise Exception("设备未连接")

            x = data.get("x")
            y = data.get("y")

            if x is None or y is None:
                raise Exception("缺少坐标参数")

            device_x = int(x)
            device_y = int(y)

            self.device.click(device_x, device_y)

            await self.write_message(json.dumps({
                "type": "click_result",
                "success": True,
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
            if not self.device:
                raise Exception("设备未连接")

            x = data.get("x")
            y = data.get("y")

            if x is None or y is None:
                raise Exception("缺少坐标参数")

            device_x = int(x)
            device_y = int(y)

            self.device.long_click(device_x, device_y, 1.5)

            await self.write_message(json.dumps({
                "type": "long_click_result",
                "success": True,
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
            if not self.device:
                raise Exception("设备未连接")

            start_x = data.get("start_x")
            start_y = data.get("start_y")
            end_x = data.get("end_x")
            end_y = data.get("end_y")
            duration = data.get("duration", 0.2)

            if any(v is None for v in [start_x, start_y, end_x, end_y]):
                raise Exception("缺少滑动坐标参数")

            device_start_x = int(start_x)
            device_start_y = int(start_y)
            device_end_x = int(end_x)
            device_end_y = int(end_y)

            self.device.swipe(device_start_x, device_start_y, device_end_x, device_end_y, duration)

            await self.write_message(json.dumps({
                "type": "swipe_result",
                "success": True,
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

    async def _handle_input_text(self, data):
        """处理文本输入请求"""
        try:
            if not self.device:
                raise Exception("设备未连接")

            text = data.get("text")
            if not text:
                raise Exception("缺少文本参数")

            self.device.send_keys(text)

            await self.write_message(json.dumps({
                "type": "input_text_result",
                "success": True,
                "data": {"text": text}
            }))

        except Exception as e:
            logger.error(f"文本输入失败: {e}")
            await self.write_message(json.dumps({
                "type": "input_text_result",
                "success": False,
                "error": str(e)
            }))

    async def _handle_key_event(self, data):
        """处理按键事件请求"""
        try:
            if not self.device:
                raise Exception("设备未连接")

            key = data.get("key")
            if not key:
                raise Exception("缺少按键参数")

            # 映射按键名称到Android keycode
            key_mapping = {
                'home': 'KEYCODE_HOME',
                'back': 'KEYCODE_BACK',
                'menu': 'KEYCODE_MENU',
                'power': 'KEYCODE_POWER',
                'volume_up': 'KEYCODE_VOLUME_UP',
                'volume_down': 'KEYCODE_VOLUME_DOWN'
            }

            keycode = key_mapping.get(key.lower(), key)
            self.device.keyevent(keycode)

            await self.write_message(json.dumps({
                "type": "key_event_result",
                "success": True,
                "data": {"key": key}
            }))

        except Exception as e:
            logger.error(f"按键事件失败: {e}")
            await self.write_message(json.dumps({
                "type": "key_event_result",
                "success": False,
                "error": str(e)
            }))

    async def _handle_dump_hierarchy(self, data):
        """处理UI层次结构导出请求"""
        try:
            xml_data = get_device_hierarchy_xml(self.device, compress=True)

            await self.write_message(json.dumps({
                "type": "dump_hierarchy_result",
                "success": True,
                "data": {
                    "hierarchy": xml_data["xml"],
                    "compressed": xml_data["compressed"],
                    "encoding": xml_data["encoding"],
                    "timestamp": datetime.now().isoformat()
                }
            }))

        except Exception as e:
            logger.error(f"UI层次结构导出失败: {e}")
            await self.write_message(json.dumps({
                "type": "dump_hierarchy_result",
                "success": False,
                "error": str(e)
            }))

    async def _handle_get_xml_only(self, data):
        """仅获取XML内容（用于变化检测）"""
        try:
            xml_data = get_device_hierarchy_xml(self.device, compress=True)

            await self.write_message(json.dumps({
                "type": "xml_only",
                "success": True,
                "data": {
                    "xml": xml_data["xml"],
                    "compressed": xml_data["compressed"],
                    "encoding": xml_data["encoding"],
                    "timestamp": datetime.now().isoformat()
                }
            }))

        except Exception as e:
            logger.error(f"获取XML内容失败: {e}")
            await self.write_message(json.dumps({
                "type": "xml_only",
                "success": False,
                "error": str(e)
            }))

    async def _handle_device_info(self, data):
        """处理设备信息请求"""
        try:
            if not self.device:
                raise Exception("设备未连接")

            info = self.device.device_info
            info["display"] = self.device.window_size()

            await self.write_message(json.dumps({
                "type": "device_info_result",
                "success": True,
                "data": {
                    "info": info,
                    "timestamp": datetime.now().isoformat()
                }
            }))

        except Exception as e:
            logger.error(f"获取设备信息失败: {e}")
            await self.write_message(json.dumps({
                "type": "device_info_result",
                "success": False,
                "error": str(e)
            }))

    def on_close(self):
        """WebSocket连接关闭"""
        self.device = None


class ElementInspectorWebSocket(tornado.websocket.WebSocketHandler):
    """元素检查器WebSocket - 专门负责UI元素查找和操作"""

    def __init__(self, application: tornado.web.Application, request: httputil.HTTPServerRequest, **kwargs: Any):
        super().__init__(application, request, **kwargs)
        self.serial = None
        self.device: u2.Device = None
        self.device_resolution = None

    def check_origin(self, origin):
        """允许跨域"""
        return True

    def open(self, serial):
        """WebSocket连接建立"""
        self.serial = serial

        try:
            # 通过ADB客户端获取设备
            self.device = u2.connect(self.serial)

            # 获取并缓存设备分辨率
            try:
                w, h = self.device.window_size()
                self.device_resolution = (w, h)
            except Exception as e:
                logger.warning(f"获取设备分辨率失败: {e}")

            # 发送连接确认
            if self.ws_connection and not self.ws_connection.is_closing():
                self.write_message(json.dumps({
                    "type": "connected",
                    "serial": serial,
                    "service": "element_inspector",
                    "device_resolution": self.device_resolution,
                    "timestamp": datetime.now().isoformat()
                }))

        except Exception as e:
            logger.error(f"元素检查器连接设备失败: {e}")
            if self.ws_connection and not self.ws_connection.is_closing():
                self.write_message(json.dumps({
                    "type": "error",
                    "message": f"Failed to connect to device: {e}"
                }))

    def on_message(self, message):
        """处理WebSocket消息 - 元素检查相关"""
        try:
            data = json.loads(message)
            msg_type = data.get("type")

            if msg_type == "ping":
                if self.ws_connection and not self.ws_connection.is_closing():
                    self.write_message(json.dumps({"type": "pong"}))

            elif msg_type == "get_ui_hierarchy":
                tornado.ioloop.IOLoop.current().add_callback(self._handle_get_ui_hierarchy, data)

            elif msg_type == "highlight_element":
                tornado.ioloop.IOLoop.current().add_callback(self._handle_highlight_element, data)

            elif msg_type == "element_action":
                tornado.ioloop.IOLoop.current().add_callback(self._handle_element_action, data)

            elif msg_type == "get_element_info":
                tornado.ioloop.IOLoop.current().add_callback(self._handle_get_element_info, data)

            else:
                logger.warning(f"元素检查器 WebSocket不支持的消息类型: {msg_type}")
                if self.ws_connection and not self.ws_connection.is_closing():
                    self.write_message(json.dumps({
                        "type": "error",
                        "message": f"Unsupported message type: {msg_type}"
                    }))

        except Exception as e:
            logger.error(f"元素检查器 WebSocket消息处理失败: {e}")
            if self.ws_connection and not self.ws_connection.is_closing():
                self.write_message(json.dumps({
                    "type": "error",
                    "message": str(e)
                }))

    async def _handle_get_ui_hierarchy(self, data):
        """获取并返回UI层次结构"""
        try:
            if not self.device:
                raise Exception("设备未连接")

            # 使用通用函数获取UI层次结构XML（不压缩，因为需要解析）
            xml_data = get_device_hierarchy_xml(self.device, compress=False)
            hierarchy_xml = xml_data["xml"]

            # 解析XML为结构化数据
            root = ET.fromstring(hierarchy_xml)
            ui_tree = self._parse_ui_tree(root)

            await self.write_message(json.dumps({
                "type": "ui_hierarchy",
                "success": True,
                "data": {
                    "tree": ui_tree,
                    "xml": hierarchy_xml,
                    "device_resolution": self.device_resolution,
                    "timestamp": datetime.now().isoformat()
                }
            }))

        except Exception as e:
            logger.error(f"获取UI层次结构失败: {e}")
            await self.write_message(json.dumps({
                "type": "ui_hierarchy",
                "success": False,
                "error": str(e)
            }))

    def _parse_ui_tree(self, element, index=0):
        """递归解析XML元素为树结构"""
        bounds = element.get("bounds", "")
        bounds_match = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', bounds)

        if bounds_match:
            x1, y1, x2, y2 = map(int, bounds_match.groups())
            center_x = (x1 + x2) // 2
            center_y = (y1 + y2) // 2
            width = x2 - x1
            height = y2 - y1
        else:
            x1 = y1 = x2 = y2 = center_x = center_y = width = height = 0

        node = {
            "index": index,
            "class": element.get("class", ""),
            "text": element.get("text", ""),
            "resource_id": element.get("resource-id", ""),
            "content_desc": element.get("content-desc", ""),
            "bounds": bounds,
            "coordinates": {
                "x1": x1, "y1": y1, "x2": x2, "y2": y2,
                "center_x": center_x, "center_y": center_y,
                "width": width, "height": height
            },
            "clickable": element.get("clickable", "false") == "true",
            "enabled": element.get("enabled", "false") == "true",
            "focused": element.get("focused", "false") == "true",
            "scrollable": element.get("scrollable", "false") == "true",
            "children": []
        }

        # 递归处理子元素
        for i, child in enumerate(element):
            child_node = self._parse_ui_tree(child, len(node["children"]))
            node["children"].append(child_node)

        return node

    def _extract_element_info(self, element):
        """提取元素信息"""
        bounds = element.get("bounds", "")
        bounds_match = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', bounds)

        if bounds_match:
            x1, y1, x2, y2 = map(int, bounds_match.groups())
            center_x = (x1 + x2) // 2
            center_y = (y1 + y2) // 2
            width = x2 - x1
            height = y2 - y1
        else:
            x1 = y1 = x2 = y2 = center_x = center_y = width = height = 0

        return {
            "class": element.get("class", ""),
            "text": element.get("text", ""),
            "resource_id": element.get("resource-id", ""),
            "content_desc": element.get("content-desc", ""),
            "package": element.get("package", ""),
            "bounds": bounds,
            "coordinates": {
                "x1": x1, "y1": y1, "x2": x2, "y2": y2,
                "center_x": center_x, "center_y": center_y,
                "width": width, "height": height
            },
            "clickable": element.get("clickable", "false") == "true",
            "enabled": element.get("enabled", "false") == "true",
            "focused": element.get("focused", "false") == "true",
            "scrollable": element.get("scrollable", "false") == "true",
            "checkable": element.get("checkable", "false") == "true",
            "checked": element.get("checked", "false") == "true",
            "selected": element.get("selected", "false") == "true",
            "password": element.get("password", "false") == "true"
        }

    async def _handle_highlight_element(self, data):
        """高亮显示指定元素"""
        try:
            element_info = data.get("element")
            if not element_info:
                raise Exception("缺少元素信息")

            bounds = element_info.get("bounds", "")
            if not bounds:
                raise Exception("元素缺少bounds信息")

            await self.write_message(json.dumps({
                "type": "highlight_element",
                "success": True,
                "data": {
                    "element": element_info,
                    "bounds": bounds,
                    "timestamp": datetime.now().isoformat()
                }
            }))

        except Exception as e:
            logger.error(f"高亮元素失败: {e}")
            await self.write_message(json.dumps({
                "type": "highlight_element",
                "success": False,
                "error": str(e)
            }))

    async def _handle_element_action(self, data):
        """执行元素操作"""
        try:
            if not self.device:
                raise Exception("设备未连接")

            element_info = data.get("element")
            action = data.get("action")

            if not element_info or not action:
                raise Exception("缺少元素信息或操作类型")

            coordinates = element_info.get("coordinates", {})
            center_x = coordinates.get("center_x")
            center_y = coordinates.get("center_y")

            if center_x is None or center_y is None:
                raise Exception("元素坐标信息不完整")

            result = None
            if action == "click":
                self.device.click(center_x, center_y)
                result = f"点击元素 ({center_x}, {center_y})"

            elif action == "long_click":
                self.device.long_click(center_x, center_y, 1.5)
                result = f"长按元素 ({center_x}, {center_y})"

            elif action == "input_text":
                text = data.get("text", "")
                if not text:
                    raise Exception("缺少输入文本")
                self.device.click(center_x, center_y)
                await asyncio.sleep(0.1)
                self.device.send_keys(text)
                result = f"在元素中输入: {text}"

            elif action == "clear_text":
                self.device.click(center_x, center_y)
                await asyncio.sleep(0.1)
                self.device.keyevent("KEYCODE_CTRL_A")
                self.device.keyevent("KEYCODE_DEL")
                result = "清空元素文本"

            else:
                raise Exception(f"不支持的操作: {action}")

            await self.write_message(json.dumps({
                "type": "element_action_result",
                "success": True,
                "data": {
                    "action": action,
                    "element": element_info,
                    "result": result,
                    "timestamp": datetime.now().isoformat()
                }
            }))

        except Exception as e:
            logger.error(f"元素操作失败: {e}")
            await self.write_message(json.dumps({
                "type": "element_action_result",
                "success": False,
                "error": str(e)
            }))

    async def _handle_get_element_info(self, data):
        """获取元素详细信息"""
        try:
            selector = data.get("selector", {})
            if not selector:
                raise Exception("缺少元素选择器")

            # 实时获取UI层次结构（确保数据最新）
            xml_data = get_device_hierarchy_xml(self.device, compress=False)
            hierarchy_xml = xml_data["xml"]

            root = ET.fromstring(hierarchy_xml)
            element = self._find_element_by_selector(root, selector)

            if element is not None:
                element_info = self._extract_element_info(element)
                await self.write_message(json.dumps({
                    "type": "element_info",
                    "success": True,
                    "data": {
                        "element": element_info,
                        "timestamp": datetime.now().isoformat()
                    }
                }))
            else:
                await self.write_message(json.dumps({
                    "type": "element_info",
                    "success": False,
                    "error": "未找到匹配的元素"
                }))

        except Exception as e:
            logger.error(f"获取元素信息失败: {e}")
            await self.write_message(json.dumps({
                "type": "element_info",
                "success": False,
                "error": str(e)
            }))

    def _find_element_by_selector(self, root, selector):
        """根据选择器查找元素"""
        xpath_conditions = []
        if selector.get("text"):
            xpath_conditions.append(f"@text='{selector['text']}'")
        if selector.get("resource_id"):
            xpath_conditions.append(f"@resource-id='{selector['resource_id']}'")
        if selector.get("class_name"):
            xpath_conditions.append(f"@class='{selector['class_name']}'")
        if selector.get("content_desc"):
            xpath_conditions.append(f"@content-desc='{selector['content_desc']}'")
        if selector.get("bounds"):
            xpath_conditions.append(f"@bounds='{selector['bounds']}'")

        if not xpath_conditions:
            return None

        xpath = f".//*[{' and '.join(xpath_conditions)}]"
        elements = root.findall(xpath)
        return elements[0] if elements else None

    def on_close(self):
        """WebSocket连接关闭"""
        self.device = None


class AndroidProxyServer:
    """Android代理服务器"""

    def __init__(self):
        self.config = settings["android"]
        # 初始化本地环境
        self.init_env()
        # 启动代理服务
        self.app = self.make_app()

    @staticmethod
    def _exec(command, host="", port=""):
        cmd = "adb"
        if host and host != "127.0.0.1":
            cmd += f" -H {host}"
        if port and port != "5037":
            cmd += f" -P {port}"
        cmd += f" {command}"
        logger.info(cmd)
        proc = subprocess.Popen(
            cmd,
            shell=True,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE
        )
        return proc.stdout.read().decode()

    def init_env(self):
        # 停止本机adb-server
        self._exec(command="kill-server")
        self._exec(command="kill-server", port=self.config["adb"]["port"])
        # 启动指定端口的adb-server
        self._exec(command="-a start-server", port=self.config["adb"]["port"])

    def make_app(self):
        """创建Tornado应用 - 三个WebSocket接口"""
        return tornado.web.Application([
            (r"/devices/([^/]+)/scrcpy", ScrcpyWebSocket),  # scrcpy投屏WebSocket
            (r"/devices/([^/]+)/control", DeviceControlWebSocket),  # 设备控制WebSocket
            (r"/devices/([^/]+)/inspector", ElementInspectorWebSocket)  # 元素检查器WebSocket
        ], debug=self.config['proxy']['debug'])

    def run(self):
        """启动服务器"""
        host = self.config['proxy'].get('host', '0.0.0.0')
        port = self.config['proxy']['port']
        logger.info(f"启动Android代理服务: {host}: {port}")

        self.app.listen(port, address=host)
        logger.info("Android设备代理服务器已启动! 🚀")
