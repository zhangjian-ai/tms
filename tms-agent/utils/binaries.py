import platform
import stat
from pathlib import Path

from logzero import logger

# 项目自带二进制根目录：bin/<platform>/<name>
BIN_DIR = Path(__file__).parent.parent / "bin"


def _platform_dir() -> str:
    """当前平台对应的子目录（仅支持 mac/linux）"""
    sysname = platform.system()
    if sysname == "Darwin":
        return "darwin"
    if sysname == "Linux":
        return "linux"
    raise RuntimeError(f"不支持的平台: {sysname}（仅支持 mac/linux）")


def resolve(name: str) -> str:
    """返回项目自带二进制的绝对路径，运行时不依赖宿主机环境。

    - 命中自带文件：确保可执行权限后返回其绝对路径
    - 未命中：回退到 PATH 中的同名命令（并告警）
    """
    path = BIN_DIR / _platform_dir() / name
    if path.exists():
        mode = path.stat().st_mode
        if not (mode & stat.S_IXUSR):
            path.chmod(mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
        return str(path)

    logger.warning(f"未找到自带二进制 {path}，回退到 PATH 中的 '{name}'（请执行 scripts/fetch_binaries.sh 准备）")
    return name
