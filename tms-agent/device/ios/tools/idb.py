import os
import json
import subprocess

from typing import List, Dict, Optional
from logzero import logger

from utils.variables import settings
from utils.binaries import resolve as resolve_bin

# go-ios 可执行文件：优先 settings.ios.go_ios_bin 显式配置，否则用项目自带二进制（无需 Go 运行时）
GO_IOS_BIN = settings.get("ios", {}).get("go_ios_bin") or resolve_bin("ios")


class Idb:
    """go-ios 工具封装（支持 iOS 17+）

    职责：列设备 / 设备信息 / 查 WDA / 启动 WDA / 端口转发。
    WDA 的 HTTP 交互仍由上层 WDAClient 负责，本类只管"启动 WDA + 把端口转发到本地"。
    """

    @staticmethod
    def _base(udid: str = None) -> List[str]:
        cmd = [GO_IOS_BIN]
        if udid:
            cmd.append(f"--udid={udid}")
        return cmd

    @classmethod
    def _run_json(cls, args: List[str], timeout: int = 10) -> Optional[dict]:
        """执行一次性命令并解析其 JSON 输出"""
        try:
            result = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
            if result.returncode != 0 or not result.stdout.strip():
                logger.warning(f"go-ios 命令失败 {args}: {result.stderr.strip()}")
                return None
            return json.loads(result.stdout)
        except subprocess.TimeoutExpired:
            logger.error(f"go-ios 命令超时: {args}")
            return None
        except json.JSONDecodeError as e:
            logger.error(f"go-ios 输出 JSON 解析失败 {args}: {e}")
            return None
        except FileNotFoundError:
            logger.error(f"未找到 go-ios 可执行文件 '{GO_IOS_BIN}'，请确认已安装并在 PATH 中")
            return None
        except Exception as e:
            logger.error(f"go-ios 命令异常 {args}: {e}")
            return None

    def list_devices(self) -> List[Dict]:
        """列出已连接设备：ios list -> {"deviceList": ["udid", ...]}"""
        data = self._run_json(self._base() + ["list"], timeout=5)
        if not data:
            return []
        return [{"udid": udid} for udid in data.get("deviceList", [])]

    def get_device_info(self, udid: str) -> Optional[Dict]:
        """获取设备信息：ios info（返回 lockdown 值，含 DeviceName/ProductType/ProductVersion）"""
        return self._run_json(self._base(udid) + ["info"], timeout=5)

    def get_wda_status(self, udid: str) -> bool:
        """检查 WDA 是否已安装：ios apps 输出中是否含 WebDriverAgent"""
        try:
            result = subprocess.run(
                self._base(udid) + ["apps", "--list"],
                capture_output=True, text=True, timeout=10
            )
            return "WebDriverAgent" in result.stdout
        except Exception as e:
            logger.error(f"检查 WDA 状态失败 {udid}: {e}")
            return False

    def start_tunnel(self) -> Optional[subprocess.Popen]:
        """启动 iOS 17+ 所需的 RSD 隧道守护进程（需 root）。

        - 已是 root：直接启动
        - 非 root：用 settings.ios.sudo_password 经 `sudo -S` 从 stdin 免交互提权
        非 17+ 设备不依赖隧道；若已由外部(systemd/launchd)常驻可在 settings 关闭 auto_tunnel。
        """
        base = self._base() + ["tunnel", "start"]

        # 已是 root，无需 sudo
        if hasattr(os, "geteuid") and os.geteuid() == 0:
            return self._spawn_tunnel(base, None)

        sudo_password = settings.get("ios", {}).get("sudo_password")
        if sudo_password:
            # sudo -S 从 stdin 读密码，-p '' 关闭提示语
            return self._spawn_tunnel(["sudo", "-S", "-p", ""] + base, sudo_password)

        logger.warning("未配置 ios.sudo_password 且非 root 运行，iOS 17+ 隧道可能启动失败")
        return self._spawn_tunnel(base, None)

    @staticmethod
    def _spawn_tunnel(cmd: List[str], sudo_password: Optional[str]) -> Optional[subprocess.Popen]:
        """启动隧道进程；若提供密码则写入 stdin 供 sudo -S 使用。

        输出重定向到 DEVNULL（隧道长驻且日志多，PIPE 无人读会阻塞）。
        """
        try:
            process = subprocess.Popen(
                cmd,
                stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, text=True
            )
            if sudo_password is not None:
                try:
                    process.stdin.write(sudo_password + "\n")
                    process.stdin.flush()
                except Exception as e:
                    logger.error(f"向 sudo 写入密码失败: {e}")
            logger.info("go-ios 隧道守护已启动")
            return process
        except Exception as e:
            logger.error(f"启动 go-ios 隧道失败: {e}")
            return None

    def start_wda(self, udid: str, wda_bundle_id: str) -> Optional[subprocess.Popen]:
        """启动 WDA(XCUITest runner)：ios runwda，长驻进程。

        输出重定向到 DEVNULL：runwda 会持续输出测试日志，若用 PIPE 无人读取会填满管道缓冲
        导致进程阻塞、WDA 卡死。
        """
        try:
            process = subprocess.Popen(
                self._base(udid) + [
                    "runwda",
                    f"--bundleid={wda_bundle_id}",
                    f"--testrunnerbundleid={wda_bundle_id}",
                    "--xctestconfig=WebDriverAgentRunner.xctest",
                ],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL
            )
            logger.info(f"WDA 已启动: {udid} ({wda_bundle_id})")
            return process
        except Exception as e:
            logger.error(f"启动 WDA 失败 {udid}: {e}")
            return None

    def forward(self, udid: str, local_port: int, device_port: int) -> Optional[subprocess.Popen]:
        """端口转发：ios forward <local> <device>，长驻进程。

        WDA HTTP 用 device:8100，MJPEG 用 device:9100。
        输出重定向到 DEVNULL，避免管道缓冲填满阻塞转发进程。
        """
        try:
            process = subprocess.Popen(
                self._base(udid) + ["forward", str(local_port), str(device_port)],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL
            )
            return process
        except Exception as e:
            logger.error(f"启动端口转发失败 {udid} {local_port}->{device_port}: {e}")
            return None
