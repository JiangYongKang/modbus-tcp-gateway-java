package com.github.highcumontoa.modbustcpgatewayjava.config;

/**
 * 单个数据点的声明式配置（保持寄存器 FC=0x03）。
 *
 * @param scale   缩放系数，工程量 = 原始值 * scale + offset
 * @param offset  偏移量
 * @param min     工程量合法下限（闭区间），null 表示不校验
 * @param max     工程量合法上限（闭区间），null 表示不校验
 */
public record PointConfig(
        String id,
        int address,
        PointType type,
        ByteOrder order,
        double scale,
        double offset,
        Double min,
        Double max) {
}
