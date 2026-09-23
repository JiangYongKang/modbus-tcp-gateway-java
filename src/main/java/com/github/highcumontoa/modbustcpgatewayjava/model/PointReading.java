package com.github.highcumontoa.modbustcpgatewayjava.model;

/**
 * 单个数据点的一次读数快照（不可变）。
 *
 * @param value      解码并缩放后的工程量值；失败时为 null
 * @param rawHex     原始寄存器字节的十六进制文本，用于日志与测试断言
 * @param quality    质量标记
 * @param failReason 失败原因；GOOD 时为 null
 * @param message    判定依据 / 说明文本
 * @param sampledAt  采样时刻（epoch millis）
 */
public record PointReading(
        String deviceId,
        String pointId,
        Double value,
        String rawHex,
        Quality quality,
        FailReason failReason,
        String message,
        long sampledAt) {
}
