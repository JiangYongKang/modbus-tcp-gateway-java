# Modbus TCP 工业设备采集网关（本地可复现版）

一套可在本地完整运行的工业设备采集网关。用内置的**脚本化 Mock Modbus TCP 数据源**
替代真实设备，使采集结果、失败判定与运行状态在不同网络条件（分片、粘包、超时、
断连重连、非法报文）和不同配置条件（字节序/字序、符号、缩放、量程）下都保持
**可预期、可复现**。

## 1. 功能总览

| 能力 | 实现要点 |
|---|---|
| 持续采集 | 每设备单线程定时轮询，相邻数据点自动合并为最少的 FC=0x03 读取区间（单区间 ≤125 寄存器） |
| 流式收包 | `FramingBuffer` 处理**半包/粘包**；按 MBAP 长度字段切帧，不做任何猜测式对齐 |
| 请求匹配 | 严格按 **MBAP 事务号 + 单元号**匹配响应；乱序/串号响应直接判失败，绝不张冠李戴 |
| 超时 | Socket 读超时；**零字节超时=TIMEOUT，收到部分字节未收齐=INCOMPLETE_FRAME** |
| 连接生命周期 | 懒连接、失败即断链（杜绝陈旧字节污染）、指数退避自动重连，重连计数可查询 |
| 解码兼容 | INT16/UINT16/INT32/UINT32/INT64/FLOAT32/FLOAT64 × 4 种字/字节序 × 缩放偏移量程 |
| 失败归类 | 13 种可区分 `FailReason`（见第 4 节），异常消息携带**原始报文十六进制**与判定依据 |
| 运行期一致性 | 读数整体快照加锁；计数满足 `pollCount = successCount + failureCount`；旧值降级 UNCERTAIN→STALE |
| 对外接口 | `GET /api/status[/{deviceId}]`、`GET /api/readings[/{deviceId}]` |

## 2. 快速开始

要求 JDK 21+、Maven 3.8+。

```bash
# 启动（自动内置 Mock 数据源 127.0.0.1:11502，并采集演示设备 device-1）
mvn spring-boot:run

# 另开终端查询
curl -s http://127.0.0.1:8080/api/status | python3 -m json.tool
curl -s http://127.0.0.1:8080/api/readings/device-1 | python3 -m json.tool

# 全量测试（38 个用例，覆盖分片/乱序/超时/重连/非法报文/解码约定）
mvn test
```

预期 `device-1` 的 4 个演示点（地址 0 起的合并区间）：

| 数据点 | 类型/约定 | 线序原始值 | 工程量 |
|---|---|---|---|
| `temp_big_endian` | INT16 ABCD, ×0.1−40 | `00 FA` | -15.0 |
| `pressure_word_swap` | UINT32 CDAB | `00 01 00 00` | 1 |
| `flow_float_little` | FLOAT32 DCBA(小端) | `C3 F5 48 40` | 3.1400001 |
| `energy_int64` | INT64 ABCD, ×0.001 | `00 00 00 00 00 00 30 39` | 12.345 |

## 3. 协议映射与解码兼容规则

### 3.1 Modbus TCP 帧（MBAP + PDU）

```
MBAP(7B): transactionId(2) protocolId(2=0) length(2) unitId(1)
请求 PDU(FC=0x03, 5B): functionCode=0x03 startAddress(2) quantity(2)
正常响应 PDU: functionCode=0x03 byteCount(1) data(byteCount)
异常响应 PDU: functionCode=0x83 exceptionCode(1)
```

- 只采集**保持寄存器（功能码 0x03）**；其他功能码响应一律判 `ILLEGAL_FRAME`。
- 网关只信任结构自洽的帧：`protocolId=0`、`length≥2`、整帧 ≤259 字节、
  `byteCount = quantity×2 = 实际帧长−9`。任何矛盾都判非法帧并丢弃整条连接。
- 失败即关闭底层连接并清空接收缓冲，保证上一轮的陈旧字节不可能被下一轮误读。

### 3.2 字序 / 字节序（兼容规则的核心）

线序基准 **ABCD**（高字在低地址、字内高字节在前，Modbus 标准大端）。
以 32 位模式 `0x12345678` 为例，四种约定下设备线上发的字节不同：

| 约定 | 线序字节 | 含义 |
|---|---|---|
| `ABCD` | `12 34 56 78` | 高字在前 + 大字在前（默认大端） |
| `BADC` | `34 12 78 56` | 字序不变，字内字节交换 |
| `CDAB` | `56 78 12 34` | 字整体交换（Schneider 等常见） |
| `DCBA` | `78 56 34 12` | 字交换 + 字节交换（整体小端） |

同一段线序字节用不同约定解释会得到**不同但确定**的值（由
`PointDecoderTest#int32FourOrders` 锁定）。

### 3.3 类型与缩放

- 整数：`INT16/UINT16/INT32/UINT32/INT64` 按补码解释（是否有符号由类型决定）；
- 浮点：`FLOAT32`（IEEE 754 单精度）、`FLOAT64`（双精度）；
- 工程量换算：`engineering = raw × scale + offset`；
- 量程：可选 `[min, max]` 闭区间校验；
- **任何**数据区越界、奇数偏移、非有限浮点（NaN/Infinity）、scale=0、
  量程越界，都抛 `DECODE_ERROR` 并定位到具体 `pointId`，绝不静默产出错误数值。

## 4. 失败分类（FailReason）

| 原因 | 触发条件 |
|---|---|
| `CONFIG_ERROR` | 地址/数量非法等请求侧配置问题 |
| `DECODE_ERROR` | 帧有效但无法按点约定解码（越界、类型、量程、字节序不适用），定位到点 |
| `ILLEGAL_FRAME` | protocolId≠0、length/byteCount 不符、功能码不符、数据长度不符、垃圾字节 |
| `INCOMPLETE_FRAME` | 超时窗口内只收到部分字节（半包未收齐） |
| `TRANSACTION_MISMATCH` | 响应事务号或单元号与请求不匹配（乱序/串号） |
| `TIMEOUT` | 响应超时且一个字节都没收到 |
| `CONNECT_FAILED` | 连接被拒绝 / 不可达 |
| `CONNECTION_LOST` | 已建连接被 RST/FIN（含响应前 EOF）或读写 IO 异常 |
| `MODBUS_ILLEGAL_FUNCTION` (0x01) | 设备异常码 |
| `MODBUS_ILLEGAL_DATA_ADDRESS` (0x02) | 设备异常码 |
| `MODBUS_ILLEGAL_DATA_VALUE` (0x03) | 设备异常码 |
| `MODBUS_SERVER_DEVICE_FAILURE` (0x04) | 设备异常码 |
| `MODBUS_EXCEPTION_OTHER` | 其他异常码 |

日志示例（判定依据 + 原始报文）：

```
[dev] 读取失败 addr=0 qty=2 reason=INCOMPLETE_FRAME detail=等待响应超时（400ms），
已收 6 字节；请求=00 01 00 00 00 06 01 03 00 00 00 02，残缺响应=00 01 00 00 00 07
```

## 5. 质量标记（Quality）与运行期一致性

| 标记 | 含义 |
|---|---|
| `GOOD` | 本轮采集并解码成功，值新鲜 |
| `UNCERTAIN` | 通信失败但保留上一次有效值，且未过陈旧阈值 |
| `STALE` | 保留值已超过 `gateway.stale-threshold-ms`（含采集停止场景） |
| `BAD` | 从未成功，或该点解码/配置失败（value 恒为 null） |

一致性保证：

- 同一 `(deviceId, pointId)` 只存最新状态；成功轮次**整体**提交，避免半新半旧；
- 通信失败不删除旧值，仅降级；点级失败只置该点 BAD，不影响其他点；
- 计数在同一把锁上维护，恒有 `pollCount = successCount + failureCount`，
  恢复成功后 `consecutiveFailures` 清零（由 `IntegrationScenarioTest` 断言）；
- 8 线程并发快照读取与采集同时进行的场景由测试覆盖，不允许错位或计数漂移。

## 6. 本地异常场景如何复现（Mock 脚本能力）

`MockModbusServer` 对“下一条接入连接”排队脚本（测试中使用）：

- `fragmentedSend(frame, 1, 30)`：逐字节分片，每片间隔 30ms；
- `RawSend(byte[])`：一次发任意字节（粘包/非法帧/精细分片）；
- `Delay(ms)`：发送延迟（制造 TIMEOUT / INCOMPLETE_FRAME）；
- `enqueueExceptionOnNextRequest(0x02)`：回 Modbus 异常码；
- `enqueueEchoOnceThenClose()`：正常应答一帧后断连（抖动/自动恢复）；
- 寄存器映像 `setRegister/setRegisters`：确定性的正常数据源。

测试与类位置：

| 测试类 | 覆盖内容 |
|---|---|
| `PointDecoderTest` | 9 例：4 种字序、符号、FLOAT32/INT64、缩放偏移、量程、越界定位 |
| `FrameAssemblyTest` | 5 例：逐字节分片、粘包两帧一 chunk、事务号乱序、半包超时、顺序回归 |
| `TimeoutReconnectTest` | 5 例：静默超时、连接拒绝、断连自动恢复、宕机退避、UNCERTAIN→STALE |
| `IllegalFrameTest` | 9 例：4 类 Modbus 异常码、protocolId、byteCount、功能码、单元号、垃圾字节 |
| `ConfigValidatorTest` | 5 例：地址越界、量程矛盾、id 重复、port 越界、约定缺失 |
| `IntegrationScenarioTest` | 4 例：端到端多类型、点级失败隔离、源端抖动计数守恒、并发快照 |

## 7. 配置说明（application.properties）

```properties
gateway.poll-interval-ms=1000              # 轮询周期
gateway.stale-threshold-ms=5000            # 保留值陈旧阈值
gateway.reconnect-backoff-initial-ms=500   # 重连初始退避（指数翻倍，封顶 max）
gateway.reconnect-backoff-max-ms=10000
gateway.mock-server-enabled=true           # 内置本地数据源
gateway.mock-server-port=11502
gateway.devices[0].id=device-1
gateway.devices[0].host=127.0.0.1
gateway.devices[0].port=11502
gateway.devices[0].unit-id=1
gateway.devices[0].connect-timeout-ms=1000
gateway.devices[0].response-timeout-ms=1500
gateway.devices[0].points[0].id=temp_big_endian
gateway.devices[0].points[0].address=0
gateway.devices[0].points[0].type=INT16        # INT16 UINT16 INT32 UINT32 INT64 FLOAT32 FLOAT64
gateway.devices[0].points[0].order=ABCD        # ABCD BADC CDAB DCBA
gateway.devices[0].points[0].scale=0.1
gateway.devices[0].points[0].offset=-40
gateway.devices[0].points[0].min=-50
gateway.devices[0].points[0].max=150
```

接真实设备时：将 `gateway.mock-server-enabled=false`，把设备 `host/port/unit-id`
改成实际值即可；协议、解码与失败分类路径完全一致。

## 8. 包结构

```
model      FailReason / Quality / PointReading / DeviceStatus
config     PointType / ByteOrder / PointConfig / DeviceConfig / GatewayProperties
protocol   ModbusFrames / FramingBuffer / ModbusTcpClient / ModbusRequestException / HexUtils
codec      PointDecoder / DecodeException
collect    ConfigValidator / ReadingStore / DeviceCollectionEngine
mock       MockModbusServer（脚本化本地数据源）
service    GatewayManager（Spring 生命周期、退避重连、陈旧检测）
web        GatewayController（REST 查询接口）
```
