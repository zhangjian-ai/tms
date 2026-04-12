from utils.variables import settings

DEFAULT_ADB_HOST = "127.0.0.1"
DEFAULT_ADB_PORT = 5037


def get_adb_config() -> dict:
    """获取 ADB 配置，返回 {"host": str, "port": int}"""
    adb_config = settings.get("android", {}).get("adb", {})
    return {
        "host": adb_config.get("host", DEFAULT_ADB_HOST),
        "port": adb_config.get("port", DEFAULT_ADB_PORT),
    }


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
