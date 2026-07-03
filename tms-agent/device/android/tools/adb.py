from utils.variables import settings
from utils.binaries import resolve as resolve_bin

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
