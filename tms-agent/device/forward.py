"""设备额外端口转发能力（内聚到各平台的代理服务中）

链路：客户端 → agent(0.0.0.0:proxy_port 中继) → 127.0.0.1:local_port(adb/go-ios 转发) → 设备:device_port

- Android：adb forward 把设备端口转到本机 localhost；
- iOS：go-ios forward 只能绑定 127.0.0.1，故统一再叠加一层 0.0.0.0 中继暴露到 LAN。

每个平台的设备管理器各持有一个本类实例（platform 固定），HTTP 接口 /api/forward
由各自的代理服务（Android 8000 / iOS 8001）挂载提供；platform 由所在服务隐含。
"""
import json
import asyncio
import subprocess

import tornado.web
from logzero import logger

from utils.network import Host, Port
from device.android.tools.adb import adb_cmd


class PortForwardManager:
    """单平台端口转发管理器。

    以 (serial, device_port) 为键做幂等：同一设备同一端口重复请求返回同一代理端口。
    每条转发 = 一次 adb/go-ios 设备转发(→127.0.0.1:local) + 一个 0.0.0.0:proxy 中继。
    """

    def __init__(self, platform: str, device_manager):
        self.platform = platform  # "android" | "ios"
        self.device_manager = device_manager
        self._forwards = {}  # (serial, device_port) -> entry(dict)
        self._lock = asyncio.Lock()

    def _device_online(self, serial: str) -> bool:
        device = self.device_manager.devices.get(serial)
        return bool(device and device.online)

    async def add_forward(self, serial: str, device_port) -> dict:
        """建立(或复用)一条端口转发，返回代理连接信息。"""
        if not serial:
            raise ValueError("serial 不能为空")
        try:
            device_port = int(device_port)
        except (TypeError, ValueError):
            raise ValueError(f"非法设备端口: {device_port}")
        if not (0 < device_port < 65536):
            raise ValueError(f"非法设备端口: {device_port}")
        if not self._device_online(serial):
            raise ValueError(f"设备不在线或未接入: {serial}")

        key = (serial, device_port)
        async with self._lock:
            # 幂等：已存在且存活则直接复用
            existing = self._forwards.get(key)
            if existing and self._alive(existing):
                return self._result(existing)
            if existing:  # 已失效，先拆除旧记录
                await self._teardown(existing)
                self._forwards.pop(key, None)

            # 1) 用现有能力把设备端口转到本机 127.0.0.1:local_port
            local_port = Port.get(self.platform)
            process = None
            if self.platform == "android":
                await asyncio.to_thread(self._android_forward, serial, local_port, device_port)
            else:
                process = await asyncio.to_thread(self.device_manager.idb.forward, serial, local_port, device_port)
                if not process:
                    raise RuntimeError("go-ios 端口转发启动失败")

            # 2) 叠加 0.0.0.0 中继，暴露到 LAN
            proxy_port = Port.get(self.platform)
            try:
                relay = await self._start_relay("0.0.0.0", proxy_port, "127.0.0.1", local_port)
            except Exception:
                # 中继失败，回收已建立的设备转发，避免泄漏
                if self.platform == "android":
                    await asyncio.to_thread(self._android_unforward, serial, local_port)
                elif process:
                    self._terminate(process)
                raise

            entry = {
                "serial": serial, "device_port": device_port,
                "local_port": local_port, "proxy_port": proxy_port,
                "process": process, "relay": relay,
            }
            self._forwards[key] = entry

        logger.info(f"端口转发已建立 {self.platform} {serial} device:{device_port} -> {Host.get()}:{proxy_port}")
        return self._result(entry)

    async def remove_forwards(self, serial: str) -> int:
        """清理某序列号名下所有额外端口转发（接口删除 / 设备下线时调用）。返回清理条数。"""
        async with self._lock:
            keys = [k for k in self._forwards if k[0] == serial]
            for key in keys:
                await self._teardown(self._forwards.pop(key))
        if keys:
            logger.info(f"已清理 {self.platform} {serial} 的 {len(keys)} 条额外端口转发")
        return len(keys)

    def list_forwards(self) -> list:
        return [self._result(entry) for entry in self._forwards.values()]

    # ---- 具体转发实现 ----

    @staticmethod
    def _android_forward(serial: str, local_port: int, device_port: int):
        cmd = adb_cmd("forward", f"tcp:{local_port}", f"tcp:{device_port}", serial=serial)
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
        if result.returncode != 0:
            raise RuntimeError(f"adb forward 失败: {result.stderr.strip()}")

    @staticmethod
    def _android_unforward(serial: str, local_port: int):
        try:
            cmd = adb_cmd("forward", "--remove", f"tcp:{local_port}", serial=serial)
            subprocess.run(cmd, capture_output=True, text=True, timeout=10)
        except Exception:
            pass

    async def _start_relay(self, listen_host: str, listen_port: int, target_host: str, target_port: int):
        """启动 0.0.0.0 TCP 中继，转发到本机的 adb/go-ios 转发端口。"""
        async def handle(reader, writer):
            try:
                t_reader, t_writer = await asyncio.open_connection(target_host, target_port)
            except Exception as e:
                logger.warning(f"中继连接目标失败 {target_host}:{target_port}: {e}")
                try:
                    writer.close()
                except Exception:
                    pass
                return
            await asyncio.gather(
                self._pipe(reader, t_writer),
                self._pipe(t_reader, writer),
            )

        return await asyncio.start_server(handle, listen_host, listen_port)

    @staticmethod
    async def _pipe(reader, writer):
        try:
            while True:
                data = await reader.read(65536)
                if not data:
                    break
                writer.write(data)
                await writer.drain()
        except Exception:
            pass
        finally:
            try:
                writer.close()
            except Exception:
                pass

    @staticmethod
    def _terminate(process):
        try:
            process.terminate()
        except Exception:
            pass

    @staticmethod
    def _alive(entry) -> bool:
        process = entry.get("process")
        if process is not None and process.poll() is not None:
            return False
        relay = entry.get("relay")
        if relay is not None and not relay.is_serving():
            return False
        return True

    async def _teardown(self, entry):
        relay = entry.get("relay")
        if relay is not None:
            try:
                relay.close()
                await relay.wait_closed()
            except Exception:
                pass
        process = entry.get("process")
        if process is not None:
            self._terminate(process)
        if self.platform == "android":
            await asyncio.to_thread(self._android_unforward, entry["serial"], entry["local_port"])

    def _result(self, entry) -> dict:
        host = Host.get()
        proxy_port = entry["proxy_port"]
        return {
            "platform": self.platform,
            "serial": entry["serial"],
            "device_port": entry["device_port"],
            "proxy_host": host,
            "proxy_port": proxy_port,
            "connection": f"{host}:{proxy_port}",
        }


class ForwardHandler(tornado.web.RequestHandler):
    """端口转发 HTTP 接口（挂载在各平台代理服务上，platform 由所在服务隐含）。

    POST   /api/forward  body: {serial, port}   建立/复用转发
    GET    /api/forward                          列出本平台全部转发
    DELETE /api/forward  body: {serial}          删除某设备的全部转发
    """

    def initialize(self, forward_manager=None):
        self.forward_manager = forward_manager

    def set_default_headers(self):
        self.set_header("Content-Type", "application/json")
        self.set_header("Access-Control-Allow-Origin", "*")
        self.set_header("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
        self.set_header("Access-Control-Allow-Headers", "Content-Type")

    def options(self):
        self.set_status(204)
        self.finish()

    def _ok(self, data):
        self.finish(json.dumps({"code": 0, "data": data}))

    def _err(self, status, message):
        self.set_status(status)
        self.finish(json.dumps({"code": 1, "message": message}))

    def _body(self) -> dict:
        if not self.request.body:
            return {}
        return json.loads(self.request.body)

    async def post(self):
        if not self.forward_manager:
            return self._err(503, "转发服务未就绪")
        try:
            body = self._body()
        except Exception:
            return self._err(400, "请求体不是合法 JSON")
        try:
            data = await self.forward_manager.add_forward(body.get("serial"), body.get("port"))
            self._ok(data)
        except ValueError as e:
            self._err(400, str(e))
        except Exception as e:
            logger.error(f"建立端口转发失败: {e}")
            self._err(500, str(e))

    def get(self):
        if not self.forward_manager:
            return self._err(503, "转发服务未就绪")
        self._ok(self.forward_manager.list_forwards())

    async def delete(self):
        if not self.forward_manager:
            return self._err(503, "转发服务未就绪")
        try:
            body = self._body()
        except Exception:
            return self._err(400, "请求体不是合法 JSON")
        serial = body.get("serial")
        if not serial:
            return self._err(400, "serial 不能为空")
        removed = await self.forward_manager.remove_forwards(serial)
        self._ok({"serial": serial, "removed": removed})
