from logzero import logger
from typing import List, Dict
from tornado import tcpclient

from device.android.tools.adb import get_adb_config, encode_command


class AdbClient:
    def __init__(self, stream):
        self._conn = stream

    @classmethod
    async def connect(cls, host=None, port=None):
        cfg = get_adb_config()
        host = host or cfg["host"]
        port = port or cfg["port"]

        s = await cls.connect_adb(host, port)
        return cls(s)

    @staticmethod
    async def connect_adb(host="127.0.0.1", port=5037):
        sock = await tcpclient.TCPClient().connect(host, port)
        sock.set_nodelay(True)
        return sock

    def disconnect(self):
        if self._conn:
            self._conn.close()
            self._conn = None

    def __del__(self):
        self.disconnect()

    async def write(self, cmd):
        if isinstance(cmd, bytes):
            await self._conn.write(cmd)
        if isinstance(cmd, str):
            await self._conn.write(encode_command(cmd))

    async def write_and_check(self, cmd) -> bool:
        await self.write(cmd)
        return await self.check_okay()

    async def read(self) -> str:
        num = int((await self.read_bytes(4)).decode("utf8"), 16)
        return (await self.read_bytes(num)).decode("utf8")

    async def read_bytes(self, n: int) -> bytes:
        return await self._conn.read_bytes(n)

    async def read_bytes_until(self, delimiter: bytes, max_bytes: int = None) -> bytes:
        """读取字节直到遇到指定的分隔符"""
        if max_bytes is not None:
            return await self._conn.read_until(delimiter=delimiter, max_bytes=max_bytes)
        else:
            return await self._conn.read_until(delimiter=delimiter)

    async def read_utils(self, delimiter: str, max_bytes: int) -> bytes:
        return await self._conn.read_until(delimiter=delimiter.encode("utf8"), max_bytes=max_bytes)

    async def check_okay(self):
        data = await self.read_bytes(4)
        if data == b'FAIL':
            raise RuntimeError("FAIL")
        elif data == b'OKAY':
            return True
        raise RuntimeError(f"unknown error: {data}")

    async def version(self):
        await self.write_and_check("host:version")
        return await self.read()

    async def devices(self):
        await self.write_and_check("host:devices")
        return await self.read()

    async def forward(self, serial, local_port, remote_port, norebind: bool = False):
        cmd = "forward"
        if norebind:
            cmd += ":norebind"
        cmd += f':tcp:{local_port};tcp:{remote_port}'
        await self.write_and_check(f"host-serial:{serial}:{cmd}")

    async def forward_remove(self, serial, local_port):
        await self.write_and_check(f"host:tport:serial:{serial}")
        await self.write(f"host:killforward:tcp:{local_port}")

    async def open_transport(self, serial: str):
        await self.write_and_check("host:transport:" + serial)

    async def open_tcp(self, serial: str, port: int):
        await self.open_transport(serial)
        await self.write_and_check(f"tcp:{port}")

    async def shell(self, serial: str, command: str) -> str:
        await self.open_transport(serial)
        cmd = f"shell:{command}"
        await self.write_and_check(cmd)
        return await self.read()

    async def device_list(self) -> List[Dict[str, str]]:
        """获取设备列表"""
        devices_output = await self.devices()
        devices = []

        for line in devices_output.strip().split('\n'):
            if line.strip():
                parts = line.split('\t')
                if len(parts) >= 2:
                    serial = parts[0]
                    state = parts[1]
                    devices.append({
                        "serial": serial,
                        "state": state
                    })

        return devices

    async def push(self, serial: str, local_path: str, remote_path: str) -> bool:
        """推送文件到设备"""
        try:
            await self.open_transport(serial)

            sync_cmd = f"sync:"
            await self.write_and_check(sync_cmd)

            send_cmd = f"SEND{len(remote_path):04x}{remote_path}"
            await self._conn.write(send_cmd.encode())

            with open(local_path, 'rb') as f:
                while True:
                    chunk = f.read(65536)
                    if not chunk:
                        break
                    await self._conn.write(f"DATA{len(chunk):04x}".encode())
                    await self._conn.write(chunk)

            import time
            mtime = int(time.time())
            await self._conn.write(f"DONE{mtime:08x}".encode())

            response = await self.read_bytes(8)
            if response[:4] == b"OKAY":
                return True
            else:
                return False

        except Exception as e:
            logger.error(f"推送文件失败: {e}")
            return False

    async def install(self, serial: str, apk_path: str) -> bool:
        """安装APK"""
        try:
            result = await self.shell(serial, f"pm install -r {apk_path}")
            return "Success" in result
        except Exception as e:
            logger.error(f"安装APK失败: {e}")
            return False
