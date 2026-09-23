package com.github.highcumontoa.modbustcpgatewayjava.config;

import java.util.List;

/**
 * 一台设备（一条 Modbus TCP 链路）的配置。
 */
public record DeviceConfig(
        String id,
        String host,
        int port,
        int unitId,
        int connectTimeoutMs,
        int responseTimeoutMs,
        List<PointConfig> points) {
}
