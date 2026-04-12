"""
TCP-USB Proxy Server
"""
import socket
import asyncio
import secrets

from logzero import logger

from .proto import Packet, PacketReader, Protocol


class TcpProxyServer:

    def __init__(self, client, serial: str, bypass_auth: bool = True, max_connections: int = 10, device_id: bytes = None):
        self.client = client
        self.serial = serial
        self.bypass_auth = bypass_auth
        self.max_connections = max_connections
        self.server = None
        self.connections = []
        self.device_id = device_id

    async def listen(self, host: str, port: int):
        """开始监听"""
        self.server = await asyncio.start_server(
            self._handle_connection,
            host,
            port,
            family=socket.AF_INET
        )

        # 等待服务器运行
        async with self.server:
            await self.server.serve_forever()

    async def _handle_connection(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
        """处理新连接"""
        if len(self.connections) >= self.max_connections:
            oldest_connection = self.connections[0]
            await self._close_connection(oldest_connection)
            logger.info(f"连接数达到最大值 {self.max_connections}，关闭最早的连接")

        client_socket = TcpForward(self.client, self.serial, reader, writer, self.bypass_auth, self.device_id)
        self.connections.append(client_socket)

        client_address = writer.get_extra_info('peername')
        logger.info(f"新连接 {client_address}，当前连接数: {len(self.connections)}/{self.max_connections}")

        try:
            await client_socket.start()
        except Exception as e:
            logger.error(f"连接处理异常: {e}", exc_info=True)
        finally:
            await self._close_connection(client_socket)

    async def _close_connection(self, client_socket):
        """关闭指定的连接"""
        try:
            if client_socket in self.connections:
                self.connections.remove(client_socket)

            await client_socket.end()
            logger.info(f"连接已关闭，当前连接数: {len(self.connections)}/{self.max_connections}")
        except Exception as e:
            logger.error(f"关闭连接时出错: {e}")

    async def stop(self):
        """停止服务器"""
        if self.server:
            self.server.close()
            await self.server.wait_closed()

        for conn in self.connections[:]:
            await conn.end()
        self.connections.clear()

    def get_status(self) -> dict:
        """获取服务器状态"""
        return {
            'serial': self.serial,
            'connections': len(self.connections),
            'max_connections': self.max_connections,
            'bypass_auth': self.bypass_auth
        }


class TcpForward:

    # 认证常量
    AUTH_TOKEN = 1
    AUTH_SIGNATURE = 2
    AUTH_RSAPUBLICKEY = 3

    def __init__(self, client, serial: str, reader: asyncio.StreamReader,
                 writer: asyncio.StreamWriter, bypass_auth: bool = True, device_id: bytes = None):
        self.client = client
        self.serial = serial
        self.reader = reader
        self.writer = writer
        self.bypass_auth = bypass_auth
        self.device_id = device_id

        self.ended = False
        self.authorized = False
        self.max_payload = 4096
        self.version = 1

        self.token = None
        self.signature = None

        self.channels = ChannelManager()
        self.packet_reader = PacketReader(self.reader)

        self.remote_address = writer.get_extra_info('peername')

    async def start(self):
        """开始处理连接"""
        try:
            while not self.ended:
                try:
                    packet = await self.packet_reader.read_packet()
                    if packet is None:
                        break

                    await self._handle_packet(packet)
                except asyncio.CancelledError:
                    break
                except Exception as e:
                    logger.error(f"处理数据包时出错: {e}", exc_info=True)

        except Exception as e:
            logger.error(f"Socket error: {e}", exc_info=True)
        finally:
            await self.end()

    async def _handle_packet(self, packet: Packet):
        """处理数据包"""
        if self.ended:
            return

        try:
            if packet.command == Packet.A_SYNC:
                await self._handle_sync(packet)
            elif packet.command == Packet.A_CNXN:
                await self._handle_connection(packet)
            elif packet.command == Packet.A_AUTH:
                await self._handle_auth(packet)
            elif packet.command == Packet.A_OPEN:
                await self._handle_open(packet)
            elif packet.command in (Packet.A_OKAY, Packet.A_WRTE, Packet.A_CLSE):
                await self._forward_to_channel(packet)
            else:
                raise ValueError(f"Unknown command: {packet.command}")
        except Exception as e:
            logger.error(f"Packet handling error: {e}", exc_info=True)
            await self.end()

    async def _handle_sync(self, packet: Packet):
        """处理SYNC包"""
        pass

    async def _handle_connection(self, packet: Packet):
        """处理连接请求包"""
        self.max_payload = min(0xFFFF, packet.arg1)

        self.token = secrets.token_bytes(20)

        auth_packet = Packet.assemble(Packet.A_AUTH, self.AUTH_TOKEN, 0, self.token)
        await self.write(auth_packet)

    async def _handle_auth(self, packet: Packet):
        """处理认证包"""
        if packet.arg0 == self.AUTH_SIGNATURE:
            if not self.signature:
                self.signature = packet.data

            auth_packet = Packet.assemble(Packet.A_AUTH, self.AUTH_TOKEN, 0, self.token)
            await self.write(auth_packet)

        elif packet.arg0 == self.AUTH_RSAPUBLICKEY:
            if not self.signature:
                raise Exception("Public key sent before signature")

            if self.bypass_auth:
                success = True
            else:
                success = False

            if success:
                self.authorized = True
                device_id = self.device_id if self.device_id else b"device::ro.product.brand=android;ro.product.model=device;ro.product.device=generic;\0"
                cnxn_packet = Packet.assemble(
                    Packet.A_CNXN,
                    Packet.swap32(self.version),
                    self.max_payload,
                    device_id
                )
                await self.write(cnxn_packet)
            else:
                raise Exception("Authentication failed")
        else:
            raise ValueError(f"Unknown authentication method: {packet.arg0}")

    async def _handle_open(self, packet: Packet):
        """处理OPEN包"""
        if not self.authorized:
            raise Exception("Unauthorized")

        remote_id = packet.arg0
        local_id = self.channels.next_local_id()

        if not packet.data or len(packet.data) < 2:
            raise ValueError("Empty service name")

        channel = TcpUsbTransfer(self.client, self.serial, local_id, remote_id, self)
        self.channels.add_channel(local_id, channel)

        try:
            await channel.handle(packet)
        except Exception as e:
            logger.error(f"通道 {local_id} 处理失败: {e}")
            await channel.end()
            raise

    async def _forward_to_channel(self, packet: Packet):
        """转发包到对应的服务通道"""
        if packet.command == Packet.A_OKAY:
            local_id = packet.arg1
        else:
            local_id = packet.arg1

        channel = self.channels.get_channel(local_id)
        if channel:
            await channel.handle(packet)

    async def write(self, data: bytes):
        """写入数据"""
        if self.ended or not self.writer:
            return

        self.writer.write(data)
        await self.writer.drain()

    async def end(self):
        """结束连接"""
        if self.ended:
            return

        self.ended = True

        await self.channels.end_all()

        if self.writer:
            try:
                self.writer.close()
                await self.writer.wait_closed()
            except Exception:
                pass


class TcpUsbTransfer:
    """ADB 服务通道代理，纯粹的双向代理，不解析上层协议内容。"""

    def __init__(self, client, serial: str, local_id: int, remote_id: int, forward: TcpForward):
        self.client = client
        self.serial = serial
        self.local_id = local_id
        self.remote_id = remote_id
        self.forward = forward
        self.transport = None
        self.ended = False
        self.need_ack = False
        self.opened = False

    async def handle(self, packet: Packet):
        if self.ended:
            return
        try:
            if packet.command == Packet.A_OPEN:
                await self._handle_open(packet)
            elif packet.command == Packet.A_OKAY:
                self._handle_okay(packet)
            elif packet.command == Packet.A_WRTE:
                await self._handle_write(packet)
            elif packet.command == Packet.A_CLSE:
                await self._handle_close(packet)
        except Exception:
            await self.end()
            raise

    async def _handle_open(self, packet: Packet):
        if packet.data and len(packet.data) > 0:
            service_name = packet.data[:-1] if packet.data.endswith(b'\0') else packet.data
            service_str = service_name.decode('utf-8', errors='ignore')
        else:
            raise ValueError("Empty service name in packet data")

        try:
            self.transport = await self.client.get_transport(self.serial)
        except Exception as e:
            logger.error(f"通道 {self.local_id} 获取 transport 失败: {e}", exc_info=True)
            raise

        if self.ended:
            raise Exception("Service ended before transport established")

        self.transport.write(Protocol.encode_data(service_str))
        await self.transport.drain()

        reply = await self.transport.parser.read_ascii(4)

        if reply == Protocol.OKAY.decode('ascii'):
            await self.forward.write(Packet.assemble(Packet.A_OKAY, self.local_id, self.remote_id))
            self.opened = True
            asyncio.create_task(self._reader_loop())
        elif reply == Protocol.FAIL.decode('ascii'):
            error_msg = await self.transport.parser.read_error()
            raise Exception(f"Service failed: {error_msg}")
        else:
            raise Exception(f"Unexpected reply: {reply}")

    def _handle_okay(self, packet: Packet):
        if self.ended:
            return
        if not self.transport:
            raise Exception("Received A_OKAY before transport established")
        self.need_ack = False

    async def _handle_write(self, packet: Packet):
        """客户端数据 → 设备（不解析内容，纯转发）"""
        if self.ended:
            return
        if not self.transport:
            raise Exception("Received A_WRTE before transport established")
        if packet.data:
            self.transport.write(packet.data)
            await self.transport.drain()
        await self.forward.write(Packet.assemble(Packet.A_OKAY, self.local_id, self.remote_id))

    async def _handle_close(self, packet: Packet):
        await self.end()

    async def _reader_loop(self):
        """后台持续读取设备数据并转发给客户端，EOF 或异常时自动 end()。"""
        try:
            reader = self.transport.reader if self.transport else None
            if not reader:
                return

            while not self.ended:
                if self.forward.ended:
                    break

                if self.need_ack:
                    await asyncio.sleep(0.001)
                    continue

                try:
                    chunk = await reader.read(self.forward.max_payload)
                except Exception:
                    break

                if not chunk:
                    break

                write_packet = Packet.assemble(Packet.A_WRTE, self.local_id, self.remote_id, chunk)
                await self.forward.write(write_packet)
                self.need_ack = True

        except Exception as e:
            logger.error(f"通道 {self.local_id} reader_loop 异常: {e}", exc_info=True)
        finally:
            if not self.ended:
                await self.end()

    async def end(self):
        if self.ended:
            return self
        self.ended = True

        if self.transport:
            try:
                await self.transport.close()
            except Exception:
                pass

        self.forward.channels.remove_channel(self.local_id)

        if not self.forward.ended and self.forward.writer:
            local_id = self.local_id if self.opened else 0
            try:
                await self.forward.write(Packet.assemble(Packet.A_CLSE, local_id, self.remote_id))
            except Exception:
                pass

        self.transport = None
        return self


class ChannelManager:

    def __init__(self):
        self.channels = {}
        self.next_id = 1

    def next_local_id(self) -> int:
        """获取下一个本地ID"""
        local_id = self.next_id
        self.next_id += 1
        return local_id

    def add_channel(self, local_id: int, channel: TcpUsbTransfer):
        """添加服务通道"""
        self.channels[local_id] = channel

    def get_channel(self, local_id: int) -> TcpUsbTransfer:
        """根据local_id获取服务通道"""
        return self.channels.get(local_id)

    def remove_channel(self, local_id: int):
        """移除服务通道"""
        if local_id in self.channels:
            del self.channels[local_id]

    async def end_all(self):
        """结束所有服务通道"""
        channels_to_close = list(self.channels.values())
        self.channels.clear()
        
        for channel in channels_to_close:
            try:
                if not channel.ended:
                    await channel.end()
            except Exception:
                pass

    @property
    def count(self) -> int:
        return len(self.channels)
