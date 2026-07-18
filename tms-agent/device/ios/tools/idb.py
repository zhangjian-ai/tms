import os
import json
import tempfile
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
    def reap_stale(cls, udid: str = None):
        """
        清理遗留的 runwda/forward 子进程。
        """
        prefix, _ = cls._sudo()
        # 命令行形如：<ios> --udid=<udid> {runwda|forward} ...；按 udid 精确匹配
        scope = f"--udid={udid}.*" if udid else ""
        for kind in ("runwda", "forward"):
            pattern = f"{GO_IOS_BIN}.*{scope}{kind}"
            try:
                # pkill 无匹配返回 1，不算失败
                cls._run(prefix + ["pkill", "-f", pattern], timeout=5)
            except Exception as e:
                logger.warning(f"清理残留 go-ios 进程失败 ({pattern}): {e}")
        logger.info(f"已清理遗留 go-ios runwda/forward 进程 (udid={udid or 'ALL'})")

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

        stdout/stderr 合并写入每进程独立临时文件（而非 DEVNULL），既避免管道缓冲阻塞，
        又能在 forward/runwda 启动失败时回读真实报错。文件路径记在 process._go_ios_log。
        """
        _, pw = cls._sudo()
        cmd = cls._base(udid) + extra_args
        logger.debug(f"spawn go-ios: {' '.join(cmd)}")
        try:
            log_file = tempfile.NamedTemporaryFile(
                mode="w+", prefix="goios-", suffix=".log", delete=False
            )
            process = subprocess.Popen(
                cmd,
                stdin=subprocess.PIPE, stdout=log_file, stderr=subprocess.STDOUT, text=True,
            )
            process._go_ios_log = log_file.name  # 供失败诊断回读
            log_file.close()  # 子进程已持有自身 fd，父进程副本可关闭
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

    @staticmethod
    def read_spawn_log(process: Optional[subprocess.Popen], tail_chars: int = 2000) -> str:
        """回读 _spawn 进程的输出尾部（用于失败诊断）。无则返回空串。"""
        path = getattr(process, "_go_ios_log", None) if process else None
        if not path:
            return ""
        try:
            with open(path, "r", errors="replace") as f:
                return f.read()[-tail_chars:].strip()
        except Exception:
            return ""

    @staticmethod
    def cleanup_spawn_log(process: Optional[subprocess.Popen]):
        """删除 _spawn 进程遗留的临时日志文件。"""
        path = getattr(process, "_go_ios_log", None) if process else None
        if path:
            try:
                os.remove(path)
            except Exception:
                pass

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
            logger.info(f"WDA开始启动: {udid} ({wda_bundle_id})")
        return process

    def forward(self, udid: str, local_port: int, device_port: int) -> Optional[subprocess.Popen]:
        """端口转发：ios forward <local> <device>，长驻进程。

        WDA HTTP 用 device:8100，MJPEG 用 device:9100。
        """
        process = self._spawn(["forward", str(local_port), str(device_port)], udid=udid)
        if not process:
            logger.error(f"启动端口转发失败 {udid} {local_port}->{device_port}")
        return process

    def list_apps(self, udid: str) -> List[Dict]:
        """列出可管理应用：ios apps --all（默认 JSON），仅返回用户应用，系统应用过滤掉。

        归一化为 [{id,name,version,system=False}]。
        """
        result = self._run(self._base(udid) + ["apps", "--all"], timeout=30)
        if result.returncode != 0:
            raise RuntimeError(result.stderr.strip() or "获取应用列表失败")
        try:
            raw = json.loads(result.stdout)
        except json.JSONDecodeError as e:
            raise RuntimeError(f"解析应用列表失败: {e}")
        apps = []
        for item in raw or []:
            bundle_id = item.get("CFBundleIdentifier")
            if not bundle_id:
                continue
            if item.get("ApplicationType") == "System":  # 系统应用不返回
                continue
            apps.append({
                "id": bundle_id,
                "name": item.get("CFBundleDisplayName") or item.get("CFBundleName") or bundle_id,
                "version": item.get("CFBundleShortVersionString") or item.get("CFBundleVersion") or "",
                "system": False,
            })
        apps.sort(key=lambda a: a["name"].lower())
        return apps

    def uninstall_app(self, udid: str, bundle_id: str):
        """卸载应用：ios uninstall <bundleId>。失败抛异常带输出。"""
        result = self._run(self._base(udid) + ["uninstall", bundle_id], timeout=120)
        if result.returncode != 0:
            raise RuntimeError(result.stderr.strip() or result.stdout.strip() or "卸载失败")

    def install_app(self, udid: str, ipa_path: str):
        """安装应用：ios install --path=<ipa>。失败抛异常带输出。"""
        result = self._run(self._base(udid) + ["install", f"--path={ipa_path}"], timeout=600)
        if result.returncode != 0:
            raise RuntimeError(result.stderr.strip() or result.stdout.strip() or "安装失败")
