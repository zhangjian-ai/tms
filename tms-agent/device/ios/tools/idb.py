import os
import json
import subprocess

from typing import List, Dict, Optional
from logzero import logger

from utils.variables import settings
from utils.binaries import resolve as resolve_bin

# go-ios 可执行文件：优先 settings.ios.go_ios_bin 显式配置，否则用项目自带二进制（无需 Go 运行时）
GO_IOS_BIN = settings.get("ios", {}).get("go_ios_bin") or resolve_bin("ios")


def _is_root() -> bool:
    return hasattr(os, "geteuid") and os.geteuid() == 0


class Idb:
    """go-ios 工具封装（支持 iOS 17+）

    职责：列设备 / 设备信息 / 查 WDA / 启动 WDA / 端口转发 / RSD 隧道。
    WDA 的 HTTP 交互仍由上层 WDAClient 负责，本类只管"启动 WDA + 把端口转发到本地"。

    权限说明（macOS/Linux）：go-ios 读取配对记录(/var/db/lockdown，root 专属)、建立
    iOS 17+ 的 RSD 隧道均需 root。因此**所有** go-ios 命令在非 root 运行时统一经
    `sudo -S` 提权，密码取 settings.ios.sudo_password；已是 root 则直接执行、无需 sudo。
    （早期版本仅对 tunnel 提权，导致 info/apps 等以普通用户运行时读不到配对记录，
    表现为"设备未配对/未安装 WDA"。）
    """

    @staticmethod
    def _sudo():
        """返回 (sudo 前缀列表, 需写入 stdin 的密码字符串或 None)。

        - 已是 root：无前缀、无需密码
        - 非 root：`sudo -S -p ''` 从 stdin 读密码免交互；未配置密码则前缀仍加上（sudo 会失败并告警）
        """
        if _is_root():
            return [], None
        pw = settings.get("ios", {}).get("sudo_password")
        return ["sudo", "-S", "-p", ""], (pw + "\n" if pw else "")

    @classmethod
    def _base(cls, udid: str = None) -> List[str]:
        prefix, _ = cls._sudo()
        cmd = prefix + [GO_IOS_BIN]
        if udid:
            cmd.append(f"--udid={udid}")
        return cmd

    @classmethod
    def _run(cls, args: List[str], timeout: int) -> subprocess.CompletedProcess:
        """执行一次性命令；非 root 时把 sudo 密码喂给 stdin。

        root 时 input=None（不连 stdin）；非 root 时 input=密码。go-ios 命令本身不读 stdin，
        sudo 消费首行密码后子进程拿到的多余输入无害。
        """
        _, pw = cls._sudo()
        return subprocess.run(
            args, capture_output=True, text=True, timeout=timeout, input=pw
        )

    @classmethod
    def _run_json(cls, args: List[str], timeout: int = 10) -> Optional[dict]:
        """执行一次性命令并解析其 JSON 输出"""
        try:
            result = cls._run(args, timeout)
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

    @classmethod
    def _spawn(cls, extra_args: List[str], udid: str = None) -> Optional[subprocess.Popen]:
        """启动长驻 go-ios 子进程（tunnel/runwda/forward）；非 root 时经 sudo 并把密码写入 stdin。

        输出重定向到 DEVNULL：这些进程会持续输出日志，若用 PIPE 无人读取会填满管道缓冲
        导致进程阻塞、功能卡死。
        """
        _, pw = cls._sudo()
        try:
            process = subprocess.Popen(
                cls._base(udid) + extra_args,
                stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, text=True,
            )
            if pw:
                try:
                    process.stdin.write(pw)
                    process.stdin.flush()
                except Exception as e:
                    logger.error(f"向 sudo 写入密码失败: {e}")
            else:
                # root 场景无需喂密码，关闭 stdin 避免子进程持有空管道
                try:
                    process.stdin.close()
                except Exception:
                    pass
            return process
        except Exception as e:
            logger.error(f"启动 go-ios 子进程失败 {extra_args}: {e}")
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

    def get_wda_status(self, udid: str) -> Optional[bool]:
        """检查 WDA 是否已安装：ios apps 输出中是否含 WebDriverAgent。

        三态返回，区分"命令失败"与"确实未安装"：
        - True  : 确认已安装
        - False : 命令成功执行但列表中没有 WDA，确实未安装
        - None  : 命令失败/超时/异常（未配对、隧道未起、设备繁忙等），状态未知——
                  上层应视作"暂不可判定"重试，而非误报"未安装"
        """
        try:
            result = self._run(self._base(udid) + ["apps", "--list"], timeout=10)
            if result.returncode != 0:
                logger.warning(f"检查 WDA 状态命令未成功（状态未知，稍后重试）{udid}: {result.stderr.strip()}")
                return None
            return "WebDriverAgent" in result.stdout
        except subprocess.TimeoutExpired:
            logger.warning(f"检查 WDA 状态超时（状态未知，稍后重试）: {udid}")
            return None
        except Exception as e:
            logger.warning(f"检查 WDA 状态异常（状态未知，稍后重试）{udid}: {e}")
            return None

    def start_tunnel(self) -> Optional[subprocess.Popen]:
        """启动 iOS 17+ 所需的 RSD 隧道守护进程（需 root）。

        非 17+ 设备不依赖隧道；若已由外部(systemd/launchd)常驻可在 settings 关闭 auto_tunnel。
        提权与密码由统一的 _spawn/_sudo 处理。
        """
        if not _is_root() and not settings.get("ios", {}).get("sudo_password"):
            logger.warning("未配置 ios.sudo_password 且非 root 运行，iOS 17+ 隧道及配对相关命令可能失败")
        process = self._spawn(["tunnel", "start"])
        if process:
            logger.info("go-ios 隧道守护已启动")
        return process

    def start_wda(self, udid: str, wda_bundle_id: str) -> Optional[subprocess.Popen]:
        """启动 WDA(XCUITest runner)：ios runwda，长驻进程。"""
        process = self._spawn(
            [
                "runwda",
                f"--bundleid={wda_bundle_id}",
                f"--testrunnerbundleid={wda_bundle_id}",
                "--xctestconfig=WebDriverAgentRunner.xctest",
            ],
            udid=udid,
        )
        if process:
            logger.info(f"WDA 已启动: {udid} ({wda_bundle_id})")
        return process

    def forward(self, udid: str, local_port: int, device_port: int) -> Optional[subprocess.Popen]:
        """端口转发：ios forward <local> <device>，长驻进程。

        WDA HTTP 用 device:8100，MJPEG 用 device:9100。
        """
        process = self._spawn(["forward", str(local_port), str(device_port)], udid=udid)
        if not process:
            logger.error(f"启动端口转发失败 {udid} {local_port}->{device_port}")
        return process
