import os
import json
import subprocess

from typing import List, Dict, Optional
from logzero import logger

from utils.variables import settings
from utils.binaries import resolve as resolve_bin

# go-ios 可执行文件：优先 settings.ios.go_ios_bin，否则用项目自带二进制
GO_IOS_BIN = settings.get("ios", {}).get("wda", {}).get("go_ios_bin") or resolve_bin("ios")


def _is_root() -> bool:
    return hasattr(os, "geteuid") and os.geteuid() == 0


class Idb:
    """go-ios 工具封装（支持 iOS 17+）

    职责：列设备 / 设备信息 / 查 WDA / 启动 WDA / 端口转发 / RSD 隧道。
    所有 go-ios 命令在非 root 运行时统一经 sudo -S 提权（密码取 settings.ios.sudo_password）。
    """

    @staticmethod
    def _sudo():
        """返回 (sudo 前缀列表, 需写入 stdin 的密码字符串或 None)。"""
        if _is_root():
            return [], None
        pw = settings.get("ios", {}).get("wda", {}).get("sudo_password")
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
        """执行一次性命令；非 root 时把 sudo 密码喂给 stdin。"""
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

        输出重定向到 DEVNULL，避免持续日志填满管道缓冲导致阻塞。
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
                # root 场景关闭 stdin
                try:
                    process.stdin.close()
                except Exception:
                    pass
            return process
        except Exception as e:
            logger.error(f"启动 go-ios 子进程失败 {extra_args}: {e}")
            return None

    def list_devices(self) -> List[Dict]:
        """列出 USB 直连设备：ios list --details。

        用 --details 拿 ConnectionType，只保留 "USB"，过滤 Wi‑Fi 同步残留的网络设备。
        """
        data = self._run_json(self._base() + ["list", "--details"], timeout=5)
        if not data:
            return []
        result = []
        for entry in data.get("deviceList", []):
            # 兼容：--details 为 dict，退化为纯 udid 字符串时保守保留
            if isinstance(entry, dict):
                if entry.get("ConnectionType") != "USB":
                    continue
                udid = entry.get("Udid")
            else:
                udid = entry
            if udid:
                result.append({"udid": udid})
        return result

    def get_device_info(self, udid: str) -> Optional[Dict]:
        """获取设备信息：ios info（返回 lockdown 值，含 DeviceName/ProductType/ProductVersion）"""
        return self._run_json(self._base(udid) + ["info"], timeout=5)

    def get_wda_status(self, udid: str) -> bool:
        """检查 WDA 是否已安装：ios apps --list 输出中是否含 WebDriverAgent。

        命令失败/超时/异常统一按“未确认已安装”返回 False。
        """
        try:
            result = self._run(self._base(udid) + ["apps", "--list"], timeout=10)
            if result.returncode != 0:
                logger.warning(f"检查 WDA 状态未成功 {udid}: {result.stderr.strip()}")
                return False
            return "WebDriverAgent" in result.stdout
        except subprocess.TimeoutExpired:
            logger.warning(f"检查 WDA 状态超时: {udid}")
            return False
        except Exception as e:
            logger.warning(f"检查 WDA 状态异常 {udid}: {e}")
            return False

    def start_tunnel(self) -> Optional[subprocess.Popen]:
        """启动 iOS 17+ 所需的 RSD 隧道守护进程（需 root）。"""
        if not _is_root() and not settings.get("ios", {}).get("wda", {}).get("sudo_password"):
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
