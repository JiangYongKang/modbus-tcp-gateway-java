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

## 4. 运行期故障编排（故障演练）

网关运行期间可以通过 HTTP API 对内置设备下发故障编排指令，无需改代码、无需重启，网关采集循环照常运行。故障期间产生的异常读数仍按原有的质量标记（`DEGRADED`/`STALE`/`BAD`）和失败分类（`FailureReason`）对外暴露；撤销故障后采集自动恢复正常值，故障期间的旧值不会被当作当前有效状态，恢复后计数不重复、不漂移。

### 注入故障

```bash
curl -X POST http://127.0.0.1:18080/api/simulator/faults \
  -H 'Content-Type: application/json' \
  -d '{"type":"MODBUS_EXCEPTION","pointName":"temperature-c","exceptionCode":2}'
```

故障定位二选一：

- `pointName`：按数据点注入，服务端自动解析为该点的寄存器类型与地址范围；
- `registerType` + `addressStart`/`addressEnd`：按寄存器地址段注入（`registerType` 省略时同时匹配 HOLDING 与 INPUT）。

支持的故障类型（`FaultType`）：

| 类型 | 行为 | 网关侧表现 |
|---|---|---|
| `LATENCY` | 响应前延迟 `delayMs` 毫秒 | 延迟低于 `response-timeout-ms` 时读数保持 GOOD；超过则 `TIMEOUT` |
| `TIMEOUT` | 吞掉请求，永不响应 | `TIMEOUT`，随后自动重连 |
| `MODBUS_EXCEPTION` | 返回异常响应，异常码由 `exceptionCode` 指定 | `MODBUS_EXCEPTION` |
| `CONNECTION_DROP` | 收到请求后直接断开 TCP 连接 | `CONNECTION_CLOSED`，随后自动重连 |
| `FRAGMENT` | 把响应拆成 `fragmentCount` 段、间隔 `fragmentDelayMs` 发送 | 分片重组后仍解码为 GOOD |
| `COALESCE` | 把同一帧的两份拷贝粘在一个 TCP 段里发出 | 第一份正常完成读取，重复帧计入 `unmatchedFrames` |

### 查看与撤销

```bash
# 查看当前生效的故障
curl http://127.0.0.1:18080/api/simulator/faults

# 按 id 撤销单个故障
curl -X DELETE http://127.0.0.1:18080/api/simulator/faults/<faultId>

# 一键撤销全部故障，恢复正常采集
curl -X DELETE http://127.0.0.1:18080/api/simulator/faults
```

非法请求（未知点名、缺少类型、地址越界等）返回 HTTP `400`；删除不存在的故障返回 `404`；本地设备未启用时编排接口返回 `503`。

### 运行期改写寄存器

同一批原始寄存器内容可以在运行期改写，用于拿真实报文核对各种解码约定，无需重新编译：

```bash
# 改写保持寄存器 0 为 0x01F4（temperature-c 变为 50.0）
curl -X PUT http://127.0.0.1:18080/api/simulator/registers \
  -H 'Content-Type: application/json' \
  -d '[{"registerType":"HOLDING","address":0,"value":500}]'

# 查看当前寄存器映像
curl http://127.0.0.1:18080/api/simulator/registers

# 恢复为初始确定性映像
curl -X DELETE http://127.0.0.1:18080/api/simulator/registers
```

改写后解码仍完全按数据点配置的 `byte-order`/`word-order`/`data-type`/`scale`/`offset`/量程执行：同一组原始字节在不同约定下给出确定且可区分的结果；越界、类型不匹配等仍落到具体数据点上（如 `OUT_OF_RANGE`），不会悄悄写入错误数值，其它点不受影响。

## 5. 故障过程与健康查询

每次故障的完整过程可以通过 HTTP API 查询，注入和恢复期间查询接口保持可用，并发查询时各点计数和状态一致、不串点。

```bash
# 单个数据点的故障过程
curl http://127.0.0.1:18080/api/readings/temperature-c/health

# 网关整体健康判定
curl http://127.0.0.1:18080/api/health
```

`PointHealthSnapshot` 字段：

- `healthy`：当前无进行中的故障且质量为 `GOOD`；
- `quality`：当前质量标记；
- `faultActive`：是否处于未恢复的故障中；
- `lastFaultStartedAt`：最近一次故障开始时间（首次失败的时刻，后续连续失败不会移动该时间）；
- `lastFailureReason`：最近一次失败分类；
- `lastRecoveredAt`：最近一次恢复时间（故障后第一次成功的时刻）；
- `firstGoodValueAfterRecovery`：恢复后的第一个有效值。

`GatewayHealthSnapshot` 字段：`healthy`（连接正常且全部数据点健康）、`connectionState`、`totalPoints`、`healthyPoints`，以及每个点的 `PointHealthSnapshot` 列表。

## 6. 数据点配置与解码兼容

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

## 7. 读数质量与状态一致性

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

## 8. 失败分类

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

## 9. 本地验证覆盖

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
- 高并发状态更新下轮询计数、成功计数和失败计数一致性；
- 端到端故障演练（`FaultDrillEndToEndTest`，真实启动服务并通过运行期编排 API 注入/撤销故障）：
  - `MODBUS_EXCEPTION`、`TIMEOUT`、`CONNECTION_DROP` 的失败分类与恢复后读数；
  - `FRAGMENT` 分片响应仍解码为 GOOD，`COALESCE` 粘包重复帧计入 `unmatchedFrames`；
  - `LATENCY` 在超时阈值内读数保持 GOOD；
  - 运行期寄存器改写改变解码结果、复位后恢复初始值；
  - 同一组原始字节在 BIG/LITTLE endian 与 HIGH/LOW word order 下结果确定且可区分；
  - 越界值只落在对应数据点上，保留最后有效值，邻点不受影响；
  - 故障期间并发查询各接口，计数不漂移、故障不串点；
  - 非法编排请求返回 400/404；
- 故障编排匹配规则（`FaultOrchestratorTest`）：地址段重叠、寄存器类型过滤、参数校验、撤销后恢复；
- 故障过程跟踪（`ReadingStoreHealthTest`）：故障开始/恢复时间、恢复后首个有效值、整体健康判定。

测试日志会打印：

- 原始请求/响应十六进制；
- 解析得到的寄存器；
- 数据类型、scale、offset 与最终值；
- 失败原因与判定依据。

## 10. 关键配置

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
