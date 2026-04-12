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

    async def prepare_device_stream(self, serial: str, ws_client: WebSocketHandler) -> bool:
        """启动设备投屏"""
        try:
            scrcpy_device = await self.get_device_client(serial)
            scrcpy_device.ws_client_list.clear()
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
