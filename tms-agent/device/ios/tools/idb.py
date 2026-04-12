import json
import subprocess

from typing import List, Dict, Optional
from logzero import logger


class Idb:
    """tidevice 工具封装类"""

    @staticmethod
    def list_devices() -> List[Dict]:
        """
        获取已连接的 iOS 设备列表
        执行: tidevice list --json
        返回: [{"udid": "xxx", "name": "iPhone", "version": "15.0"}]
        """
        try:
            result = subprocess.run(
                ["tidevice", "list", "--json"],
                capture_output=True,
                text=True,
                timeout=5
            )
            if result.returncode == 0 and result.stdout.strip():
                return json.loads(result.stdout)
                devices = json.loads(result.stdout)
                return devices if devices else []
            return []
        except subprocess.TimeoutExpired:
            logger.error("tidevice list 超时")
            return []
        except json.JSONDecodeError as e:
            logger.error(f"tidevice list JSON 解析失败: {e}")
            return []
        except Exception as e:
            logger.error(f"tidevice list 失败: {e}")
            return []

    @staticmethod
    def get_device_info(udid: str) -> Optional[Dict]:
        """
        获取设备详细信息
        执行: tidevice --udid {udid} info --json
        """
        try:
            result = subprocess.run(
                ["tidevice", "-u", udid, "info", "--json"],
                capture_output=True,
                text=True,
                timeout=5
            )
            if result.returncode == 0 and result.stdout.strip():
                return json.loads(result.stdout)
            return None
        except subprocess.TimeoutExpired:
            logger.error(f"获取设备信息超时 {udid}")
            return None
        except json.JSONDecodeError as e:
            logger.error(f"设备信息 JSON 解析失败 {udid}: {e}")
            return None
        except Exception as e:
            logger.error(f"获取设备信息失败 {udid}: {e}")
            return None

    @staticmethod
    def get_wda_status(udid: str) -> bool:
        """
        检查 WDA 是否已安装
        执行: tidevice -u {udid} xctest list
        """
        try:
            result = subprocess.run(
                f"tidevice -u {udid} applist | grep WebDriverAgent",
                shell=True,  # 命令是一整个字符串时，要开启shell模式
                capture_output=True,
                text=True,
                timeout=5
            )
            # 检查输出中是否包含 WebDriverAgent
            return "WebDriverAgentRunner" in result.stdout
        except subprocess.TimeoutExpired:
            logger.error(f"检查 WDA 状态超时 {udid}")
            return False
        except Exception as e:
            logger.error(f"检查 WDA 状态失败 {udid}: {e}")
            return False

    @staticmethod
    def start_wda_proxy(udid: str, wda_bundle_id: str, local_port: int) -> Optional[subprocess.Popen]:
        """
        启动 WDA 代理
        执行: tidevice -u {udid} wdaproxy -B {bundle_id} --port {local_port}
        这个命令会自动启动 WDA 并建立代理
        返回: Popen 对象，用于后续管理进程
        """
        try:
            process = subprocess.Popen(
                [
                    "tidevice",
                    "-u", udid,
                    "wdaproxy",
                    "-B", wda_bundle_id,
                    "--port", str(local_port)
                ],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True
            )
            logger.info(f"WDA 代理已启动: {udid} - localhost:{local_port}")
            return process
        except Exception as e:
            logger.error(f"启动 WDA 代理失败 {udid}: {e}")
            return None

    @staticmethod
    def start_mjpeg_server(udid: str, local_port: int) -> Optional[subprocess.Popen]:
        """
        启动 MJPEG 服务器（用于视频投屏）
        执行: tidevice -u {udid} relay {local_port} 9100
        将设备的 MJPEG 服务（端口 9100）转发到本地端口
        返回: Popen 对象，用于后续管理进程
        """
        try:
            process = subprocess.Popen(
                [
                    "tidevice",
                    "-u", udid,
                    "relay",
                    str(local_port), "9100"
                ],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True
            )
            logger.info(f"MJPEG 服务器已启动: {udid} - localhost:{local_port} -> 9100")
            return process
        except Exception as e:
            logger.error(f"启动 MJPEG 服务器失败 {udid}: {e}")
            return None
