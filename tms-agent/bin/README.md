# bin — 项目自带二进制

agent 运行时直接使用这里的二进制，不依赖宿主机 PATH，同时保证 `adb` 版本统一
（避免多版本 adb 客户端触发 server 重启）。

## 结构

```
bin/
  darwin/   # macOS（通用二进制）
    adb
    ios     # go-ios
  linux/    # Linux x86_64 (amd64)
    adb
    ios
```

平台由 `utils/binaries.py` 按 `platform.system()` 自动选择（仅支持 mac/linux）。
可用 `settings.yml` 的 `android.adb.bin` / `ios.go_ios_bin` 填绝对路径覆盖。

## 来源与版本

| 二进制 | 来源 | 版本 |
|---|---|---|
| adb | Android platform-tools (`platform-tools-latest-{darwin,linux}.zip`) | latest |
| ios | [go-ios](https://github.com/danielpaulus/go-ios) | v1.2.0 |

- Linux 统一采用 amd64（Google 的 linux platform-tools 仅提供 x86_64；go-ios 另有 `ios-arm64`，如需 arm64 主机可自行替换）。
- iOS 17+ 需常驻 RSD 隧道：`sudo ios tunnel start`（需 root）。

## 重新生成

```
bash scripts/fetch_binaries.sh
```

## 是否提交到 git

这些文件约 94MB。二选一：
- **提交**：部署主机无需联网即可 `git clone` 直接用（适合内网/离线部署）。
- **不提交**：将 `bin/darwin`、`bin/linux` 加入 `.gitignore`，部署时在每台主机跑一次 `fetch_binaries.sh`。
