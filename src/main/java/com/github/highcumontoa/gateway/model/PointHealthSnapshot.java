package com.github.highcumontoa.gateway.model;

import java.time.Instant;

public record PointHealthSnapshot(
        String pointName,
        boolean healthy,
        PointQuality quality,
        boolean faultActive,
        Instant lastFaultStartedAt,
        FailureReason lastFailureReason,
        Instant lastRecoveredAt,
        Double firstGoodValueAfterRecovery) {
}
