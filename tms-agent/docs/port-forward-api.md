# 设备端口转发 API

agent 代理在每台设备的代理端口上暴露一个 HTTP 接口，用于把**设备上的任意端口**转发到 agent 所在机器的一个 LAN 端口上。
典型场景：真机上跑的 Unity 游戏 / 调试服务监听了某个端口，测试需要从别的机器直接连到它。

- **逻辑集中在 agent**：Android 用 `adb forward`，iOS 用 go-ios `forward`，脚本无需在本机装任何工具。
- **默认暴露到 LAN**：agent 在 `0.0.0.0` 上再挂一层 TCP 中继，返回的地址内网可直连（iOS 的 go-ios 只能绑 `127.0.0.1`，靠这层中继补齐）。
- **幂等**：同一设备同一设备端口重复请求，返回同一个代理端口，不会重复占用。
- **自动清理**：设备下线时，其名下所有转发被自动拆除。

---

## 1. 接入地址

```
http://{proxyHost}:{proxyPort}/api/forward
```

`platform` 由你请求打到的端口隐含，无需在参数里传：

| 平台 | 默认代理端口（即接口端口） |
|------|--------------------------|
| Android | `8000` |
| iOS | `8001` |

`proxyHost` / `proxyPort` / `serial` 从管理后端的“设备连接信息”获取（与 web 详情页、应用管理接口取值一致）：

```
GET http://{后端}:8888/api/.../device/connection/{deviceId}
→ data: { proxyHost, proxyPort, serial, ... }
```

> 代理沿用内网信任模型，**无鉴权**。请在受信网络内调用。

### 转发链路

```
客户端 → agent(0.0.0.0:代理端口 中继) → 127.0.0.1:本地端口(adb/go-ios 转发) → 设备:设备端口
```

> 注意区分两个端口：`{proxyPort}`（`8000/8001`）是**调接口**用的；接口返回里的 `proxy_port` 才是**连游戏/服务**用的那个转发端口（Android 落在 20000–30000，iOS 落在 30001–40000）。

---

## 2. 接口一览

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST`   | `/api/forward` | 建立（或复用）一条端口转发 |
| `GET`    | `/api/forward` | 列出本平台当前所有转发 |
| `DELETE` | `/api/forward` | 删除某设备的全部转发 |

### 通用响应格式

成功：

```json
{ "code": 0, "data": { ... } }
```

失败：

```json
{ "code": 1, "message": "错误详情" }
```

### 状态码

| 状态码 | 含义 |
|--------|------|
| `200` | 成功 |
| `400` | 参数非法：请求体非 JSON、`serial` 为空、`port` 非法、**设备不在线/未接入** |
| `500` | 执行失败，`message` 带 adb / go-ios 原始错误 |
| `503` | 转发服务未就绪（代理服务未绑定设备管理器时才会出现） |

---

## 3. 建立转发

```
POST /api/forward
Content-Type: application/json

{ "serial": "设备序列号", "port": 12345 }
```

- `serial`：设备序列号 / udid。
- `port`：**设备上**要暴露的端口。
- 幂等：同一 `(serial, port)` 已存在且存活则直接返回原有转发。

**响应 200**

```json
{
  "code": 0,
  "data": {
    "platform": "android",
    "serial": "SERIAL",
    "device_port": 12345,
    "proxy_host": "10.0.0.5",
    "proxy_port": 20345,
    "connection": "10.0.0.5:20345"
  }
}
```

| 字段 | 说明 |
|------|------|
| `platform` | 平台，由接口端口隐含（`android` / `ios`）。 |
| `serial` | 设备序列号 / udid。 |
| `device_port` | 设备上被转发的端口（即请求的 `port`）。 |
| `proxy_host` | agent 的 LAN IP。 |
| `proxy_port` | agent 上对外的代理端口，**客户端连这个**。 |
| `connection` | `proxy_host:proxy_port` 拼好的直连串。 |

**示例**

```bash
# Android：把设备 12345 端口暴露出来
curl -X POST "http://10.0.0.5:8000/api/forward" \
     -H "Content-Type: application/json" \
     -d '{"serial":"SERIAL","port":12345}'

# iOS
curl -X POST "http://10.0.0.5:8001/api/forward" \
     -H "Content-Type: application/json" \
     -d '{"serial":"UDID","port":12345}'
```

拿到 `data.connection`（如 `10.0.0.5:20345`）后，客户端 / Unity 直接连它即可。

---

## 4. 查询转发

```
GET /api/forward
```

列出**当前平台**所有活跃转发（Android 服务只列 Android 的，iOS 同理）。

**响应 200**

```json
{
  "code": 0,
  "data": [
    { "platform": "android", "serial": "SERIAL", "device_port": 12345,
      "proxy_host": "10.0.0.5", "proxy_port": 20345, "connection": "10.0.0.5:20345" }
  ]
}
```

**示例**

```bash
curl "http://10.0.0.5:8000/api/forward"
```

---

## 5. 删除转发

```
DELETE /api/forward
Content-Type: application/json

{ "serial": "设备序列号" }
```

- 删除该设备名下**所有**额外端口转发（关闭中继、停 go-ios 进程、`adb forward --remove`）。
- `platform` 由接口端口隐含，无需传。

**响应 200**

```json
{ "code": 0, "data": { "serial": "SERIAL", "removed": 2 } }
```

`removed` 为实际清理的转发条数。

**示例**

```bash
curl -X DELETE "http://10.0.0.5:8000/api/forward" \
     -H "Content-Type: application/json" \
     -d '{"serial":"SERIAL"}'
```

> `curl` 用 `DELETE` 带 body 时务必显式 `-X DELETE` 且带 `Content-Type: application/json`。

---

## 6. 端到端示例（自动化脚本）

```python
import requests


class PortForwarder:
    def __init__(self, host, port, serial):
        self.base = f"http://{host}:{port}/api/forward"
        self.serial = serial

    def add(self, device_port) -> str:
        """建立转发，返回可直连的 host:port。幂等。"""
        r = requests.post(self.base, json={"serial": self.serial, "port": device_port}, timeout=30)
        if r.status_code != 200:
            raise RuntimeError(f"建立转发失败: {r.text}")
        return r.json()["data"]["connection"]

    def list(self):
        return requests.get(self.base, timeout=30).json()["data"]

    def clear(self):
        r = requests.delete(self.base, json={"serial": self.serial}, timeout=30)
        return r.json()["data"]["removed"]


# 用法：先从后端拿到 proxyHost/proxyPort/serial
fw = PortForwarder("10.0.0.5", 8000, "SERIAL")
conn = fw.add(12345)          # e.g. "10.0.0.5:20345"
print("连这个地址:", conn)     # Unity / 客户端连 conn
# ... 测试 ...
fw.clear()                    # 结束清场（设备下线也会自动清）
```

---

## 7. 注意事项

- **两个端口别混**：`8000/8001` 是调接口用的；返回的 `proxy_port` 才是连服务用的。
- **iOS 也能内网直连**：go-ios `forward` 只绑 `127.0.0.1`，agent 已在 `0.0.0.0` 加了一层中继补齐，返回的 `proxy_host` 是 LAN IP。
- **幂等**：同一 `(serial, port)` 重复 `POST` 返回同一 `proxy_port`；若原转发已失效会自动重建。
- **自动清理**：设备下线时其全部转发被自动拆除；无需担心残留端口。手动结束用 `DELETE`。
- **设备状态**：设备离线 / 未接入时返回 `400`，`message` 为“设备不在线或未接入”，请确认设备已被占用且代理已上报。
- **并发**：iOS 侧 go-ios 调用在 agent 内部串行化（避免与隧道 / WDA 抢占 usbmux），高并发会排队。
