package com.github.highcumontoa.modbustcpgatewayjava.model;

/**
 * 设备（一条 TCP 采集链路）的运行状态快照。
 */
public record DeviceStatus(
        String deviceId,
        String state,
        boolean connected,
        long pollCount,
        long successCount,
        long failureCount,
        long timeoutCount,
        long reconnectCount,
        long consecutiveFailures,
        long lastPollAt,
        long lastSuccessAt,
        long lastFailureAt,
        FailReason lastFailReason,
        String lastError) {
}
