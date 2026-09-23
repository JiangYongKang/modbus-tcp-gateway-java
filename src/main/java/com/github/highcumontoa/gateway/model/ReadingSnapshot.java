package com.github.highcumontoa.gateway.model;

import java.time.Instant;

public record ReadingSnapshot(
        String pointName,
        Double value,
        Double lastGoodValue,
        PointQuality quality,
        FailureReason failureReason,
        String message,
        String rawHex,
        long cycle,
        Instant updatedAt,
        Instant lastGoodAt,
        long pollCount,
        long successCount,
        long failureCount) {
}
