import asyncio
import struct

from device.android.tools.adb import encode_command, decode_length


class Protocol:
    """ADB协议常量和工具函数"""

    OKAY = b'OKAY'
    FAIL = b'FAIL'

    @staticmethod
    def encode_data(data: str) -> bytes:
        """编码数据为ADB格式: 4字节十六进制长度 + 数据"""
        return encode_command(data) if isinstance(data, str) else data

    @staticmethod
    def decode_length(length_str: str) -> int:
        """解码长度字符串为整数"""
        return decode_length(length_str)


class Packet:
    """ADB数据包"""

    # ADB Commands
    A_SYNC = 0x434e5953
    A_CNXN = 0x4e584e43
    A_OPEN = 0x4e45504f
    A_OKAY = 0x59414b4f
    A_CLSE = 0x45534c43
    A_WRTE = 0x45545257
    A_AUTH = 0x48545541

    def __init__(self, command: int, arg0: int, arg1: int, length: int, check: int, magic: int, data: bytes = b''):
        self.command = command
        self.arg0 = arg0
        self.arg1 = arg1
        self.length = length
        self.check = check
        self.magic = magic
        self.data = data

    @classmethod
    def checksum(cls, data: bytes) -> int:
        """计算校验和"""
        result = 0
        for byte in data:
            result += byte
        return result & 0xFFFFFFFF

    @classmethod
    def magic(cls, command: int) -> int:
        """计算魔数"""
        return command ^ 0xFFFFFFFF

    @classmethod
    def swap32(cls, n: int) -> int:
        """32位字节序转换"""
        return struct.unpack('>I', struct.pack('<I', n))[0]

    @classmethod
    def assemble(cls, command: int, arg0: int, arg1: int, data: bytes = None) -> bytes:
        """组装数据包"""
        if data is None:
            data = b''

        length = len(data)
        check = cls.checksum(data)
        magic = cls.magic(command)

        # 24字节头部 + 数据
        header = struct.pack('<IIIIII', command, arg0, arg1, length, check, magic)
        return header + data

    def verify_checksum(self) -> bool:
        """验证校验和"""
        return self.check == self.checksum(self.data)

    def verify_magic(self) -> bool:
        """验证魔数"""
        return self.magic == self.__class__.magic(self.command)


class PacketReader:
    """数据包读取器"""

    def __init__(self, stream: asyncio.StreamReader):
        self.stream = stream
        self.buffer = b''
        self.current_packet = None
        self.in_body = False

    async def read_packet(self) -> Packet:
        """读取完整的数据包（支持大数据包，如长文件路径）"""
        while True:
            # 先尝试从已有 buffer 解析，只有 buffer 不够时才从 stream 读取新数据
            packet = self._try_parse()
            if packet is not None:
                return packet

            try:
                if self.in_body and self.current_packet:
                    needed = self.current_packet.length - len(self.current_packet.data) if hasattr(self.current_packet, 'data') else self.current_packet.length
                    read_size = max(4096, min(needed + 1024, 65536))
                else:
                    read_size = 4096

                chunk = await self.stream.read(read_size)
                if not chunk:
                    return None
                self.buffer += chunk
            except Exception:
                return None

    def _try_parse(self):
        """尝试从 buffer 中解析一个完整的包，解析成功返回 Packet，数据不够返回 None"""
        if not self.in_body:
            if len(self.buffer) < 24:
                return None

            header = self.buffer[:24]
            command, arg0, arg1, length, check, magic = struct.unpack('<IIIIII', header)
            self.current_packet = Packet(command, arg0, arg1, length, check, magic, b'')

            if not self.current_packet.verify_magic():
                raise ValueError(f"Magic value mismatch")

            self.buffer = self.buffer[24:]

            if length == 0:
                packet = self.current_packet
                self.current_packet = None
                return packet
            else:
                self.in_body = True
                # fall through to body parsing

        # 读取包体
        if len(self.buffer) < self.current_packet.length:
            return None

        self.current_packet.data = self.buffer[:self.current_packet.length]
        self.buffer = self.buffer[self.current_packet.length:]

        if not self.current_packet.verify_checksum():
            raise ValueError("Checksum mismatch")

        packet = self.current_packet
        self.current_packet = None
        self.in_body = False
        return packet
