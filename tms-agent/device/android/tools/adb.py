from utils.variables import settings
from utils.binaries import resolve as resolve_bin

import os
import signal
import socket
import subprocess
import time

from logzero import logger

DEFAULT_ADB_HOST = "127.0.0.1"
DEFAULT_ADB_PORT = 5037


def get_adb_config() -> dict:
    """获取 ADB 配置，返回 {"host": str, "port": int}"""
    adb_config = settings.get("android", {}).get("adb", {})
    return {
        "host": adb_config.get("host", DEFAULT_ADB_HOST),
        "port": adb_config.get("port", DEFAULT_ADB_PORT),
    }


def get_adb_bin() -> str:
    """adb 二进制路径：优先 settings.android.adb.bin 显式配置，否则用项目自带二进制。

    统一二进制版本是消除"多版本客户端触发 server 重启"的根本手段。
    """
    cfg_bin = settings.get("android", {}).get("adb", {}).get("bin")
    return cfg_bin if cfg_bin else resolve_bin("adb")


def pin_adbutils_to_bundled() -> str:
    """把 adbutils 客户端指向自带 adb（进程级，幂等）。

    设置 ADBUTILS_ADB_PATH，使进程内所有 adbutils 客户端自动起 server 时统一用自带二进制。
    """
    bin_path = get_adb_bin()
    os.environ["ADBUTILS_ADB_PATH"] = bin_path
    return bin_path


def adb_cmd(*args: str, serial: str = None) -> list:
    """构造一条统一的 adb 命令：[bin, -H host, -P port, (-s serial,) *args]

    所有 subprocess 调用都应经此构造，保证使用同一 adb 二进制并指向同一 server。
    """
    cfg = get_adb_config()
    # 客户端连接目标用本机地址；server 监听 0.0.0.0 由 -a 控制，二者独立
    host = cfg["host"] if cfg["host"] != "0.0.0.0" else "127.0.0.1"
    cmd = [get_adb_bin(), "-H", host, "-P", str(cfg["port"])]
    if serial:
        cmd += ["-s", serial]
    cmd += list(args)
    return cmd


def adb_server_alive(port: int = None, timeout: float = 2.0) -> bool:
    """裸 TCP 探测 adb server 是否在监听（不触发任何 start-server）。

    只区分"有监听"与"连接被拒"；繁忙但存活的 server 内核层仍会完成握手，返回 True。
    """
    if port is None:
        port = int(get_adb_config()["port"])
    s = socket.socket()
    s.settimeout(timeout)
    try:
        s.connect(("127.0.0.1", int(port)))
        return True
    except OSError:
        return False
    finally:
        s.close()


def free_adb_port(port: int, wait: float = 0.5) -> None:
    """强制释放 adb server 端口：按端口定位占用进程并 SIGKILL（lsof，mac/linux 通用）。"""
    try:
        out = subprocess.run(
            ["lsof", "-ti", f"tcp:{port}"],
            capture_output=True, text=True, timeout=5,
        ).stdout
    except Exception as e:
        logger.warning(f"lsof 查询端口 {port} 占用失败: {e}")
        return

    pids = {p for p in out.split() if p.isdigit()}
    if not pids:
        return

    logger.info(f"adb 端口 {port} 被占用，强制清理进程: {sorted(pids)}")
    for pid in pids:
        try:
            os.kill(int(pid), signal.SIGKILL)
        except ProcessLookupError:
            pass
        except Exception as e:
            logger.warning(f"清理端口 {port} 占用进程 {pid} 失败: {e}")
    if wait:
        time.sleep(wait)  # 等内核释放监听套接字，避免随后 bind 撞 TIME_WAIT


def restart_adb_server(port: int = None) -> bool:
    """清端口 → 起一个监听全网卡的 adb server（幂等，用于启动与运行期自愈）。

    返回是否成功。所有 adb 子进程均带 timeout。
    """
    if port is None:
        port = int(get_adb_config()["port"])
    bin_path = get_adb_bin()

    # 先 kill-server 常规关闭本端口 server
    try:
        subprocess.run([bin_path, "-P", str(port), "kill-server"],
                       capture_output=True, timeout=5)
    except Exception:
        pass

    free_adb_port(port)

    try:
        # -a：监听所有网卡供 LAN 访问；端口由 -P 指定
        r = subprocess.run([bin_path, "-a", "-P", str(port), "start-server"],
                           capture_output=True, timeout=20)
        if r.returncode != 0:
            logger.warning(f"adb start-server 失败(port={port}): {r.stderr.decode(errors='ignore').strip()}")
            return False
        logger.info(f"adb server 已在端口 {port} 启动")
        return True
    except subprocess.TimeoutExpired:
        logger.error(f"adb start-server 仍超时(port={port})：端口可能被非 adb 程序占用")
        return False
    except Exception as e:
        logger.error(f"adb start-server 异常(port={port}): {e}")
        return False


def encode_command(command: str) -> bytes:
    """编码 ADB 命令：4字节十六进制 ASCII 长度前缀 + UTF-8 数据"""
    data = command.encode('utf-8')
    length_hex = f"{len(data):04x}".encode('ascii')
    return length_hex + data


def decode_length(length_str: str) -> int:
    """解码4字节十六进制 ASCII 长度字符串"""
    try:
        return int(length_str, 16)
    except ValueError:
        return 0
