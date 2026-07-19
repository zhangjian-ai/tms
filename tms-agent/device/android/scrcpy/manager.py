from typing import Dict

from logzero import logger
from tornado.websocket import WebSocketHandler

from device.android.scrcpy.device import ScrcpyDevice


class ScrcpyManager:
    """scrcpy设备管理器 - 专门管理投屏功能"""

    def __init__(self):
        self.scrcpy_devices: Dict[str, ScrcpyDevice] = {}

    async def get_device_client(self, serial: str) -> ScrcpyDevice:
        """获取或创建设备客户端"""
        if serial not in self.scrcpy_devices:
            self.scrcpy_devices[serial] = ScrcpyDevice(serial)
        return self.scrcpy_devices[serial]

    def has_active_client(self, serial: str, exclude: WebSocketHandler = None) -> bool:
        """该设备是否已有存活的投屏客户端（用于判断是否已在其他页面投屏）"""
        device = self.scrcpy_devices.get(serial)
        if not device:
            return False
        return any(
            getattr(c, "ws_connection", None) is not None and c is not exclude
            for c in device.ws_client_list
        )

    async def prepare_device_stream(self, serial: str, ws_client: WebSocketHandler) -> bool:
        """启动设备投屏。

        同一设备同一时刻只允许一个投屏：若已存在存活的投屏客户端，则拒绝新的请求，
        由调用方向前端回传错误提示（不再清空并劫持已有投屏）。
        """
        try:
            scrcpy_device = await self.get_device_client(serial)

            # 剔除已断开的客户端与自身
            scrcpy_device.ws_client_list = [
                c for c in scrcpy_device.ws_client_list
                if getattr(c, "ws_connection", None) is not None and c is not ws_client
            ]

            if scrcpy_device.ws_client_list:
                logger.warning(f"设备 {serial} 已在其他页面投屏，拒绝新的投屏请求")
                return False

            scrcpy_device.ws_client_list.append(ws_client)

            async with scrcpy_device.async_lock:
                if scrcpy_device.video_data_transfer:
                    await scrcpy_device.stop()
                await scrcpy_device.prepare()
            return True
        except Exception as e:
            logger.error(f"启动设备投屏失败 {serial}: {e}")
            return False

    async def stop_device_stream(self, serial: str, ws_client: WebSocketHandler):
        """停止设备投屏"""
        try:
            if serial in self.scrcpy_devices:
                scrcpy_device = self.scrcpy_devices[serial]
                if ws_client in scrcpy_device.ws_client_list:
                    scrcpy_device.ws_client_list.remove(ws_client)

                if not scrcpy_device.ws_client_list:
                    await scrcpy_device.stop()
        except Exception as e:
            logger.error(f"停止设备投屏失败 {serial}: {e}")

    async def handle_binary_control(self, serial: str, data: bytes):
        """转发二进制控制数据到scrcpy control socket"""
        try:
            if serial in self.scrcpy_devices:
                device = self.scrcpy_devices[serial]
                await device.send_control(data)
            else:
                logger.warning(f"设备 {serial} 不存在，无法转发控制指令")
        except Exception as e:
            logger.error(f"转发控制指令失败 {serial}: {e}")

    async def cleanup_device(self, serial: str, device_offline: bool = False):
        """清理指定设备的scrcpy资源，device_offline=True 时跳过设备端进程清理"""
        try:
            if serial in self.scrcpy_devices:
                scrcpy_device = self.scrcpy_devices[serial]
                await scrcpy_device.stop(skip_device_cleanup=device_offline)
                del self.scrcpy_devices[serial]
                logger.info(f"设备 {serial} 的scrcpy资源已清理")
            elif not device_offline:
                temp_device = ScrcpyDevice(serial)
                await temp_device.cleanup_existing_scrcpy_processes()
                logger.info(f"设备 {serial} 的scrcpy进程清理完成")
        except Exception as e:
            logger.error(f"清理设备 {serial} 的scrcpy资源时出错: {e}")


scrcpy_manager = ScrcpyManager()
