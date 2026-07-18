import struct
import random
import zipfile
import asyncio
import json
import adbutils
import subprocess

from typing import List

from logzero import logger
from tornado.websocket import WebSocketHandler

from device.android.scrcpy.adb import AdbClient
from device.android.tools.adb import get_adb_config, adb_cmd
from device.android.tools.install import (
    AndroidToolDownloader,
    DOWNLOAD_DIR,
    SCRCPY_VERSION,
    SCRCPY_SERVER_REMOTE,
)

# 与 install.py 共用同一目录（device/android/tools/static）
STATIC_DIR = DOWNLOAD_DIR


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
        self.scid = None  # scrcpy 2.x 会话 id，决定 socket 名 scrcpy_<scid>

        self.async_lock = asyncio.Lock()

        self.ws_client_list: List[WebSocketHandler] = []

    @classmethod
    async def cancel_task(cls, task):
        """取消异步任务"""
        try:
            task.cancel()
            await task
        except asyncio.CancelledError:
            pass
        except Exception as e:
            logger.debug(f"任务异常: {e}")

    async def cleanup_existing_scrcpy_processes(self):
        """清理设备上已存在的scrcpy进程"""
        try:
            cleanup_commands = [
                "pkill -f 'app_process.*scrcpy'",
                "pkill -f 'scrcpy-server'",
                "pkill -f 'scrcpy'",
                "ps aux | grep -E '(scrcpy|app_process.*com.genymobile.scrcpy)' | grep -v grep | "
                "awk '{print $2}' | xargs -r kill -9"
            ]

            for cmd in cleanup_commands:
                try:
                    result = await asyncio.to_thread(
                        subprocess.run,
                        adb_cmd("shell", cmd, serial=self.serial),
                        capture_output=True, text=True, timeout=10
                    )
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
            # adbutils 客户端连接用本机地址
            adb_host = cfg["host"] if cfg["host"] != "0.0.0.0" else "127.0.0.1"
            adb_port = cfg["port"]

            adb_client = adbutils.AdbClient(host=adb_host, port=adb_port)
            device = adb_client.device(self.serial)

            server_zip = f"scrcpy-server-{SCRCPY_VERSION}.zip"

            try:
                result = await asyncio.to_thread(device.shell, f"ls {SCRCPY_SERVER_REMOTE}")
                if "No such file" in result:
                    raise FileNotFoundError("scrcpy-server不存在")
            except Exception:
                scrcpy_server_path = STATIC_DIR / "scrcpy-server"
                scrcpy_zip_path = STATIC_DIR / server_zip

                # 设备端缺 server 则推送，本地缺 zip 则下载
                if not scrcpy_server_path.exists():
                    if not scrcpy_zip_path.exists():
                        logger.info(f"[{self.serial}] 本地缺少 {server_zip}，尝试下载...")
                        await asyncio.to_thread(AndroidToolDownloader().download_scrcpy_server)

                    if scrcpy_zip_path.exists():
                        logger.info(f"[{self.serial}] 解压{server_zip}...")
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
                    push_result = await asyncio.to_thread(
                        subprocess.run,
                        adb_cmd("push", str(scrcpy_server_path), SCRCPY_SERVER_REMOTE, serial=self.serial),
                        capture_output=True, text=True, timeout=30
                    )

                    if push_result.returncode == 0:
                        await asyncio.to_thread(
                            subprocess.run,
                            adb_cmd("shell", f"chmod 755 {SCRCPY_SERVER_REMOTE}", serial=self.serial),
                            capture_output=True, text=True, timeout=10
                        )
                        logger.info(f"[{self.serial}] scrcpy-server推送成功")
                    else:
                        raise ConnectionError(f"推送scrcpy-server失败: {push_result.stderr}")
                else:
                    raise ConnectionError("本地scrcpy-server文件不存在")

            # scrcpy 2.x 参数（全部 key=value）
            self.scid = f"{random.getrandbits(31):08x}"
            scrcpy_cmd = [
                f"CLASSPATH={SCRCPY_SERVER_REMOTE}",
                "app_process",
                "/",
                "com.genymobile.scrcpy.Server",
                SCRCPY_VERSION,
                f"scid={self.scid}",
                "log_level=info",
                "video=true",
                "audio=false",
                f"max_size={self.max_size}",
                f"video_bit_rate={self.bit_rate}",
                f"max_fps={self.max_fps}",
                "video_codec=h264",
                "tunnel_forward=true",
                "control=true",
                "display_id=0",
                "show_touches=false",
                "stay_awake=true",
                "power_off_on_close=false",
                "clipboard_autosync=false",
                "downsize_on_error=true",
                "cleanup=false",  # 断开投屏后不清理设备上的scrcpy文件
                "power_on=false",
                "send_device_meta=true",
                "send_codec_meta=true",
                "send_frame_meta=true",
                "send_dummy_byte=true",
                "raw_stream=false",
            ]

            commands = adb_cmd("shell", " ".join(scrcpy_cmd), serial=self.serial)

            # stdout 丢弃（scrcpy-server 的 info 日志量大，若走管道且不消费会填满缓冲阻塞视频流）；
            # stderr 保留管道，仅在启动失败退出后读取用于报错。
            self.shell_socket = subprocess.Popen(
                commands,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.PIPE,
            )

            await asyncio.sleep(2)

            if self.shell_socket.poll() is not None:
                err = await asyncio.to_thread(self.shell_socket.stderr.read)
                error_output = err.decode("utf-8", "replace").strip() if err else "无输出"
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

            # scrcpy 2.x 视频头：codec_id(u32) + width(u32) + height(u32)，共 12 字节
            header = await self.video_socket.read_bytes(12)
            _codec_id, width, height = struct.unpack(">III", header)
            self.resolution = (width, height)

        except Exception as e:
            logger.error(f"[{self.serial}] socket准备失败: {e}")
            raise

    async def _connect_scrcpy_socket(self, timeout: int = 10) -> AdbClient:
        """连接 scrcpy 的 localabstract socket，带重试"""
        cfg = get_adb_config()
        adb_host = cfg["host"] if cfg["host"] != "0.0.0.0" else "127.0.0.1"
        last_error = None

        for i in range(timeout * 100):
            socket = None
            try:
                socket = await AdbClient.connect(host=adb_host, port=cfg["port"])
                await socket.write_and_check(f'host:transport:{self.serial}')
                # scrcpy 2.x 的 localabstract socket 名带 scid
                await socket.write_and_check(f'localabstract:scrcpy_{self.scid}')
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
        """视频数据处理任务。

        send_frame_meta=true：每帧前置 12 字节头（PTS+标志 u64 + 包长 u32），
        据此读取精确包长并读满即刻下发，不再靠起始码切分等待下一帧到达，
        消除约一帧（25fps 下最多 ~40ms）的固有转发延迟。
        每条 WS 二进制消息 = 一个完整的 H.264 访问单元（Annex-B，可能含多个 NAL，如配置包 SPS+PPS）。
        """
        while True:
            try:
                # 帧头：PTS+flags(u64) + 包长(u32)，共 12 字节
                header = await self.video_socket.read_bytes(12)
                _pts_and_flags, size = struct.unpack(">QI", header)
                if size == 0:
                    continue
                frame_data = await self.video_socket.read_bytes(size)
                for ws_client in self.ws_client_list:
                    await ws_client.write_message(frame_data, binary=True)

            except ConnectionError:
                logger.info(f"[{self.serial}] scrcpy连接断开")
                break
            except Exception as e:
                logger.info(f"[{self.serial}] scrcpy连接异常: {str(e)}")
                break

        self._notify_stream_disconnected()

    def _notify_stream_disconnected(self):
        """通知并关闭所有前端投屏 WS。"""
        for ws_client in list(self.ws_client_list):
            try:
                if ws_client.ws_connection and not ws_client.ws_connection.is_closing():
                    ws_client.write_message(json.dumps({"type": "device_disconnected", "serial": self.serial}))
                    ws_client.close()
            except Exception:
                pass

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

        self._notify_stream_disconnected()

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

