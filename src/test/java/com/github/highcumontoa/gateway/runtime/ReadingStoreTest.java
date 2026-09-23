package com.github.highcumontoa.gateway.runtime;

import com.github.highcumontoa.gateway.config.GatewayProperties;
import com.github.highcumontoa.gateway.model.FailureReason;
import com.github.highcumontoa.gateway.model.PointQuality;
import com.github.highcumontoa.gateway.model.ReadingSnapshot;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReadingStoreTest {
    private static final Logger log = LoggerFactory.getLogger(ReadingStoreTest.class);

    @Test
    void classifiesBadDegradedAndStaleReadings() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        ReadingStore store = new ReadingStore(properties(), clock);
        store.initialize(properties().getPoints());

        ReadingSnapshot initial = store.snapshot("temperature");
        store.recordFailure("temperature", FailureReason.TIMEOUT, "no response", "", 1);
        ReadingSnapshot bad = store.snapshot("temperature");
        store.recordSuccess("temperature", 25.5d, "41CC0000", 2);
        clock.advanceSeconds(1);
        store.recordFailure("temperature", FailureReason.CONNECTION_CLOSED, "socket closed", "", 3);
        ReadingSnapshot degraded = store.snapshot("temperature");
        clock.advanceSeconds(2);
        ReadingSnapshot stale = store.snapshot("temperature");

        log.info("STATE_TEST initialQuality={} bad={} degraded={} stale={} lastGood={} basis=stale threshold is 2 seconds",
                initial.quality(), bad.quality(), degraded.quality(), stale.quality(),
                stale.lastGoodValue());
        assertNull(initial.value());
        assertEquals(PointQuality.BAD, initial.quality());
        assertEquals(PointQuality.BAD, bad.quality());
        assertNull(bad.lastGoodValue());
        assertEquals(PointQuality.DEGRADED, degraded.quality());
        assertEquals(25.5d, degraded.value());
        assertEquals(PointQuality.STALE, stale.quality());
        assertEquals(FailureReason.CONNECTION_CLOSED, stale.failureReason());
    }

    @Test
    void keepsCountersConsistentUnderConcurrentPolls() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        GatewayProperties properties = properties();
        ReadingStore store = new ReadingStore(properties, clock);
        store.initialize(properties.getPoints());
        int threads = 64;
        int iterations = 200;
        ExecutorService executor = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        if (i % 3 == 0) {
                            store.recordFailure("temperature", FailureReason.TIMEOUT,
                                    "simulated timeout", "", i + 1L);
                        } else {
                            store.recordSuccess("temperature", 1.0d + (i % 5), "0001", i + 1L);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        ReadingSnapshot snapshot = store.snapshot("temperature");
        long expectedFailures = 0;
        for (int i = 0; i < iterations; i++) {
            if (i % 3 == 0) {
                expectedFailures++;
            }
        }
        long expectedTotal = (long) threads * iterations;
        long expectedFailureTotal = expectedFailures * threads;

        log.info("STATE_TEST point=temperature polls={} success={} failure={} basis=each poll performs exactly one atomic state update",
                snapshot.pollCount(), snapshot.successCount(), snapshot.failureCount());
        assertEquals(expectedTotal, snapshot.pollCount());
        assertEquals(expectedTotal, snapshot.successCount() + snapshot.failureCount());
        assertEquals(expectedFailureTotal, snapshot.failureCount());
        assertEquals(expectedTotal - expectedFailureTotal, snapshot.successCount());
    }

    private static GatewayProperties properties() {
        GatewayProperties properties = new GatewayProperties();
        properties.getClient().setStaleThresholdMs(2_000);
        GatewayProperties.PointDefinition point = new GatewayProperties.PointDefinition();
        point.setName("temperature");
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
