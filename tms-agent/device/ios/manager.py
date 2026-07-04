import asyncio
import subprocess
from typing import Dict, Optional
from logzero import logger
from tornado import websocket
from datetime import datetime
from dataclasses import dataclass, field

from utils.network import Host, Port
from utils.server import ws_client
from utils.variables import settings
from device.ios.tools.idb import Idb
from device.ios.tools.client import WDAClient


@dataclass
class IOSDeviceState:
    """iOS 设备状态信息"""
    udid: str
    online: bool = False
    init: bool = False
    wda_runner: subprocess.Popen = None   # go-ios runwda 进程
    wda_forward: subprocess.Popen = None  # 8100 端口转发进程
    wda_port: int = 0
    mjpeg_forward: subprocess.Popen = None  # 9100 端口转发进程
    mjpeg_port: int = 0
    wda_client: WDAClient = None
    health_fail: int = 0  # WDA 连续健康检查失败次数（容忍瞬时繁忙，避免误杀投屏）
    miss_count: int = 0  # 设备连续未出现在 go-ios list 的次数（容忍瞬时查询失败，避免误判离线）
    last_seen: datetime = field(default_factory=datetime.now)
    error: str = ""


class IOSDeviceManager:
    """iOS 设备管理器（go-ios，支持 iOS 17+）"""

    # 连续多少次未在 go-ios list 中出现才判定离线（3s/次，约 9s 容忍瞬时查询失败）
    OFFLINE_MISS_THRESHOLD = 3

    def __init__(self):
        self.config = settings["ios"]
        self.devices: Dict[str, IOSDeviceState] = {}
        self.idb = Idb()
        self.ws: websocket.WebSocketClientConnection = ...

        # iOS 17+ 需要常驻 RSD 隧道（需 root）。可在 settings.ios.auto_tunnel=false 由外部守护
        self.tunnel_process: Optional[subprocess.Popen] = None
        if self.config.get("auto_tunnel", True):
            self.tunnel_process = self.idb.start_tunnel()

    @staticmethod
    def _terminate(process: Optional[subprocess.Popen]):
        """安全终止子进程"""
        if process:
            try:
                process.terminate()
            except Exception:
                pass

    async def _cleanup_device(self, device: IOSDeviceState):
        """释放设备占用的 WDA / 转发 / 会话资源"""
        self._terminate(device.wda_runner)
        self._terminate(device.wda_forward)
        self._terminate(device.mjpeg_forward)
        device.wda_runner = None
        device.wda_forward = None
        device.mjpeg_forward = None
        if device.wda_client:
            await device.wda_client.close()
            device.wda_client = None
        device.wda_port = 0
        device.mjpeg_port = 0
        device.init = False

    async def _recover_wda(self, udid: str, device: IOSDeviceState) -> bool:
        """仅恢复 WDA（runwda + 8100 转发 + 会话），保留 MJPEG 投屏不受影响。

        复用同一 WDAClient 对象与端口：session_id 原地刷新，持有该引用的控制/检查器
        WS handler 无需重连即可继续使用。
        """
        self._terminate(device.wda_runner)
        self._terminate(device.wda_forward)
        device.wda_runner = None
        device.wda_forward = None

        # 状态未知或确未安装都不继续恢复（未知则下轮重试）
        if self.idb.get_wda_status(udid) is not True:
            return False

        bundle = self.config.get("wda_bundle_id", "com.facebook.WebDriverAgentRunner.xctrunner")
        runner = self.idb.start_wda(udid, bundle)
        if not runner:
            return False
        device.wda_runner = runner

        port = device.wda_port or Port.get("ios")
        fwd = self.idb.forward(udid, port, 8100)
        if not fwd:
            return False
        device.wda_forward = fwd
        device.wda_port = port

        if device.wda_client is None:
            device.wda_client = WDAClient("127.0.0.1", port)

        if not await self._wait_wda_ready(udid, device):
            return False
        return await device.wda_client.create_session()

    async def sync(self):
        """同步设备状态到服务端（3秒轮询）"""
        while True:
            await asyncio.sleep(3)

            if not self.ws or isinstance(self.ws, Ellipsis.__class__):
                self.ws = await ws_client.connect()

            try:
                # 1. 设备发现
                devices = self.idb.list_devices()
                device_udids = [d["udid"] for d in devices]
                current_time = datetime.now()

                # 2. 更新在线设备
                for udid in device_udids:
                    if udid not in self.devices:
                        logger.info(f"发现新 iOS 设备: {udid}")
                        self.devices[udid] = IOSDeviceState(udid=udid, online=True, last_seen=current_time)
                    else:
                        # 本轮出现即重置缺失计数（容忍瞬时 list 失败）
                        self.devices[udid].miss_count = 0
                        self.devices[udid].last_seen = current_time
                        if not self.devices[udid].online:
                            self.devices[udid].online = True

                    await self.ws.write_message({"type": "status", "serial": udid, "status": "online"})

                # 3. 标记离线设备（带容忍：go-ios list 在设备繁忙/投屏时可能瞬时查不到，
                #    单次缺失不立即判离线，避免误清理正在使用的设备并被重新识别）
                for udid, device_info in self.devices.items():
                    if udid not in device_udids:
                        device_info.miss_count += 1
                        if device_info.miss_count >= self.OFFLINE_MISS_THRESHOLD:
                            device_info.online = False

                # 4. 处理设备状态（离线设备清理后从字典移除，避免长跑泄漏）
                offline_udids = []
                for udid, device in list(self.devices.items()):
                    if not device.online:
                        # 先可靠上报离线，再清理资源，避免清理异常导致漏报
                        await self.ws.write_message({"type": "status", "serial": udid, "status": "offline"})
                        offline_udids.append(udid)
                        await self._cleanup_device(device)
                        continue

                    # WDA 会话保活：容忍瞬时失败（如 Dump XML 期间 WDA 繁忙导致 /status 超时），
                    # 连续多次失败才恢复；且只恢复 WDA，不动 MJPEG 投屏（8100 与 9100 相互独立）
                    if device.init:
                        if device.wda_client and await device.wda_client.health_check():
                            device.health_fail = 0
                            continue
                        device.health_fail += 1
                        if device.health_fail < 3:
                            continue  # 瞬时不健康，暂不处理，避免误杀投屏
                        device.health_fail = 0
                        logger.warning(f"WDA 连续失联，仅恢复 WDA（保留投屏）: {udid}")
                        await self._recover_wda(udid, device)
                        continue

                    # 5. 设备初始化 - 启动 WDA + 端口转发
                    if not await self._init_device(udid, device):
                        continue

                for udid in offline_udids:
                    self.devices.pop(udid, None)

            except Exception as e:
                logger.error(f"iOS 设备同步失败: {e}")
                if "websocket" in str(e).lower():
                    self.ws = None

    async def _wait_wda_ready(self, udid: str, device: IOSDeviceState) -> bool:
        """等待 WDA 在设备上真正就绪。

        - runwda 进程中途退出 → WDA 未在设备上运行，立即失败（触发重试）
        - 直到 health_check 通过或超过 wda_ready_timeout（WDA 冷启动较慢，窗口需足够）
        """
        timeout = int(self.config.get("wda_ready_timeout", 30))
        waited = 0
        while waited < timeout:
            await asyncio.sleep(2)
            waited += 2

            runner = device.wda_runner
            if runner is not None and runner.poll() is not None:
                logger.error(f"runwda 进程已退出(code={runner.returncode})，WDA 未在设备上运行: {udid}")
                return False

            if device.wda_client and await device.wda_client.health_check():
                return True
            logger.debug(f"等待 WDA 就绪... {waited}/{timeout}s ({udid})")

        logger.error(f"WDA 就绪超时({timeout}s): {udid}")
        return False

    async def _init_device(self, udid: str, device: IOSDeviceState) -> bool:
        """初始化单台设备：启动 WDA、转发端口、建会话并上报。成功返回 True。"""
        # 检查 WDA 是否已安装（需提前由 Xcode 安装到设备）
        wda_status = self.idb.get_wda_status(udid)
        if wda_status is None:
            # 状态未知（命令失败/设备繁忙），本轮不判定，稍后重试，避免误报"未安装"
            logger.debug(f"设备 {udid} WDA 安装状态暂不可判定，稍后重试")
            return False
        if wda_status is False:
            logger.warning(f"设备 {udid} 未安装 WDA，跳过初始化")
            device.error = "WDA not installed"
            return False

        wda_bundle_id = self.config.get("wda_bundle_id", "com.facebook.WebDriverAgentRunner.xctrunner")

        # 启动 WDA + 转发 8100
        wda_runner = self.idb.start_wda(udid, wda_bundle_id)
        if not wda_runner:
            return False
        device.wda_runner = wda_runner

        local_port = Port.get("ios")
        wda_forward = self.idb.forward(udid, local_port, 8100)
        if not wda_forward:
            await self._cleanup_device(device)
            return False
        device.wda_forward = wda_forward
        device.wda_port = local_port

        # 启动 MJPEG 转发（9100），用于投屏
        mjpeg_port = Port.get("ios")
        mjpeg_forward = self.idb.forward(udid, mjpeg_port, 9100)
        if mjpeg_forward:
            device.mjpeg_forward = mjpeg_forward
            device.mjpeg_port = mjpeg_port
        else:
            logger.warning(f"启动 MJPEG 转发失败: {udid}")

        # 创建 WDA 客户端（经转发口）
        device.wda_client = WDAClient("127.0.0.1", local_port)

        # 等待 WDA 在设备上真正就绪（检测 runwda 中途退出 + 足够长的冷启动窗口）
        if not await self._wait_wda_ready(udid, device):
            await self._cleanup_device(device)
            return False

        if not await device.wda_client.create_session():
            logger.error(f"创建 WDA 会话失败: {udid}")
            await self._cleanup_device(device)
            return False

        # 上报设备信息（屏幕尺寸经 WDA 获取）
        device_info = self.get_device_info(udid)
        if device_info:
            size = await device.wda_client.get_window_size()
            if size:
                device_info["width"], device_info["height"] = size[0], size[1]
            await self.ws.write_message({"type": "device_info", "serial": udid, "device_info": device_info})

        # 上报连接信息
        await self.ws.write_message({
            "type": "connection_info", "serial": udid,
            "connection_info": self.get_connection_info(device)
        })

        device.init = True
        return True

    def get_device_info(self, udid: str) -> Optional[Dict]:
        """获取设备基础信息"""
        try:
            info = self.idb.get_device_info(udid)
            if not info:
                return None

            return {
                "name": info.get("DeviceName", udid),
                "serial": udid,
                "brand": "Apple",
                "model": info.get("ProductType", "iPhone"),
                "device_sys": "ios",
                "os_version": info.get("ProductVersion", "Unknown"),
                "width": 0,  # 由 WDA 获取
                "height": 0
            }
        except Exception as e:
            logger.error(f"获取 iOS 设备信息失败 {udid}: {e}")
            return None

    def get_connection_info(self, device: IOSDeviceState):
        """获取连接信息"""
        try:
            host = Host.get()
            return {
                "adb_host": host,
                "adb_port": device.wda_port,
                "proxy_host": host,
                "proxy_port": self.config["proxy"]["port"],
                "connection": f"{host}:{device.wda_port}"
            }
        except Exception as e:
            logger.error(f"获取 iOS 连接信息失败 {device.udid}: {e}")
            return None
