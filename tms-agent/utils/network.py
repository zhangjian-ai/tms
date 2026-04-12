import socket
import random
import struct

import fcntl
import netifaces


class Port:
    Android = [20000, 30000]
    Ios = [30001, 40000]
    Harmony = [40001, 50000]

    @classmethod
    def get(cls, platform: str):
        if platform.lower() == "android":
            start, end = cls.Android
        elif platform.lower() == "ios":
            start, end = cls.Ios
        elif platform.lower() == "harmony":
            start, end = cls.Harmony
        else:
            raise ValueError("platform 参数值只能是 android、ios、harmony")

        while True:
            port = random.randint(start, end)

            if cls.in_use(port):
                return port

    @classmethod
    def in_use(cls, port):
        """检查端口是否未被占用"""
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            try:
                # 尝试绑定端口，如果成功说明端口未被占用
                s.bind(('0.0.0.0', port))
                return True  # 端口可用
            except OSError:
                return False  # 端口被占用


class Host:

    @classmethod
    def get(cls):
        interfaces = netifaces.interfaces()
        for interface in interfaces:
            if interface == 'lo':
                continue
            addresses = netifaces.ifaddresses(interface)
            if netifaces.AF_INET in addresses:
                ip = addresses[netifaces.AF_INET][0]['addr']
                if ip != '127.0.0.1':
                    return ip

        raise RuntimeError("本机IP获取失败")