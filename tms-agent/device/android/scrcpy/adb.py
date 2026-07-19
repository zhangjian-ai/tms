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
        elif isinstance(cmd, str):
            await self._conn.write(encode_command(cmd))

    async def write_and_check(self, cmd) -> bool:
        await self.write(cmd)
        return await self.check_okay()

    async def read_bytes(self, n: int) -> bytes:
        return await self._conn.read_bytes(n)

    async def check_okay(self):
        data = await self.read_bytes(4)
        if data == b'FAIL':
            raise RuntimeError("FAIL")
        elif data == b'OKAY':
            return True
        raise RuntimeError(f"unknown error: {data}")
