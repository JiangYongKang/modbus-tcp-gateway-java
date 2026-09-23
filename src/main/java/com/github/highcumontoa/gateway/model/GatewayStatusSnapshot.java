package com.github.highcumontoa.gateway.model;

import java.time.Instant;
import java.util.Map;

public record GatewayStatusSnapshot(
        String connectionState,
        String remoteEndpoint,
        long cycle,
        long connectionAttempts,
        long successfulConnections,
        long disconnects,
        long reconnects,
        long timeouts,
        long invalidFrames,
        long unmatchedFrames,
        Instant lastConnectedAt,
        Instant lastDisconnectedAt,
        String lastConnectionError,
        Map<String, Long> failuresByReason) {
}
