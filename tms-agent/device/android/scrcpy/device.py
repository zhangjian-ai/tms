import struct
import zipfile
import asyncio
import adbutils
import subprocess

from pathlib import Path
from typing import List

from logzero import logger
from bitstring import BitStream
from h26x_extractor.nalutypes import SPS
from tornado.websocket import WebSocketHandler

from device.android.scrcpy.adb import AdbClient
from device.android.tools.adb import get_adb_config

STATIC_DIR = Path(__file__).parent.parent / "static"


class ScrcpyDevice:
    """scrcpy设备客户端 - 专门管理投屏功能"""

    def __init__(self, serial: str, max_size: int = 1080, bit_rate: int = 1280000, max_fps: int = 25,
                 connect_timeout: int = 10):

        self.serial = serial
        self.max_size = max_size
        self.bit_rate = bit_rate
        self.max_fps = max_fps
        self.connect_timeout = connect_timeout
        self.shell_socket = None
        self.video_socket = None
        self.control_socket = None
        self.video_data_transfer = None

        self.name = None
        self.resolution = None

        self.async_lock = asyncio.Lock()

        self.ws_client_list: List[WebSocketHandler] = []

    @classmethod
    async def cancel_task(cls, task):
        """取消异步任务"""
        try:
            task.cancel()
            await task
        except asyncio.CancelledError:
            logger.debug("任务已取消")
        except Exception as e:
            logger.debug(f"任务异常: {e}")

    async def cleanup_existing_scrcpy_processes(self):
        """清理设备上已存在的scrcpy进程"""
        try:
            cfg = get_adb_config()
            adb_host = cfg["host"]
            adb_port = cfg["port"]

            cleanup_commands = [
                "pkill -f 'app_process.*scrcpy'",
                "pkill -f 'scrcpy-server'",
                "pkill -f 'scrcpy'",
                "ps aux | grep -E '(scrcpy|app_process.*com.genymobile.scrcpy)' | grep -v grep | "
                "awk '{print $2}' | xargs -r kill -9"
            ]

            for cmd in cleanup_commands:
                try:
                    kill_cmd = [
                        "adb", "-H", adb_host, "-P", str(adb_port),
                        "-s", self.serial, "shell", cmd
                    ]
                    result = subprocess.run(kill_cmd, capture_output=True, text=True, timeout=10)
                    if result.returncode == 0 and result.stdout.strip():
                        logger.info(f"[{self.serial}] 清理scrcpy进程: {cmd}")
                except Exception as e:
                    logger.debug(f"[{self.serial}] 清理命令执行失败: {cmd}, 错误: {e}")

            await asyncio.sleep(1)

        except Exception as e:
            logger.warning(f"[{self.serial}] 清理scrcpy进程时出错: {e}")

    async def prepare_server(self):
        """准备scrcpy-server"""
        try:
            await self.cleanup_existing_scrcpy_processes()

            cfg = get_adb_config()
            adb_host = cfg["host"]
            adb_port = cfg["port"]

            adb_client = adbutils.AdbClient(host=adb_host, port=adb_port)
            device = adb_client.device(self.serial)

            try:
                result = device.shell("ls /data/local/tmp/scrcpy-server")
                if "No such file" in result:
                    raise FileNotFoundError("scrcpy-server不存在")
            except Exception:
                scrcpy_server_path = STATIC_DIR / "scrcpy-server"
                scrcpy_zip_path = STATIC_DIR / "scrcpy-server-1.24.zip"

                if not scrcpy_server_path.exists() and scrcpy_zip_path.exists():
                    logger.info(f"[{self.serial}] 解压scrcpy-server-1.24.zip...")
                    with zipfile.ZipFile(scrcpy_zip_path, 'r') as zip_ref:
                        for file_info in zip_ref.filelist:
                            if file_info.filename.endswith('scrcpy-server') or file_info.filename == 'scrcpy-server':
                                with zip_ref.open(file_info) as source, open(scrcpy_server_path, 'wb') as target:
                                    target.write(source.read())
                                break

                    if scrcpy_server_path.exists():
                        logger.info(f"[{self.serial}] scrcpy-server解压成功")
                    else:
                        raise ConnectionError("zip文件中未找到scrcpy-server")

                if scrcpy_server_path.exists():
                    logger.info(f"[{self.serial}] 推送scrcpy-server...")
                    push_cmd = [
                        "adb", "-H", adb_host, "-P", str(adb_port),
                        "-s", self.serial, "push",
                        str(scrcpy_server_path), "/data/local/tmp/scrcpy-server"
                    ]
                    push_result = subprocess.run(push_cmd, capture_output=True, text=True, timeout=30)

                    if push_result.returncode == 0:
                        chmod_cmd = [
                            "adb", "-H", adb_host, "-P", str(adb_port),
                            "-s", self.serial, "shell", "chmod 755 /data/local/tmp/scrcpy-server"
                        ]
                        subprocess.run(chmod_cmd, capture_output=True, text=True, timeout=10)
                        logger.info(f"[{self.serial}] scrcpy-server推送成功")
                    else:
                        raise ConnectionError(f"推送scrcpy-server失败: {push_result.stderr}")
                else:
                    raise ConnectionError("本地scrcpy-server文件不存在")

            scrcpy_cmd = [
                "CLASSPATH=/data/local/tmp/scrcpy-server",
                "app_process",
                "/",
                "com.genymobile.scrcpy.Server",
                "1.24",
                "log_level=info",
                f"max_size={self.max_size}",
                f"bit_rate={self.bit_rate}",
                f"max_fps={self.max_fps}",
                "lock_video_orientation=-1",
                "tunnel_forward=true",
                "control=true",
                "display_id=0",
                "show_touches=false",
                "stay_awake=true",
                "power_off_on_close=false",
                "clipboard_autosync=false",
                "downsize_on_error=true",
                "cleanup=true",
                "power_on=false",
                "send_device_meta=true",
                "send_frame_meta=false",
                "send_dummy_byte=true",
                "raw_video_stream=false",
            ]

            commands = [
                "adb", "-H", adb_host, "-P", str(adb_port),
                "-s", self.serial, "shell", " ".join(scrcpy_cmd)
            ]

            self.shell_socket = subprocess.Popen(
                commands,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
            )

            await asyncio.sleep(2)

            if self.shell_socket.poll() is not None:
                output = self.shell_socket.stdout.read()
                error_output = output if output else "无输出"
                logger.error(f"[{self.serial}] scrcpy-server进程退出，输出: {error_output}")
                raise ConnectionError(f"scrcpy-server启动失败: {error_output}")

        except Exception as e:
            logger.error(f"[{self.serial}] 准备scrcpy-server失败: {e}")
            if self.shell_socket:
                try:
                    self.shell_socket.terminate()
                except:
                    pass
                self.shell_socket = None
            raise ConnectionError(f"启动scrcpy-server失败: {e}")

    async def prepare_socket(self):
        """准备socket连接 - video socket + control socket"""
        try:
            self.video_socket = await self._connect_scrcpy_socket(self.connect_timeout)

            dummy_byte = await self.video_socket.read_bytes(1)
            if not len(dummy_byte) or dummy_byte != b"\x00":
                raise ConnectionError("未收到Dummy Byte")

            self.control_socket = await self._connect_scrcpy_socket(self.connect_timeout)

            device_name_bytes = await self.video_socket.read_bytes(64)
            self.name = device_name_bytes.decode("utf-8").rstrip("\x00")
            if not len(self.name):
                raise ConnectionError("未收到设备名称")

            resolution_bytes = await self.video_socket.read_bytes(4)
            self.resolution = struct.unpack(">HH", resolution_bytes)

        except Exception as e:
            logger.error(f"[{self.serial}] socket准备失败: {e}")
            raise

    async def _connect_scrcpy_socket(self, timeout: int = 10) -> AdbClient:
        """连接 scrcpy 的 localabstract socket，带重试"""
        cfg = get_adb_config()
        last_error = None

        for i in range(timeout * 100):
            socket = None
            try:
                socket = await AdbClient.connect(host=cfg["host"], port=cfg["port"])
                await socket.write_and_check(f'host:transport:{self.serial}')
                await socket.write_and_check('localabstract:scrcpy')
                return socket
            except Exception as e:
                last_error = e
                if socket:
                    socket.disconnect()
                await asyncio.sleep(0.01)

        raise ConnectionError(f"{self.serial} 连接 localabstract:scrcpy 失败: {last_error}")

    async def send_control(self, data: bytes):
        """发送控制指令到scrcpy control socket"""
        if not self.control_socket:
            return
        if self.control_socket._conn is None or self.control_socket._conn.closed():
            self.control_socket = None
            return
        try:
            await self.control_socket.write(data)
        except Exception as e:
            logger.error(f"[{self.serial}] 控制指令发送失败: {e}")
            self.control_socket = None

    async def _video_task(self):
        """视频数据处理任务"""
        while True:
            try:
                data = await self.video_socket.read_bytes_until(b'\x00\x00\x00\x01')
                if data.endswith(b'\x00\x00\x00\x01'):
                    current_nal_data = b'\x00\x00\x00\x01' + data

                    for ws_client in self.ws_client_list:
                        await ws_client.write_message(current_nal_data, binary=True)

            except ConnectionError:
                logger.info(f"[{self.serial}] scrcpy连接断开")
                break
            except Exception as e:
                logger.info(f"[{self.serial}] scrcpy连接异常: {str(e)}")
                break

    def update_resolution(self, current_nal_data: bytes):
        """根据SPS帧更新分辨率"""
        if current_nal_data.startswith(b'\x00\x00\x00\x01g'):
            sps = SPS(BitStream(current_nal_data[5:]), False)
            width = (sps.pic_width_in_mbs_minus_1 + 1) * 16
            height = (2 - sps.frame_mbs_only_flag) * (sps.pic_height_in_map_units_minus_1 + 1) * 16

            if width > height:
                resolution = (max(self.resolution), min(self.resolution))
            else:
                resolution = (min(self.resolution), max(self.resolution))
            self.resolution = resolution

    async def prepare(self):
        """准备scrcpy连接"""
        await self.prepare_server()
        await self.prepare_socket()

    async def start(self):
        """启动scrcpy连接"""
        self.video_data_transfer = asyncio.create_task(self._video_task())

    async def stop(self, skip_device_cleanup: bool = False):
        """停止scrcpy连接，skip_device_cleanup=True 时跳过设备端进程清理（设备已离线）"""
        if self.video_data_transfer:
            await self.cancel_task(self.video_data_transfer)
            self.video_data_transfer = None

        if self.control_socket:
            self.control_socket.disconnect()
            self.control_socket = None

        if self.video_socket:
            self.video_socket.disconnect()
            self.video_socket = None

        if self.shell_socket:
            try:
                self.shell_socket.terminate()
            except:
                pass
            self.shell_socket = None

        if not skip_device_cleanup:
            try:
                await self.cleanup_existing_scrcpy_processes()
            except Exception as e:
                logger.warning(f"[{self.serial}] 停止时清理进程失败: {e}")

