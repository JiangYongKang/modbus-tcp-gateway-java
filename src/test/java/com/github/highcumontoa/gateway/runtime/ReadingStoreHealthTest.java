package com.github.highcumontoa.gateway.runtime;

import com.github.highcumontoa.gateway.config.GatewayProperties;
import com.github.highcumontoa.gateway.model.DataType;
import com.github.highcumontoa.gateway.model.FailureReason;
import com.github.highcumontoa.gateway.model.GatewayHealthSnapshot;
import com.github.highcumontoa.gateway.model.PointHealthSnapshot;
import com.github.highcumontoa.gateway.model.PointQuality;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadingStoreHealthTest {
    private static final Logger log = LoggerFactory.getLogger(ReadingStoreHealthTest.class);

    @Test
    void tracksFaultEpisodeFromStartToRecovery() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        ReadingStore store = new ReadingStore(properties(), clock);
        store.initialize(properties().getPoints());

        store.recordSuccess("temperature", 123.4d, "00010000000501030204D2", 1);
        PointHealthSnapshot healthy = store.healthSnapshot("temperature");
        assertTrue(healthy.healthy());
        assertFalse(healthy.faultActive());
        assertNull(healthy.lastFaultStartedAt());

        clock.advanceSeconds(1);
        store.recordFailure("temperature", FailureReason.TIMEOUT, "no response", "", 2);
        clock.advanceSeconds(1);
        store.recordFailure("temperature", FailureReason.TIMEOUT, "no response again", "", 3);
        PointHealthSnapshot inFault = store.healthSnapshot("temperature");
        log.info("HEALTH_TEST phase=inFault startedAt={} reason={} basis=first failure opens episode, "
                        + "later failures do not move start time",
                inFault.lastFaultStartedAt(), inFault.lastFailureReason());
        assertFalse(inFault.healthy());
        assertTrue(inFault.faultActive());
        assertEquals(Instant.parse("2026-01-01T00:00:01Z"), inFault.lastFaultStartedAt());
        assertEquals(FailureReason.TIMEOUT, inFault.lastFailureReason());
        assertNull(inFault.lastRecoveredAt());
        assertNull(inFault.firstGoodValueAfterRecovery());

        clock.advanceSeconds(1);
        store.recordSuccess("temperature", 55.5d, "000200000005010302022B", 4);
        PointHealthSnapshot recovered = store.healthSnapshot("temperature");
        log.info("HEALTH_TEST phase=recovered recoveredAt={} firstGood={} basis=first success after "
                        + "fault closes episode and becomes first valid value",
                recovered.lastRecoveredAt(), recovered.firstGoodValueAfterRecovery());
        assertTrue(recovered.healthy());
        assertFalse(recovered.faultActive());
        assertEquals(Instant.parse("2026-01-01T00:00:01Z"), recovered.lastFaultStartedAt());
        assertEquals(FailureReason.TIMEOUT, recovered.lastFailureReason());
        assertEquals(Instant.parse("2026-01-01T00:00:03Z"), recovered.lastRecoveredAt());
        assertEquals(55.5d, recovered.firstGoodValueAfterRecovery());
    }

    @Test
    void gatewayHealthRequiresConnectionAndAllPointsHealthy() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        GatewayProperties properties = properties();
        ReadingStore store = new ReadingStore(properties, clock);
        store.initialize(properties.getPoints());

        store.recordConnected();
        store.recordSuccess("temperature", 123.4d, "raw", 1);
        GatewayHealthSnapshot allGood = store.health();
        assertTrue(allGood.healthy());
        assertEquals(1, allGood.healthyPoints());

        store.recordFailure("temperature", FailureReason.MODBUS_EXCEPTION, "exception 02", "", 2);
        GatewayHealthSnapshot inFault = store.health();
        log.info("HEALTH_TEST healthy={} healthyPoints={} basis=any active fault marks gateway unhealthy",
                inFault.healthy(), inFault.healthyPoints());
        assertFalse(inFault.healthy());
        assertEquals(0, inFault.healthyPoints());
        assertEquals("CONNECTED", inFault.connectionState());

        store.recordDisconnected("DISCONNECTED", "drop");
        store.recordSuccess("temperature", 123.4d, "raw", 3);
        GatewayHealthSnapshot disconnected = store.health();
        assertFalse(disconnected.healthy());
        assertEquals("DISCONNECTED", disconnected.connectionState());
    }

    @Test
    void healthSnapshotReflectsCurrentQuality() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        ReadingStore store = new ReadingStore(properties(), clock);
        store.initialize(properties().getPoints());

        store.recordFailure("temperature", FailureReason.DECODE_ERROR, "bad registers", "", 1);
        PointHealthSnapshot bad = store.healthSnapshot("temperature");
        assertEquals(PointQuality.BAD, bad.quality());
        assertEquals(FailureReason.DECODE_ERROR, bad.lastFailureReason());
    }

    private static GatewayProperties properties() {
        GatewayProperties properties = new GatewayProperties();
        properties.getClient().setStaleThresholdMs(2_000);
        GatewayProperties.PointDefinition point = new GatewayProperties.PointDefinition();
        point.setName("temperature");
        point.setDataType(DataType.INT16);
        properties.setPoints(List.of(point));
        return properties;
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
