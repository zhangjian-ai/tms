import platform
import stat
import subprocess
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


def _ensure_signed(path: Path) -> None:
    """macOS：启动即自愈——确保自带二进制的代码签名有效。

    go-ios/adb 经就地覆盖或跨机拷贝后，磁盘上的代码签名状态可能失效，
    导致 AMFI 在启动时把进程 SIGKILL（Killed: 9）或卡在 dyld 加载阶段。
    这里校验失败则用 ad-hoc 重新签名（等价于手动 `codesign --force --sign -`），
    重签一次后即稳定，后续校验会直接通过。非 macOS 无 codesign，直接跳过。
    """
    if platform.system() != "Darwin":
        return
    try:
        verify = subprocess.run(
            ["codesign", "--verify", str(path)],
            capture_output=True, text=True, timeout=10,
        )
        if verify.returncode == 0:
            return
        logger.warning(f"二进制签名无效，尝试 ad-hoc 重签: {path}（{verify.stderr.strip()}）")
        resign = subprocess.run(
            ["codesign", "--force", "--sign", "-", str(path)],
            capture_output=True, text=True, timeout=30,
        )
        if resign.returncode != 0:
            logger.error(f"ad-hoc 重签失败 {path}: {resign.stderr.strip()}")
        else:
            logger.info(f"已重新签名，规避 AMFI kill/挂起: {path}")
    except FileNotFoundError:
        # 无 codesign（非标准 mac 环境）：跳过，交由系统决定
        pass
    except Exception as e:
        logger.warning(f"签名校验/重签异常 {path}: {e}")


def resolve(name: str) -> str:
    """返回项目自带二进制的绝对路径，运行时不依赖宿主机环境。

    - 命中自带文件：确保可执行权限 +（macOS）签名有效后返回其绝对路径
    - 未命中：回退到 PATH 中的同名命令（并告警）
    """
    path = BIN_DIR / _platform_dir() / name
    if path.exists():
        mode = path.stat().st_mode
        if not (mode & stat.S_IXUSR):
            path.chmod(mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
        _ensure_signed(path)
        return str(path)

    logger.warning(f"未找到自带二进制 {path}，回退到 PATH 中的 '{name}'（请执行 scripts/fetch_binaries.sh 准备）")
    return name
