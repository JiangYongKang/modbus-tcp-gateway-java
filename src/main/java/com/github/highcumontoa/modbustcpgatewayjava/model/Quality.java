package com.github.highcumontoa.modbustcpgatewayjava.model;

/**
 * 读数质量标记。陈旧 / 降级数据不得标记为 GOOD。
 */
public enum Quality {
    /** 本轮采集成功，数值新鲜可信 */
    GOOD,
    /** 降级值（例如重连后首轮、数据不完整但保留上一次有效值） */
    UNCERTAIN,
    /** 上一次成功值已超过陈旧阈值 */
    STALE,
    /** 采集或解码失败，value 不可用 */
    BAD
}
