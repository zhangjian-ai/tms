import asyncio

from device.android.handler import AndroidProxyServer
from device.android.manager import AndroidDeviceManager
from device.ios.handler import IOSProxyServer
from device.ios.manager import IOSDeviceManager


async def main():

    # Android 服务
    aps = AndroidProxyServer()
    aps.run()

    # 创建 iOS 设备管理器，这里和android有些差异，ios的操作投屏都要使用具体的设备端口
    ios_manager = IOSDeviceManager()
    ips = IOSProxyServer(device_manager=ios_manager)
    ips.run()

    # 并行运行两个设备管理器
    await asyncio.gather(
        AndroidDeviceManager().sync(),
        ios_manager.sync()
    )


if __name__ == '__main__':
    asyncio.run(main())
