package com.github.highcumontoa.gateway.fault;

public enum FaultType {
    /** 响应变慢：延迟 delayMs 后再返回正常响应。 */
    RESPONSE_DELAY,
    /** 永不响应：请求被吞掉，客户端表现为超时。 */
    TIMEOUT,
    /** 设备回异常：返回 Modbus 异常功能码与 exceptionCode。 */
    MODBUS_EXCEPTION,
    /** 连接被掐断：命中请求时直接关闭 TCP 连接。 */
    CONNECTION_DROP,
    /** 响应被拆成好几段：按 chunkSize 分段、间隔 chunkDelayMs 发送。 */
    FRAGMENTED_RESPONSE,
    /** 几段粘在一起：同一帧被复制后一次 flush 发出，验证粘包拆分与 unmatched 分类。 */
    COALESCED_RESPONSE
}
