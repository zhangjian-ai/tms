import requests
import uiautomator2 as u2

from typing import Dict
from pathlib import Path

from logzero import logger

# 配置下载目录
DOWNLOAD_DIR = Path(__file__).parent / "static"
DOWNLOAD_DIR.mkdir(exist_ok=True)

# 镜像源配置
MIRROR_SOURCES = {
    # scrcpy-server镜像源（设备端服务器）
    "scrcpy_server": [
        "https://github.com/Genymobile/scrcpy/releases/download"  # GitHub官方
    ]
}


class AndroidToolDownloader:
    """Android工具下载器"""

    def __init__(self, download_dir: Path = DOWNLOAD_DIR):
        self.download_dir = download_dir
        self.download_dir.mkdir(exist_ok=True)

        # 镜像源配置（可动态修改）
        self.mirror_sources = MIRROR_SOURCES.copy()

    def add_mirror_source(self, tool_name: str, mirror_url: str, priority: int = 0):
        """
        添加自定义镜像源

        Args:
            tool_name: 工具名称 (uiautomator2, atx_agent, scrcpy)
            mirror_url: 镜像URL
            priority: 优先级，0为最高优先级
        """
        if tool_name not in self.mirror_sources:
            self.mirror_sources[tool_name] = []

        if priority == 0:
            self.mirror_sources[tool_name].insert(0, mirror_url)
        else:
            self.mirror_sources[tool_name].append(mirror_url)

    def download_file(self, url: str, file_path: Path) -> bool:
        """
        下载文件

        Args:
            url: 下载链接
            file_path: 保存路径

        Returns:
            bool: 下载是否成功
        """
        if file_path.exists():
            logger.info(f"文件 {file_path.name} 已存在，跳过下载")
            return True

        try:
            response = requests.get(url, stream=True)
            response.raise_for_status()

            with open(file_path, 'wb') as f:
                for chunk in response.iter_content(chunk_size=8192):
                    if chunk:
                        f.write(chunk)

            return True

        except Exception as e:
            logger.error(f"下载失败 {file_path.name}: {e}")
            if file_path.exists():
                file_path.unlink()
            return False

    def download_scrcpy_server(self) -> bool:
        """下载scrcpy-server"""
        logger.info("下载scrcpy-server...")

        # scrcpy-server版本
        version = "1.24"
        server_file = f"scrcpy-server-v{version}"
        zip_file = f"scrcpy-server-{version}.zip"

        # 检查zip文件是否已存在
        zip_path = self.download_dir / zip_file
        if zip_path.exists():
            logger.info(f"scrcpy-server zip已存在: {zip_file}")
            return True

        # 下载scrcpy-server原始文件
        for mirror in self.mirror_sources.get("scrcpy_server", []):
            url = f"{mirror}/v{version}/{server_file}"
            server_path = self.download_dir / server_file

            try:
                if self.download_file(url, server_path):
                    # 创建zip文件
                    import zipfile
                    with zipfile.ZipFile(zip_path, 'w', zipfile.ZIP_STORED) as zf:
                        zf.write(server_path, arcname="scrcpy-server")

                    # 删除原始文件
                    server_path.unlink()
                    logger.info(f"scrcpy-server下载成功: {zip_file}")
                    return True
            except Exception as e:
                logger.warning(f"从镜像 {mirror} 下载失败: {e}")
                if server_path.exists():
                    server_path.unlink()
                continue

        logger.error("scrcpy-server下载失败")
        return False


class AndroidDeviceInstaller:
    """Android设备安装器 - 使用adbutils的改进版本"""

    def __init__(self):
        """初始化安装器"""

        self.downloader = AndroidToolDownloader()
        self.download_dir = DOWNLOAD_DIR

    def install_scrcpy_server(self, device: u2.Device) -> bool:
        """安装scrcpy-server到设备"""
        logger.info(f"开始安装scrcpy-server到设备: {device.serial}")

        server_zip = "scrcpy-server-1.24.zip"
        zip_path = self.download_dir / server_zip

        # 检查zip文件是否已下载
        if not zip_path.exists():
            logger.info("scrcpy-server不存在，开始下载...")
            if not self.downloader.download_scrcpy_server():
                logger.error("下载scrcpy-server失败")
                return False

        device_path = "/data/local/tmp/scrcpy-server"

        # 检查设备上是否已存在
        try:
            result = device.shell(f"ls -l {device_path}")
            if "No such file" not in result.output:
                logger.info("scrcpy-server已存在于设备")
                return True
        except:
            pass  # 文件不存在，继续安装

        try:
            # 解压并推送到设备
            import zipfile
            with zipfile.ZipFile(zip_path, 'r') as zf:
                with zf.open('scrcpy-server') as server_file:
                    # 推送到设备
                    device.push(server_file, device_path, mode=0o755)

            # 验证安装
            result = device.shell(f"ls -l {device_path}")
            if "No such file" not in result:
                logger.info("scrcpy-server安装成功")
                return True
            else:
                logger.error("scrcpy-server安装验证失败")
                return False

        except Exception as e:
            logger.error(f"安装scrcpy-server失败: {e}")
            return False

    @staticmethod
    def verify_installation(device: u2.Device) -> Dict[str, bool]:
        """验证设备安装状态"""
        status = {}

        # 检查scrcpy-server是否已安装
        try:
            result = device.shell("ls -l /data/local/tmp/scrcpy-server").output
            status["scrcpy_server"] = "scrcpy-server" in result
        except:
            status["scrcpy_server"] = False

        # 检查设备基本信息
        try:
            info = device.device_info
            status["device_info"] = True
        except:
            status["device_info"] = False

        return status

    def install_to_device(self, device_serial: str) -> bool:
        """安装到指定设备"""
        try:
            device = u2.connect(device_serial)
            device.window_size()  # 触发atx-agent检查安装
            logger.info(f"为设备安装接入工具: {device_serial}")

            # 安装scrcpy-server
            self.install_scrcpy_server(device)

            # 验证安装
            status = self.verify_installation(device)
            success = all(status.values())

            return success

        except Exception as e:
            logger.error(f"安装到设备失败: {e}")
            return False
