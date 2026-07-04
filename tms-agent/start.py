import asyncio

from device.android.handler import AndroidProxyServer
from device.android.manager import AndroidDeviceManager
from device.ios.handler import IOSProxyServer
from device.ios.manager import IOSDeviceManager


async def main():

    # 创建 Android 设备管理器与代理服务器
    android_manager = AndroidDeviceManager()
    aps = AndroidProxyServer(device_manager=android_manager)
    aps.run()

    # 创建 iOS 设备管理器与代理服务器
    ios_manager = IOSDeviceManager()
    ips = IOSProxyServer(device_manager=ios_manager)
    ips.run()

    # 并行运行两个设备管理器的同步循环 + 后端指令接收循环
    await asyncio.gather(
        android_manager.sync(),
        ios_manager.sync(),
        android_manager.command_loop(),
        ios_manager.command_loop()
    )


if __name__ == '__main__':
    asyncio.run(main())
