import re
import io
import gzip
import json
import base64
import asyncio
import subprocess

import tornado.web
import tornado.ioloop
import tornado.httpserver
from typing import Any
import tornado.websocket
from logzero import logger
from tornado import httputil
from datetime import datetime

import uiautomator2 as u2
import xml.etree.ElementTree as ET

from device.android.scrcpy import scrcpy_manager
from device.android.tools.adb import get_adb_bin
from device.android.apps import AndroidAppsHandler, AndroidAppUninstallHandler, AndroidAppInstallHandler
from utils.variables import settings


def get_device_hierarchy_xml(device, compress=True):
    """获取设备UI层次结构XML的通用函数"""
    if not device:
        raise Exception("设备未连接")

    hierarchy = device.dump_hierarchy()

    if compress:
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


def _device_online(device_manager, serial) -> bool:
    """校验设备是否在线。"""
    if not device_manager or serial not in device_manager.devices:
        return False
    return device_manager.devices[serial].online


def _device_occupied(device_manager, serial) -> bool:
    """设备是否已被占用。"""
    if not device_manager or serial not in device_manager.devices:
        return False
    return device_manager.devices[serial].occupied


def _device_cast_allowed(device_manager, serial) -> bool:
    """是否允许投屏：需已占用且 cast=True。"""
    if not device_manager or serial not in device_manager.devices:
        return False
    d = device_manager.devices[serial]
    return d.occupied and d.cast


class ScrcpyWebSocket(tornado.websocket.WebSocketHandler):
    """Scrcpy专用WebSocket - 专门负责投屏功能"""

    device_manager = None  # 由 AndroidProxyServer 注入

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

        # 设备在线校验
        if not _device_online(self.device_manager, serial):
            if self.ws_connection and not self.ws_connection.is_closing():
                self.write_message(json.dumps({
                    "type": "error",
                    "message": f"设备不在线或未就绪: {serial}"
                }))
            self.close()
            return

        # 投屏校验
        if not _device_cast_allowed(self.device_manager, serial):
            if self.ws_connection and not self.ws_connection.is_closing():
                self.write_message(json.dumps({
                    "type": "error",
                    "message": "设备未开启投屏"
                }))
            self.close()
            return

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
            if isinstance(message, bytes):
                self._handle_scrcpy_binary_control(message)
                return

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

            # 同一设备只允许一个投屏
            if scrcpy_manager.has_active_client(self.serial, exclude=self):
                if self.ws_connection and not self.ws_connection.is_closing():
                    await self.write_message(json.dumps({
                        "type": "error",
                        "message": "设备已在其他页面投屏"
                    }))
                return

            success = await scrcpy_manager.prepare_device_stream(self.serial, self)

            if success:
                self.streaming = True
                scrcpy_device = await scrcpy_manager.get_device_client(self.serial)
                resolution_data = None
                if scrcpy_device and scrcpy_device.resolution:
                    resolution_data = {
                        "width": scrcpy_device.resolution[0],
                        "height": scrcpy_device.resolution[1]
                    }

                # 发送流开始通知，先告知分辨率与帧率（作为编码参数的单一来源）
                message_data = {
                    "type": "stream_started",
                    "fps": scrcpy_device.max_fps
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
            # 引用计数式停流：本设备再无投屏客户端时才真正停止
            tornado.ioloop.IOLoop.current().add_callback(
                scrcpy_manager.stop_device_stream,
                self.serial,
                self
            )


class DeviceControlWebSocket(tornado.websocket.WebSocketHandler):
    """设备控制WebSocket"""

    device_manager = None  # 由 AndroidProxyServer 注入

    def __init__(self, application: tornado.web.Application, request: httputil.HTTPServerRequest, **kwargs: Any):
        super().__init__(application, request, **kwargs)
        self.serial = None
        self.device: u2.Device = None
        self.device_resolution = None  # 缓存设备分辨率 (width, height)

    def check_origin(self, origin):
        """允许跨域"""
        return True

    async def open(self, serial):
        """WebSocket连接建立"""
        self.serial = serial

        # 设备在线校验
        if not _device_online(self.device_manager, serial):
            if self.ws_connection and not self.ws_connection.is_closing():
                self.write_message(json.dumps({
                    "type": "error",
                    "message": f"设备不在线或未就绪: {serial}"
                }))
            self.close()
            return

        # 占用校验
        if not _device_occupied(self.device_manager, serial):
            if self.ws_connection and not self.ws_connection.is_closing():
                self.write_message(json.dumps({
                    "type": "error",
                    "message": "设备未被占用"
                }))
            self.close()
            return

        try:
            self.device = await asyncio.to_thread(u2.connect, self.serial)

            try:
                w, h = await asyncio.to_thread(self.device.window_size)
                self.device_resolution = (w, h)
            except Exception as e:
                logger.warning(f"获取设备分辨率失败: {e}")

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

            elif msg_type == "dump_hierarchy":
                tornado.ioloop.IOLoop.current().add_callback(self._handle_dump_hierarchy, data)

            elif msg_type == "get_xml_only":
                tornado.ioloop.IOLoop.current().add_callback(self._handle_get_xml_only, data)

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
            screenshot_image = await asyncio.to_thread(self.device.screenshot)

            buffer = io.BytesIO()
            await asyncio.to_thread(screenshot_image.save, buffer, format='PNG')
            screenshot_bytes = buffer.getvalue()

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

    async def _handle_dump_hierarchy(self, data):
        """处理UI层次结构导出请求"""
        try:
            xml_data = await asyncio.to_thread(get_device_hierarchy_xml, self.device, compress=True)

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
            xml_data = await asyncio.to_thread(get_device_hierarchy_xml, self.device, compress=True)

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

    def on_close(self):
        """WebSocket连接关闭"""
        self.device = None


class ElementInspectorWebSocket(tornado.websocket.WebSocketHandler):
    """元素检查器WebSocket - 专门负责UI元素查找和操作"""

    device_manager = None  # 由 AndroidProxyServer 注入

    def __init__(self, application: tornado.web.Application, request: httputil.HTTPServerRequest, **kwargs: Any):
        super().__init__(application, request, **kwargs)
        self.serial = None
        self.device: u2.Device = None
        self.device_resolution = None

    def check_origin(self, origin):
        """允许跨域"""
        return True

    async def open(self, serial):
        """WebSocket连接建立"""
        self.serial = serial

        # 设备在线校验
        if not _device_online(self.device_manager, serial):
            if self.ws_connection and not self.ws_connection.is_closing():
                self.write_message(json.dumps({
                    "type": "error",
                    "message": f"设备不在线或未就绪: {serial}"
                }))
            self.close()
            return

        # 占用校验
        if not _device_occupied(self.device_manager, serial):
            if self.ws_connection and not self.ws_connection.is_closing():
                self.write_message(json.dumps({
                    "type": "error",
                    "message": "设备未被占用"
                }))
            self.close()
            return

        try:
            self.device = await asyncio.to_thread(u2.connect, self.serial)

            try:
                w, h = await asyncio.to_thread(self.device.window_size)
                self.device_resolution = (w, h)
            except Exception as e:
                logger.warning(f"获取设备分辨率失败: {e}")

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
            xml_data = await asyncio.to_thread(get_device_hierarchy_xml, self.device, compress=False)
            hierarchy_xml = xml_data["xml"]

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
                await asyncio.to_thread(self.device.click, center_x, center_y)
                result = f"点击元素 ({center_x}, {center_y})"

            elif action == "long_click":
                await asyncio.to_thread(self.device.long_click, center_x, center_y, 1.5)
                result = f"长按元素 ({center_x}, {center_y})"

            elif action == "input_text":
                text = data.get("text", "")
                if not text:
                    raise Exception("缺少输入文本")
                await asyncio.to_thread(self.device.click, center_x, center_y)
                await asyncio.sleep(0.1)
                await asyncio.to_thread(self.device.send_keys, text)
                result = f"在元素中输入: {text}"

            elif action == "clear_text":
                await asyncio.to_thread(self.device.click, center_x, center_y)
                await asyncio.sleep(0.1)
                await asyncio.to_thread(self.device.keyevent, "KEYCODE_CTRL_A")
                await asyncio.to_thread(self.device.keyevent, "KEYCODE_DEL")
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
            xml_data = await asyncio.to_thread(get_device_hierarchy_xml, self.device, compress=False)
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

    def __init__(self, device_manager=None):
        self.config = settings["android"]
        self.device_manager = device_manager
        # 初始化本地环境
        self.init_env()
        # 启动代理服务
        self.app = self.make_app()

    @staticmethod
    def _exec(args: list):
        """执行一条 adb 命令（统一二进制，不用 shell）"""
        cmd = [get_adb_bin()] + args
        proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        return proc.stdout.read().decode()

    def init_env(self):
        port = str(self.config["adb"]["port"])
        # 停止残留 server
        self._exec(["kill-server"])
        self._exec(["-P", port, "kill-server"])
        # -a 让 server 监听所有网卡
        self._exec(["-a", "-P", port, "start-server"])

    def make_app(self):
        """创建Tornado应用 - WebSocket 投屏/控制/检查器 + HTTP 应用管理"""
        if self.device_manager:
            ScrcpyWebSocket.device_manager = self.device_manager
            DeviceControlWebSocket.device_manager = self.device_manager
            ElementInspectorWebSocket.device_manager = self.device_manager
            AndroidAppsHandler.device_manager = self.device_manager
            AndroidAppUninstallHandler.device_manager = self.device_manager
            AndroidAppInstallHandler.device_manager = self.device_manager

        return tornado.web.Application([
            (r"/devices/([^/]+)/scrcpy", ScrcpyWebSocket),  # scrcpy投屏WebSocket
            (r"/devices/([^/]+)/control", DeviceControlWebSocket),  # 设备控制WebSocket
            (r"/devices/([^/]+)/inspector", ElementInspectorWebSocket),  # 元素检查器WebSocket
            (r"/devices/([^/]+)/apps", AndroidAppsHandler),  # 应用列表
            (r"/devices/([^/]+)/apps/uninstall", AndroidAppUninstallHandler),  # 卸载应用
            (r"/devices/([^/]+)/apps/install", AndroidAppInstallHandler),  # 安装应用
        ], debug=self.config['proxy']['debug'])

    def run(self):
        """启动服务器"""
        host = self.config['proxy'].get('host', '0.0.0.0')
        port = self.config['proxy']['port']
        logger.info(f"启动Android代理服务: {host}: {port}")

        # 显式 HTTPServer 以放开安装包(APK)上传体积上限
        server = tornado.httpserver.HTTPServer(self.app, max_body_size=2 * 1024 * 1024 * 1024)
        server.listen(port, address=host)
        logger.info("Android设备代理服务器已启动! 🚀")
