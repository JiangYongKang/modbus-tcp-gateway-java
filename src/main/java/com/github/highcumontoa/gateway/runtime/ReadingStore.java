package com.github.highcumontoa.gateway.runtime;

import com.github.highcumontoa.gateway.config.GatewayProperties;
import com.github.highcumontoa.gateway.model.FailureReason;
import com.github.highcumontoa.gateway.model.GatewayHealthSnapshot;
import com.github.highcumontoa.gateway.model.GatewayStatusSnapshot;
import com.github.highcumontoa.gateway.model.PointHealthSnapshot;
import com.github.highcumontoa.gateway.model.PointQuality;
import com.github.highcumontoa.gateway.model.ReadingSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ReadingStore {
    private final GatewayProperties properties;
    private final Clock clock;
    private final ConcurrentHashMap<String, ReadingState> readings = new ConcurrentHashMap<>();
    private final Object statusLock = new Object();
    private volatile List<String> pointOrder = List.of();
    private volatile String connectionState = "STOPPED";
    private volatile String remoteEndpoint = "";
    private volatile long cycle;
    private volatile long connectionAttempts;
    private volatile long successfulConnections;
    private volatile long disconnects;
    private volatile long reconnects;
    private volatile long timeouts;
    private volatile long invalidFrames;
    private volatile long unmatchedFrames;
    private volatile Instant lastConnectedAt;
    private volatile Instant lastDisconnectedAt;
    private volatile String lastConnectionError = "";
    private final EnumMap<FailureReason, Long> failuresByReason = new EnumMap<>(FailureReason.class);

    @Autowired
    public ReadingStore(GatewayProperties properties) {
        this(properties, Clock.systemUTC());
    }

    public ReadingStore(GatewayProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.remoteEndpoint = properties.getClient().getHost() + ':' + properties.getClient().getPort();
    }

    public void initialize(List<GatewayProperties.PointDefinition> points) {
        readings.clear();
        List<String> order = new ArrayList<>(points.size());
        for (GatewayProperties.PointDefinition point : points) {
            order.add(point.getName());
            readings.put(point.getName(), new ReadingState(point.getName()));
        }
        pointOrder = List.copyOf(order);
        synchronized (statusLock) {
            cycle = 0;
            connectionState = "DISCONNECTED";
        }
    }

    public void beginCycle() {
        synchronized (statusLock) {
            cycle++;
        }
    }

    public void recordConnectionAttempt(String state) {
        synchronized (statusLock) {
            connectionAttempts++;
            connectionState = state;
        }
    }

    public void recordConnected() {
        synchronized (statusLock) {
            if (successfulConnections > 0) {
                reconnects++;
            }
            successfulConnections++;
            connectionState = "CONNECTED";
            lastConnectedAt = clock.instant();
            lastConnectionError = "";
        }
    }

    public void recordDisconnected(String state, String message) {
        synchronized (statusLock) {
            disconnects++;
            connectionState = state;
            lastDisconnectedAt = clock.instant();
            lastConnectionError = message == null ? "" : message;
        }
    }

    public void recordUnmatchedFrame() {
        synchronized (statusLock) {
            unmatchedFrames++;
        }
    }

    public void recordFailure(FailureReason reason, String message, String rawHex) {
        if (reason == null) {
            reason = FailureReason.UNKNOWN;
        }
        synchronized (statusLock) {
            failuresByReason.merge(reason, 1L, Long::sum);
            if (reason == FailureReason.TIMEOUT) {
                timeouts++;
            }
            if (reason == FailureReason.FRAMING_ERROR || reason == FailureReason.MALFORMED_RESPONSE
                    || reason == FailureReason.PROTOCOL_ERROR) {
                invalidFrames++;
            }
            lastConnectionError = message == null ? "" : message;
        }
    }

    public void recordSuccess(String pointName, double value, String rawHex, long cycle) {
        ReadingState state = requireState(pointName);
        synchronized (state) {
            Instant now = clock.instant();
            if (state.faultStartedAt != null) {
                state.lastRecoveredAt = now;
                state.firstGoodValueAfterRecovery = value;
                state.faultStartedAt = null;
            }
            state.value = value;
            state.lastGoodValue = value;
            state.quality = PointQuality.GOOD;
            state.failureReason = null;
            state.message = "OK";
            state.rawHex = rawHex;
            state.cycle = cycle;
            state.updatedAt = now;
            state.lastGoodAt = now;
            state.pollCount++;
            state.successCount++;
        }
    }

    public void recordFailure(String pointName, FailureReason reason, String message,
                              String rawHex, long cycle) {
        if (reason == null) {
            reason = FailureReason.UNKNOWN;
        }
        ReadingState state = requireState(pointName);
        synchronized (state) {
            Instant now = clock.instant();
            if (state.faultStartedAt == null) {
                state.faultStartedAt = now;
                state.lastFaultStartedAt = now;
                state.lastRecoveredAt = null;
                state.firstGoodValueAfterRecovery = null;
            }
            state.cycle = cycle;
            state.updatedAt = now;
            state.failureReason = reason;
            state.lastFailureReason = reason;
            state.message = message == null ? "" : message;
            state.rawHex = rawHex == null ? "" : rawHex;
            state.value = state.lastGoodValue;
            state.quality = failedQuality(state.lastGoodAt);
            state.pollCount++;
            state.failureCount++;
        }
        synchronized (statusLock) {
            failuresByReason.merge(reason, 1L, Long::sum);
            if (reason == FailureReason.TIMEOUT) {
                timeouts++;
            }
            if (reason == FailureReason.FRAMING_ERROR || reason == FailureReason.MALFORMED_RESPONSE
                    || reason == FailureReason.PROTOCOL_ERROR) {
                invalidFrames++;
            }
        }
    }

    public List<ReadingSnapshot> snapshots() {
        List<ReadingSnapshot> result = new ArrayList<>(pointOrder.size());
        for (String pointName : pointOrder) {
            result.add(snapshot(pointName));
        }
        return result;
    }

    public ReadingSnapshot snapshot(String pointName) {
        ReadingState state = readings.get(pointName);
        if (state == null) {
            return null;
        }
        synchronized (state) {
            PointQuality quality = currentQuality(state);
            return new ReadingSnapshot(
                    state.pointName,
                    state.value == null ? null : state.value,
                    state.lastGoodValue == null ? null : state.lastGoodValue,
                    quality,
                    state.failureReason,
                    state.message,
                    state.rawHex,
                    state.cycle,
                    state.updatedAt,
                    state.lastGoodAt,
                    state.pollCount,
                    state.successCount,
                    state.failureCount);
        }
    }

    public GatewayStatusSnapshot status() {
        synchronized (statusLock) {
            Map<String, Long> failureCounts = new LinkedHashMap<>();
            failuresByReason.forEach((reason, count) -> failureCounts.put(reason.name(), count));
            return new GatewayStatusSnapshot(
                    connectionState,
                    remoteEndpoint,
                    cycle,
                    connectionAttempts,
                    successfulConnections,
                    disconnects,
                    reconnects,
                    timeouts,
                    invalidFrames,
                    unmatchedFrames,
                    lastConnectedAt,
                    lastDisconnectedAt,
                    lastConnectionError,
                    Map.copyOf(failureCounts));
        }
    }

    public PointHealthSnapshot healthSnapshot(String pointName) {
        ReadingState state = readings.get(pointName);
        if (state == null) {
            return null;
        }
        synchronized (state) {
            PointQuality quality = currentQuality(state);
            boolean faultActive = state.faultStartedAt != null;
            return new PointHealthSnapshot(
                    state.pointName,
                    !faultActive && quality == PointQuality.GOOD,
                    quality,
                    faultActive,
                    state.lastFaultStartedAt,
                    state.lastFailureReason,
                    state.lastRecoveredAt,
                    state.firstGoodValueAfterRecovery);
        }
    }

    public GatewayHealthSnapshot health() {
        List<PointHealthSnapshot> points = new ArrayList<>(pointOrder.size());
        for (String pointName : pointOrder) {
            points.add(healthSnapshot(pointName));
        }
        long healthyPoints = points.stream().filter(PointHealthSnapshot::healthy).count();
        boolean connected;
        synchronized (statusLock) {
            connected = "CONNECTED".equals(connectionState);
        }
        boolean healthy = connected && healthyPoints == points.size();
        return new GatewayHealthSnapshot(healthy, connected ? "CONNECTED" : connectionState,
                points.size(), healthyPoints, List.copyOf(points));
    }

    private ReadingState requireState(String pointName) {
        ReadingState state = readings.get(pointName);
        if (state == null) {
            throw new IllegalArgumentException("Unknown point: " + pointName);
        }
        return state;
    }

    private PointQuality failedQuality(Instant lastGoodAt) {
        if (lastGoodAt == null) {
            return PointQuality.BAD;
        }
        long ageMs = clock.instant().toEpochMilli() - lastGoodAt.toEpochMilli();
        return ageMs > properties.getClient().getStaleThresholdMs()
                ? PointQuality.STALE : PointQuality.DEGRADED;
    }

    private PointQuality currentQuality(ReadingState state) {
        if ((state.quality == PointQuality.GOOD || state.quality == PointQuality.DEGRADED)
                && state.lastGoodAt != null) {
            long ageMs = clock.instant().toEpochMilli() - state.lastGoodAt.toEpochMilli();
            return ageMs > properties.getClient().getStaleThresholdMs()
                    ? PointQuality.STALE : state.quality;
        }
        return state.quality;
    }

    private static final class ReadingState {
        private final String pointName;
        private Double value;
        private Double lastGoodValue;
        private PointQuality quality = PointQuality.BAD;
        private FailureReason failureReason;
        private String message = "No poll has completed";
        private String rawHex = "";
        private long cycle;
        private Instant updatedAt;
        private Instant lastGoodAt;
        private long pollCount;
        private long successCount;
        private long failureCount;
        private Instant faultStartedAt;
        private Instant lastFaultStartedAt;
        private FailureReason lastFailureReason;
        private Instant lastRecoveredAt;
        private Double firstGoodValueAfterRecovery;

        private ReadingState(String pointName) {
            this.pointName = pointName;
        }
    }
}
