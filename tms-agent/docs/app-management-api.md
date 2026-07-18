# 设备应用管理 API

agent 代理在每台设备的代理端口上直接暴露一组 HTTP 接口，用于**列出 / 卸载 / 安装**设备上的应用。
Web 详情页的「应用管理」面板即调用这套接口；自动化测试脚本也可以直接调同一套接口完成安装 / 卸载。

- **逻辑集中在 agent**：Android 用 `adb`，iOS 用 go-ios，脚本无需在本机装任何工具。
- **安装 / 卸载均为同步接口**：装完 / 卸完才返回，脚本按 HTTP 状态码判断成败。
- **Android 与 iOS 接口同形**，仅安装包类型不同（APK / IPA）。

---

## 1. 接入地址

```
http://{proxyHost}:{proxyPort}/devices/{serial}/apps...
```

| 平台 | 默认代理端口 | 安装包 |
|------|-------------|--------|
| Android | `8000` | `.apk` |
| iOS | `8001` | `.ipa` |

`proxyHost` / `proxyPort` / `serial` 三个值从管理后端的“设备连接信息”接口获取（与 web 详情页取值一致）：

```
GET http://{后端}:8888/api/.../device/connection/{deviceId}
→ data: { proxyHost, proxyPort, serial, ... }
```

> 代理沿用内网信任模型，**无鉴权**。请在受信网络内调用。

---

## 2. 接口一览

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET`  | `/devices/{serial}/apps` | 列出已安装应用 |
| `POST` | `/devices/{serial}/apps/uninstall` | 卸载指定应用 |
| `POST` | `/devices/{serial}/apps/install?filename=<名字>` | 上传安装包并安装 |

### 通用状态码

| 状态码 | 含义 |
|--------|------|
| `200` | 成功 |
| `400` | 参数缺失 / 请求体非法 / 空文件 |
| `404` | 设备不存在 |
| `409` | 设备不在线（iOS 还包括未就绪） |
| `500` | 执行失败，`error` 字段带 adb / go-ios 原始输出 |

失败响应统一为：

```json
{ "ok": false, "error": "错误详情" }
```

---

## 3. 列出应用

```
GET /devices/{serial}/apps
```

**响应 200**

```json
{
  "apps": [
    { "id": "com.example.app", "name": "示例应用", "version": "",     "system": false },
    { "id": "com.example.two", "name": "另一个应用", "version": "2.1", "system": false }
  ]
}
```

| 字段 | 说明 |
|------|------|
| `id` | 应用唯一标识：Android=包名，iOS=bundleId。**卸载即用此值**。 |
| `name` | 应用显示名。Android 为包名（无廉价 label），iOS 为应用显示名。 |
| `version` | 版本号。Android 为空串，iOS 为 `CFBundleShortVersionString`。 |
| `system` | 恒为 `false`。列表只返回可管理的用户/三方应用，**系统应用已在服务端过滤，不会返回**。 |

列表已按名称/包名排序；仅包含可卸载的用户/三方应用。

**示例**

```bash
curl "http://10.0.0.5:8000/devices/SERIAL/apps"
```

```python
import requests
apps = requests.get(f"http://{host}:{port}/devices/{serial}/apps").json()["apps"]
# 返回的都是可卸载应用，无需再按 system 过滤
```

---

## 4. 卸载应用

```
POST /devices/{serial}/apps/uninstall
Content-Type: application/json

{ "id": "com.example.app" }
```

- `id`：Android 包名 / iOS bundleId（取自列表接口的 `id`）。
- 同步返回：卸完才响应。
- 列表已过滤系统应用，返回的都是可卸载应用。

**响应 200**

```json
{ "ok": true }
```

**示例**

```bash
curl -X POST "http://10.0.0.5:8000/devices/SERIAL/apps/uninstall" \
     -H "Content-Type: application/json" \
     -d '{"id":"com.example.app"}'
```

```python
import requests
r = requests.post(
    f"http://{host}:{port}/devices/{serial}/apps/uninstall",
    json={"id": "com.example.app"},
)
assert r.status_code == 200 and r.json().get("ok"), r.text
```

---

## 5. 安装应用

```
POST /devices/{serial}/apps/install?filename=<文件名>
Content-Type: application/octet-stream

<安装包的原始二进制字节流(请求体)>
```

- **请求体是安装包本身的原始字节**（不是 multipart 表单），agent 流式落盘后执行安装。
- `filename` 查询参数只用于决定临时文件后缀（`.apk` / `.ipa`），缺省为 `app.apk` / `app.ipa`。
- Android 用 `adb install -r`（`-r` 允许覆盖安装 / 升级）。
- 同步返回：装完才响应。
- 上限 2GB（agent 已放开 body 上限）。

**响应 200**

```json
{ "ok": true }
```

**示例**

```bash
# Android
curl -X POST --data-binary @app.apk \
     "http://10.0.0.5:8000/devices/SERIAL/apps/install?filename=app.apk"

# iOS
curl -X POST --data-binary @app.ipa \
     "http://10.0.0.5:8001/devices/SERIAL/apps/install?filename=app.ipa"
```

```python
import os, requests

def install(host, port, serial, pkg_path):
    fname = os.path.basename(pkg_path)
    with open(pkg_path, "rb") as f:
        r = requests.post(
            f"http://{host}:{port}/devices/{serial}/apps/install",
            params={"filename": fname},
            data=f,                       # 原始文件流
            headers={"Content-Type": "application/octet-stream"},
            timeout=600,
        )
    if r.status_code != 200 or not r.json().get("ok"):
        raise RuntimeError(f"安装失败: {r.text}")

install("10.0.0.5", 8000, "SERIAL", "./app.apk")
```

> `requests` 传文件对象 `data=f` 时会以流式发送，不会把整包读进内存。

---

## 6. 端到端示例（自动化脚本）

```python
import os, requests

class AppManager:
    def __init__(self, host, port, serial):
        self.base = f"http://{host}:{port}/devices/{serial}/apps"

    def list(self):
        return requests.get(self.base, timeout=30).json()["apps"]

    def uninstall(self, app_id):
        r = requests.post(f"{self.base}/uninstall", json={"id": app_id}, timeout=120)
        if r.status_code != 200 or not r.json().get("ok"):
            raise RuntimeError(f"卸载失败: {r.text}")

    def install(self, pkg_path):
        with open(pkg_path, "rb") as f:
            r = requests.post(
                f"{self.base}/install",
                params={"filename": os.path.basename(pkg_path)},
                data=f,
                headers={"Content-Type": "application/octet-stream"},
                timeout=600,
            )
        if r.status_code != 200 or not r.json().get("ok"):
            raise RuntimeError(f"安装失败: {r.text}")


# 用法：先从后端拿到 proxyHost/proxyPort/serial
am = AppManager("10.0.0.5", 8000, "SERIAL")
am.install("./new_build.apk")                       # 覆盖安装最新包
assert any(a["id"] == "com.example.app" for a in am.list())
am.uninstall("com.example.app")                     # 测试结束清场
```

---

## 7. 注意事项

- **同步语义**：安装 / 卸载接口装完 / 卸完才返回，无需轮询；失败信息在 `error` 字段。
- **系统应用**：列表接口已在服务端过滤系统应用，返回的都是可卸载的用户/三方应用。
- **超时**：安装耗时较长，客户端超时建议 ≥ 600s；列表 / 卸载 30~120s 足够。
- **设备状态**：设备离线 / 未就绪时返回 `409`，请确认设备已被占用且代理已上报。
- **并发**：iOS 侧的 go-ios 调用在 agent 内部串行化（避免与隧道 / WDA 抢占 usbmux），高并发调用会排队。
