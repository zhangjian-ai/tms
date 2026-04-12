import asyncio
import struct

from device.android.tcp2usb.proto import Protocol


class AdbStreamReader:
    """A数据流读取，封装一下便于读取ADB协议数据"""

    def __init__(self, reader: asyncio.StreamReader):
        self.reader = reader
        self.ended = False

    async def read_ascii(self, length: int) -> str:
        """读取ASCII字符串"""
        data = await self.read_bytes(length)
        return data.decode('ascii')

    async def read_bytes(self, how_many: int) -> bytes:
        """读取指定字节数"""
        if how_many == 0:
            return b''

        if self.ended:
            raise Exception(f"Parser ended, cannot read {how_many} bytes")

        try:
            data = await self.reader.readexactly(how_many)
            if len(data) < how_many:
                raise Exception(f"Connection closed: got {len(data)} bytes, expected {how_many}")
            return data
        except asyncio.IncompleteReadError as e:
            raise Exception(f"Connection closed: {e.partial!r} ({len(e.partial)} bytes read, {how_many} expected)")

    async def read_error(self) -> str:
        """读取错误信息"""
        try:
            # 读取4字节长度前缀
            length_data = await self.read_bytes(4)
            length = struct.unpack('<I', length_data)[0]

            if length > 0:
                error_data = await self.read_bytes(length)
                return error_data.decode('utf-8', errors='ignore')
            return "Unknown error"
        except Exception:
            return "Failed to read error message"

    async def read_value(self) -> bytes:
        """读取带长度前缀的值"""
        length_str = await self.read_ascii(4)
        length = Protocol.decode_length(length_str)
        return await self.read_bytes(length)

    async def end(self):
        """结束解析器"""
        self.ended = True


class AdbConnection:
    """连接ADB服务"""

    def __init__(self, host: str, port: int):
        self.host = host
        self.port = port
        self.reader = None   # 原生 asyncio.StreamReader，用于直接读取原始数据
        self.parser = None   # AdbStreamReader，用于ADB协议解析
        self.writer = None
        self.ended = False

    async def connect(self):
        """建立连接"""
        try:
            self.reader, self.writer = await asyncio.open_connection(self.host, self.port)
            self.parser = AdbStreamReader(self.reader)
            return self
        except Exception as e:
            raise Exception(f"Failed to connect to ADB server at {self.host}:{self.port}: {e}")

    def write(self, data: bytes):
        """写入数据"""
        if self.writer and not self.ended:
            self.writer.write(data)
        return self

    async def drain(self):
        """确保数据发送"""
        if self.writer and not self.ended:
            try:
                # 增加超时时间，支持大文件传输如adb install
                await asyncio.wait_for(self.writer.drain(), timeout=30.0)
            except asyncio.TimeoutError:
                raise Exception("Connection drain timeout - possibly large data transfer")

    async def close(self):
        """关闭连接"""
        if self.ended:
            return

        self.ended = True

        if self.parser:
            await self.parser.end()

        if self.writer:
            self.writer.close()
            try:
                await self.writer.wait_closed()
            except Exception:
                pass


class AdbClient:
    """ADB客户端，提供设备连接和属性查询功能"""

    def __init__(self, host: str, port: int):
        self.host = host
        self.port = port

    async def get_transport(self, serial: str) -> AdbConnection:
        """获取到设备的传输连接"""
        # 为每个设备连接创建独立的ADB连接
        conn = AdbConnection(self.host, self.port)
        await conn.connect()

        # 发送transport命令切换到指定设备
        command = f"host:transport:{serial}"
        encoded = Protocol.encode_data(command)
        conn.write(encoded)
        await conn.drain()

        # 读取ADB服务器响应
        try:
            reply = await conn.parser.read_ascii(4)
        except Exception as e:
            await conn.close()
            raise Exception(f"Failed to read transport response for {serial}: {e}")

        if reply == Protocol.OKAY.decode('ascii'):
            return conn
        elif reply == Protocol.FAIL.decode('ascii'):
            error_msg = await conn.parser.read_error()
            await conn.close()

            # 根据错误类型提供具体的错误信息
            if "device not found" in error_msg.lower():
                raise Exception(f"Device {serial} not found. Please check if device is connected and authorized.")
            elif "offline" in error_msg.lower():
                raise Exception(f"Device {serial} is offline. Please check device connection.")
            else:
                raise Exception(f"Transport failed for {serial}: {error_msg}")
        else:
            await conn.close()
            raise Exception(f"Unexpected reply: {reply}, expected OKAY or FAIL")

    async def get_device_properties(self, serial: str) -> dict:
        """
        获取设备属性（用于生成设备ID）
        
        通过执行 shell:getprop 命令获取设备的真实属性
        """
        conn = None
        try:
            # 获取到设备的传输连接
            conn = await self.get_transport(serial)
            
            # 发送 shell 命令获取属性
            command = "shell:getprop"
            encoded = Protocol.encode_data(command)
            conn.write(encoded)
            await conn.drain()
            
            # 读取响应
            reply = await conn.parser.read_ascii(4)
            
            if reply == Protocol.OKAY.decode('ascii'):
                # 读取所有输出数据
                # 使用原生的 StreamReader.read()，因为我们不知道确切的字节数
                output = b''
                try:
                    while True:
                        chunk = await asyncio.wait_for(
                            conn.reader.read(4096),
                            timeout=0.5
                        )
                        if not chunk:
                            break
                        output += chunk
                except asyncio.TimeoutError:
                    # 超时表示数据读取完毕
                    pass
                except Exception:
                    # 读取结束
                    pass
                
                # 解析属性
                properties = self._parse_properties(output.decode('utf-8', errors='ignore'))
                
                # 确保返回必要的属性，如果不存在则使用默认值
                result = {
                    'ro.product.brand': properties.get('ro.product.brand', 'android'),
                    'ro.product.model': properties.get('ro.product.model', 'device'),
                    'ro.product.device': properties.get('ro.product.device', 'generic')
                }
                
                return result
                
            elif reply == Protocol.FAIL.decode('ascii'):
                error_msg = await conn.parser.read_error()
                raise Exception(f"Failed to get properties: {error_msg}")
            else:
                raise Exception(f"Unexpected reply: {reply}")
                
        except Exception as e:
            # 发生错误时返回默认值
            return {
                'ro.product.brand': 'android',
                'ro.product.model': 'device',
                'ro.product.device': 'generic'
            }
        finally:
            if conn:
                await conn.close()
    
    def _parse_properties(self, output: str) -> dict:
        """
        解析 getprop 命令输出
        
        格式示例：
        [ro.product.name]: [sdk_phone_x86]
        [ro.product.model]: [Android SDK built for x86]
        """
        properties = {}
        
        for line in output.split('\n'):
            line = line.strip()
            if not line:
                continue
            
            # 解析格式: [key]: [value]
            if line.startswith('[') and ']: [' in line:
                try:
                    # 提取 key 和 value
                    key_end = line.index(']')
                    key = line[1:key_end]
                    
                    value_start = line.index(']: [') + 4
                    value_end = line.rindex(']')
                    value = line[value_start:value_end]
                    
                    properties[key] = value
                except (ValueError, IndexError):
                    # 解析失败，跳过该行
                    continue
        
        return properties
