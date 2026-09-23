# Modbus TCP 工业设备采集网关

这是一个可在本地直接运行的 Spring Boot 采集网关。它使用内置的确定性 TCP 设备替代真实 PLC/工业设备，不依赖外部网络或专用硬件，因此采集值、失败原因、重连行为和状态计数都可复现。

## 1. 快速启动

```bash
mvn spring-boot:run
```

默认端口：

- 本地 Modbus TCP 设备：`127.0.0.1:15020`
- 网关 HTTP API：`http://127.0.0.1:18080`

查看全部数据点：

```bash
curl http://127.0.0.1:18080/api/readings
```

查看单个数据点：

```bash
curl http://127.0.0.1:18080/api/readings/temperature-c
```

未知数据点返回 HTTP `404`，不会以空读数冒充有效结果。

查看连接与失败统计：

```bash
curl http://127.0.0.1:18080/api/status
```

运行测试：

```bash
mvn test
```

## 2. Modbus TCP 协议映射

当前实现读取：

- Function Code `0x03`：保持寄存器（Holding Register）
- Function Code `0x04`：输入寄存器（Input Register）

MBAP + PDU 帧格式：

```text
Transaction Id(2) + Protocol Id(2，必须为0) + Length(2) + Unit Id(1)
+ Function Code(1) + Byte Count(1) + Register Data(N)
```

请求与响应都校验：

- Protocol Id 必须为 `0`。
- Length 必须符合 Modbus TCP 范围。
- Function Code 必须与请求一致。
- Transaction Id 必须与在途请求匹配。
- Unit Id 必须与数据点配置匹配。
- Byte Count 必须为偶数，并与 Length、实际数据长度一致。
- 寄存器数量必须与数据点类型一致。
- Function Code 最高位为 `1` 时表示设备异常响应。

TCP 层使用一个读线程持续读取当前连接。读取过程基于 MBAP Length 精确消费字节，因此支持：

- 任意 TCP 分片；
- 多个 Modbus 帧粘在同一个 TCP segment；
- 多个响应乱序到达；
- 按 Transaction Id 将响应分发回对应请求。

无法匹配 Transaction Id 的响应不会完成任何数据点读取，会打印原始报文，并计入状态接口的 `unmatchedFrames`。

## 3. 内置确定性设备

内置设备在 `LocalModbusDeviceServer` 中实现，默认启用。寄存器映像固定，不随重启随机变化。

### 保持寄存器（FC=03）

| 地址 | 原始值 | 默认数据点 / 用途 |
|---:|---:|---|
| 0 | `0x04D2` | `temperature-c`，INT16，scale=0.1，结果 123.4 |
| 1-2 | `0x1234 0x5678` | `device-count`，BIG_ENDIAN + HIGH_WORD_FIRST UINT32，结果 305419896 |
| 3-4 | `0x41CC 0x0000` | `pressure-bar`，BIG_ENDIAN + HIGH_WORD_FIRST FLOAT32，结果 25.5 |
| 5 | `0xFFF6` | `signed-offset`，INT16 符号扩展，结果 -10 |
| 11-12 | `0xA5A5 0x5A5A` | 原始 32 位字序/字节序测试数据 |
| 13-14 | `0x0000 0x41CC` | `pressure-low-word-first`，LOW_WORD_FIRST FLOAT32，结果 25.5 |
| 15-16 | `0x0000 0xCC41` | `mixed-endian-float`，LITTLE_ENDIAN + LOW_WORD_FIRST FLOAT32，结果 25.5 |

### 输入寄存器（FC=04）

| 地址 | 原始值 | 默认数据点 / 用途 |
|---:|---:|---|
| 0 | `0xA604` | `input-raw`，UINT16，结果 42500 |
| 1-2 | `0x0001 0x86A0` | `input-count`，UINT32，结果 100000 |

非法地址和非法数量返回 Modbus 异常：

- 非法功能码：异常码 `0x01`
- 非法地址：异常码 `0x02`
- 非法数量：异常码 `0x03`

## 4. 数据点配置与解码兼容

配置位于 `src/main/resources/application.yml`：

```yaml
gateway:
  points:
    - name: temperature-c
      register-type: HOLDING
      unit-id: 1
      address: 0
      data-type: INT16
      byte-order: BIG_ENDIAN
      word-order: HIGH_WORD_FIRST
      scale: 0.1
      offset: 0
      min: 0
      max: 200
```

支持的数据类型：

- `INT16`
- `UINT16`
- `INT32`
- `UINT32`
- `FLOAT32`（IEEE-754 单精度）

32 位数据的两种约定相互独立：

- `HIGH_WORD_FIRST`：高字在前，常见 ABCD/大端字序。
- `LOW_WORD_FIRST`：低字在前，常见 CDAB 设备。
- `BIG_ENDIAN`：寄存器内高字节在前。
- `LITTLE_ENDIAN`：寄存器内低字节在前。

四种 32 位排列都可表达：ABCD、BADC、CDAB、DCBA。

最终值按以下公式计算：

```text
final = raw * scale + offset
```

解码和配置规则：

1. 启动时校验所有数据点，发现错误即拒绝启动，错误消息包含具体点名。
2. 点名必须唯一且非空。
3. Unit Id、地址、类型、scale、offset、min、max 必须合法。
4. 多寄存器数据点不得跨越 65535 地址边界。
5. 浮点 NaN/Infinity、非有限 scale/offset、换算后非有限值均判定为错误。
6. 最终值落在 `[min, max]` 外时判定为 `OUT_OF_RANGE`。
7. 解码错误、越界、类型长度不匹配不会写入错误数值；读取结果明确标记为失败。

## 5. 读数质量与状态一致性

`ReadingSnapshot` 字段包括：

- `pointName`：数据点名。
- `value`：当前展示值；失败但仍有最后有效值时可能保留旧值。
- `lastGoodValue` / `lastGoodAt`：最后一次 GOOD 读数。
- `quality`：`GOOD`、`DEGRADED`、`STALE`、`BAD`。
- `failureReason`：失败分类。
- `message`：判定依据。
- `rawHex`：原始 Modbus 响应十六进制。
- `cycle`：采集周期号。
- `pollCount`、`successCount`、`failureCount`：点级计数。

质量规则：

- `GOOD`：本轮成功解码并通过量程检查。
- `DEGRADED`：本轮失败，但仍有最后有效值，且年龄未超过 `stale-threshold-ms`。
- `STALE`：当前值来自陈旧的最后有效值，年龄超过阈值；GOOD 读数长时间未刷新时查询接口也会暴露为 STALE。
- `BAD`：从未成功，或当前没有可解释的最后有效值。

采集循环为单线程，每个周期对每个点只提交一次读取并只记录一次结果，因此不会产生重复读数、点间错位或成功/失败计数漂移。并发查询通过同步快照获得一致状态。

## 6. 失败分类

`FailureReason` 对不同失败来源给出可区分原因：

| 原因 | 含义 |
|---|---|
| `CONFIG_ERROR` | 点名、地址、类型、scale、量程等配置错误 |
| `FRAMING_ERROR` | MBAP Protocol Id、Length 等帧边界错误 |
| `PROTOCOL_ERROR` | 不支持的功能码或通用协议错误 |
| `TRANSACTION_MISMATCH` | Transaction Id 不匹配 |
| `UNIT_MISMATCH` | Unit Id 不匹配 |
| `FUNCTION_MISMATCH` | 响应功能码不匹配 |
| `MALFORMED_RESPONSE` | Byte Count、PDU 长度、寄存器数量等报文内容非法 |
| `MODBUS_EXCEPTION` | 设备返回异常功能码和异常码 |
| `TIMEOUT` | 超过 `response-timeout-ms` 未收到完整合法响应 |
| `CONNECTION_CLOSED` | 对端关闭、EOF、分片不完整或连接 I/O 中断 |
| `CONNECTION_FAILED` | TCP 建连失败 |
| `DECODE_ERROR` | 原始寄存器无法按数据点类型解码 |
| `OUT_OF_RANGE` | 解码值越界或非有限 |
| `CANCELLED` | 应用关闭或请求被取消 |
| `UNKNOWN` | 未归类异常；正常配置和协议路径不应出现 |

超时或断连后，采集器会关闭不可信连接并按 `reconnect-delay-ms` 自动重连。非法、超时或不完整响应永远不会被当作有效读数。

## 7. 本地验证覆盖

测试位于 `src/test/java`，覆盖：

- 单字节边界分片、多帧粘包；
- 乱序响应与 Transaction Id 匹配；
- 响应超时、对端断连和重新连接；
- 非法 MBAP、截断 PDU、Byte Count 不匹配；
- Modbus 异常响应；
- INT16/UINT16/INT32/UINT32/FLOAT32；
- BIG/LITTLE endian、HIGH/LOW word order；
- scale、offset、min、max；
- 配置重复点名、非法地址、零 scale 和错误量程；
- BAD/DEGRADED/STALE 质量标记；
- 高并发状态更新下轮询计数、成功计数和失败计数一致性。

测试日志会打印：

- 原始请求/响应十六进制；
- 解析得到的寄存器；
- 数据类型、scale、offset 与最终值；
- 失败原因与判定依据。

## 8. 关键配置

```yaml
gateway:
  client:
    connect-timeout-ms: 500
    response-timeout-ms: 500
    poll-interval-ms: 200
    reconnect-delay-ms: 200
    stale-threshold-ms: 1000
  local-source:
    enabled: true
    host: 127.0.0.1
    port: 15020
```

若要连接外部 TCP 数据源，可将 `gateway.local-source.enabled=false`，并把 `gateway.client.host/port` 指向外部兼容 Modbus TCP 的服务。
