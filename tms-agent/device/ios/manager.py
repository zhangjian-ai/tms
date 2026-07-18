import asyncio
import json
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
    health_fail: int = 0  # WDA 连续健康检查失败次数
    miss_count: int = 0  # 连续未出现在 go-ios list 的次数
    occupied: bool = False  # 是否已被占用
    cast: bool = False      # 占用是否允许投屏（控制 9100 MJPEG 是否启动）
    info_reported: bool = False  # device_info 是否已上报
    recovering: bool = False  # 是否正在后台恢复 WDA
    last_seen: datetime = field(default_factory=datetime.now)


class IOSDeviceManager:
    """iOS 设备管理器"""

    # 连续多少次未在 go-ios list 中出现才判定离线
    OFFLINE_MISS_THRESHOLD = 3

    def __init__(self):
        self.config = settings["ios"]
        self.devices: Dict[str, IOSDeviceState] = {}
        self.idb = Idb()
        self.ws: Optional[websocket.WebSocketClientConnection] = None
        self._locks: Dict[str, asyncio.Lock] = {}  # 每设备串行化 start/stop_proxy
        self._idb_lock = asyncio.Lock()  # 串行化所有阻塞 go-ios 调用（见 _idb_call）

        # iOS 17+ 需要常驻 RSD 隧道（需 root）
        self.tunnel_process: Optional[subprocess.Popen] = None
        if self.config.get("wda", {}).get("auto_tunnel", True):
            self.tunnel_process = self.idb.start_tunnel()

    def _lock_for(self, udid: str) -> asyncio.Lock:
        lock = self._locks.get(udid)
        if lock is None:
            lock = asyncio.Lock()
            self._locks[udid] = lock
        return lock

    async def _idb_call(self, fn, *args):
        """串行化并移出事件循环执行 go-ios 阻塞调用。

        go-ios 命令共享 usbmuxd/RSD 隧道，并发调用会相互干扰，故用一把共享锁串行化。
        """
        async with self._idb_lock:
            return await asyncio.to_thread(fn, *args)

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

        作为后台任务运行：全程持设备锁，并在每个 await 边界复检 occupied 以免与释放竞态。
        复用同一 WDAClient 对象与端口，持有该引用的 WS handler 无需重连。
        """
        async with self._lock_for(udid):
            try:
                # 期间可能已被释放/离线
                if not (device.occupied and device.online):
                    return False

                self._terminate(device.wda_runner)
                self._terminate(device.wda_forward)
                device.wda_runner = None
                device.wda_forward = None

                # WDA 未确认已安装则不继续恢复，下轮再试
                if not await self._idb_call(self.idb.get_wda_status, udid):
                    return False
                if not (device.occupied and device.online):
                    return False

                bundle = self.config.get("wda", {}).get("wda_bundle_id", "com.facebook.WebDriverAgentRunner.xctrunner")
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
                if not (device.occupied and device.online):
                    return False
                return await device.wda_client.create_session()
            finally:
                device.recovering = False

    async def sync(self):
        """同步设备状态到服务端（3秒轮询）"""
        while True:
            await asyncio.sleep(3)

            if not self.ws:
                self.ws = await ws_client.connect()

            try:
                # 1. 设备发现
                devices = await self._idb_call(self.idb.list_devices)
                device_udids = [d["udid"] for d in devices]
                current_time = datetime.now()

                # 2. 更新在线设备
                for udid in device_udids:
                    device = self.devices.get(udid)
                    if device is None:
                        logger.info(f"发现新 iOS 设备: {udid}")
                        device = IOSDeviceState(udid=udid, online=True, last_seen=current_time)
                        self.devices[udid] = device
                    else:
                        # 本轮出现即重置缺失计数
                        device.miss_count = 0
                        device.last_seen = current_time
                        if not device.online:
                            device.online = True

                    await self.ws.write_message({"type": "status", "serial": udid, "status": "online"})

                    # 首次上报 device_info 供列表展示
                    if not device.info_reported:
                        info = await self._idb_call(self.get_device_info, udid)
                        if info:
                            await self.ws.write_message({"type": "device_info", "serial": udid, "device_info": info})
                            device.info_reported = True

                # 3. 标记离线设备（带容忍：单次缺失不立即判离线）
                for udid, device_info in self.devices.items():
                    if udid not in device_udids:
                        device_info.miss_count += 1
                        if device_info.miss_count >= self.OFFLINE_MISS_THRESHOLD:
                            device_info.online = False

                # 4. 处理设备状态（离线清理后移除；已占用设备做 WDA 会话保活）
                offline_udids = []
                for udid, device in list(self.devices.items()):
                    if not device.online:
                        # 后台恢复任务在跑时先不清理，推迟到下一轮
                        if device.recovering:
                            continue
                        # 先上报离线，再清理资源
                        await self.ws.write_message({"type": "status", "serial": udid, "status": "offline"})
                        offline_udids.append(udid)
                        await self._cleanup_device(device)
                        continue

                    # WDA 会话保活：仅对已占用设备执行，连续多次失败才恢复（不动 MJPEG 投屏）
                    if device.occupied and device.init and not device.recovering:
                        if device.wda_client and await device.wda_client.health_check():
                            device.health_fail = 0
                            continue
                        device.health_fail += 1
                        if device.health_fail < 3:
                            continue  # 瞬时不健康，暂不处理
                        device.health_fail = 0
                        logger.warning(f"WDA 连续失联，后台恢复（保留投屏）: {udid}")
                        # 后台任务恢复：_wait_wda_ready 最长 30s，不能 await 在轮询里
                        device.recovering = True
                        asyncio.create_task(self._recover_wda(udid, device))

                for udid in offline_udids:
                    self.devices.pop(udid, None)

            except Exception as e:
                logger.error(f"iOS 设备同步失败: {e}")
                if "websocket" in str(e).lower():
                    self.ws = None

    async def command_loop(self):
        """接收 backend 反向下发的指令（start_proxy / stop_proxy）。

        与 sync() 共用 self.ws：sync 只写、本循环只读。指令放独立 task 处理。
        """
        while True:
            ws = self.ws
            if not ws:
                await asyncio.sleep(1)
                continue
            try:
                msg = await ws.read_message()
            except Exception as e:
                logger.warning(f"读取后端指令失败: {e}")
                if self.ws is ws:
                    self.ws = None
                await asyncio.sleep(1)
                continue
            if msg is None:  # 连接已关闭
                if self.ws is ws:
                    self.ws = None
                await asyncio.sleep(1)
                continue
            await self._handle_command(msg)

    async def _handle_command(self, msg):
        try:
            data = json.loads(msg) if isinstance(msg, (str, bytes)) else msg
        except Exception:
            logger.warning(f"无法解析后端指令: {msg}")
            return
        mtype = data.get("type")
        udid = data.get("serial")
        if not udid:
            return
        if mtype == "start_proxy":
            asyncio.create_task(self.start_proxy(udid, bool(data.get("cast", False))))
        elif mtype == "stop_proxy":
            asyncio.create_task(self.stop_proxy(udid))

    async def start_proxy(self, udid: str, cast: bool = False):
        """占用时启动 WDA 代理。cast=True 时额外启动 9100 MJPEG 投屏转发。幂等。"""
        async with self._lock_for(udid):
            device = self.devices.get(udid)
            if device is None or not device.online:
                logger.warning(f"启动代理失败：iOS 设备不在线 {udid}")
                return
            device.cast = cast
            if device.init:
                # 已起代理：按本次 cast 调整投屏转发
                if cast and not device.mjpeg_forward:
                    await self._start_mjpeg(udid, device)
                elif not cast and device.mjpeg_forward:
                    self._terminate(device.mjpeg_forward)
                    device.mjpeg_forward = None
                    device.mjpeg_port = 0
                device.occupied = True
                return
            # 先置占用，init 失败再回退
            device.occupied = True
            if not await self._init_device(udid, device, cast=cast):
                device.occupied = False

    async def stop_proxy(self, udid: str):
        """释放时停止 WDA 代理与投屏转发。幂等。"""
        async with self._lock_for(udid):
            device = self.devices.get(udid)
            if device is None:
                return
            await self._cleanup_device(device)
            device.occupied = False
            device.cast = False
            logger.info(f"iOS 设备 {udid} 代理已停止")

    async def _start_mjpeg(self, udid: str, device: IOSDeviceState):
        """启动 9100 MJPEG 转发（投屏用），失败仅告警。"""
        mjpeg_port = Port.get("ios")
        mjpeg_forward = self.idb.forward(udid, mjpeg_port, 9100)
        if mjpeg_forward:
            device.mjpeg_forward = mjpeg_forward
            device.mjpeg_port = mjpeg_port
        else:
            logger.warning(f"启动 MJPEG 转发失败: {udid}")

    async def _wait_wda_ready(self, udid: str, device: IOSDeviceState) -> bool:
        """等待 WDA 在设备上真正就绪。"""
        timeout = int(self.config.get("wda", {}).get("wda_ready_timeout", 30))
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

    async def _init_device(self, udid: str, device: IOSDeviceState, cast: bool = False) -> bool:
        """初始化单台设备：启动 WDA、转发端口、建会话并上报。成功返回 True。

        WDA(8100)+会话总是启动；MJPEG(9100) 仅在 cast=True 时启动。
        """
        # 检查 WDA 是否已安装
        if not await self._idb_call(self.idb.get_wda_status, udid):
            logger.warning(f"设备 {udid} WDA 未就绪（未安装或暂不可达），跳过初始化")
            return False

        wda_bundle_id = self.config.get("wda", {}).get("wda_bundle_id", "com.facebook.WebDriverAgentRunner.xctrunner")

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

        # 启动 MJPEG 转发（9100）——仅投屏需要
        if cast:
            await self._start_mjpeg(udid, device)

        # 创建 WDA 客户端（经转发口）
        device.wda_client = WDAClient("127.0.0.1", local_port)

        # 等待 WDA 在设备上就绪
        if not await self._wait_wda_ready(udid, device):
            await self._cleanup_device(device)
            return False

        if not await device.wda_client.create_session():
            logger.error(f"创建 WDA 会话失败: {udid}")
            await self._cleanup_device(device)
            return False

        # 上报设备信息（屏幕尺寸经 WDA 获取）
        device_info = await self._idb_call(self.get_device_info, udid)
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
