package com.github.highcumontoa.gateway;

import com.github.highcumontoa.gateway.config.GatewayProperties;
import com.github.highcumontoa.gateway.decode.PointDecoder;
import com.github.highcumontoa.gateway.fault.FaultRegistry;
import com.github.highcumontoa.gateway.fault.FaultSpec;
import com.github.highcumontoa.gateway.fault.FaultType;
import com.github.highcumontoa.gateway.model.FailureReason;
import com.github.highcumontoa.gateway.model.PointQuality;
import com.github.highcumontoa.gateway.model.ReadingSnapshot;
import com.github.highcumontoa.gateway.runtime.ReadingStore;
import com.github.highcumontoa.gateway.simulator.LocalModbusDeviceServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端故障演练：整个 Spring 应用真实启动，本地数据源真实监听 TCP，
 * 通过运行期编排注册表注入/撤销故障，验证失败分类、恢复读数与计数一致性。
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.MethodName.class)
class FaultOrchestrationEndToEndTest {
    private static final Logger log = LoggerFactory.getLogger(FaultOrchestrationEndToEndTest.class);

    @Autowired
    FaultRegistry faultRegistry;
    @Autowired
    ReadingStore store;
    @Autowired
    LocalModbusDeviceServer device;
    @Autowired
    GatewayProperties properties;

    @BeforeEach
    void resetOrchestration() {
        faultRegistry.clear();
        device.resetRegisters();
    }

    @AfterEach
    void cleanupOrchestration() {
        faultRegistry.clear();
        device.resetRegisters();
    }

    @Test
    void timeoutFaultClassifiesAndRecoversAfterRevoke() {
        awaitGood("temperature-c", 123.4d);
        long successBefore = store.snapshot("temperature-c").successCount();

        FaultSpec fault = faultRegistry.inject(forPoint("temperature-c", FaultType.TIMEOUT));
        log.info("E2E_FAULT id={} type=TIMEOUT point=temperature-c basis=server swallows requests",
                fault.id());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            ReadingSnapshot snapshot = store.snapshot("temperature-c");
            assertEquals(FailureReason.TIMEOUT, snapshot.failureReason());
            assertTrue(snapshot.quality() != PointQuality.GOOD,
                    "faulting point must not expose its stale value as currently valid");
            assertTrue(!snapshot.healthy());
            assertNotNull(snapshot.faultStartedAt());
            assertTrue(!store.status().healthy());
            assertTrue(store.status().pointsInFault() >= 1);
        });
        ReadingSnapshot during = store.snapshot("temperature-c");
        log.info("E2E_FAULT_OBSERVED point=temperature-c reason={} quality={} startedAt={} "
                        + "healthy={} basis=timeout fault active",
                during.failureReason(), during.quality(), during.faultStartedAt(), during.healthy());

        faultRegistry.revoke(fault.id());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            ReadingSnapshot snapshot = store.snapshot("temperature-c");
            assertEquals(PointQuality.GOOD, snapshot.quality());
            assertEquals(123.4d, snapshot.value());
            assertEquals(123.4d, snapshot.recoveryValue());
            assertNotNull(snapshot.recoveredAt());
            assertEquals(FailureReason.TIMEOUT, snapshot.lastFailureReason(),
                    "last failure reason must survive recovery");
            assertTrue(snapshot.healthy());
            assertTrue(snapshot.successCount() > successBefore,
                    "recovered point must keep polling fresh values");
            assertEquals(snapshot.pollCount(), snapshot.successCount() + snapshot.failureCount(),
                    "counters must not drift or double count across recovery");
        });
        ReadingSnapshot recovered = store.snapshot("temperature-c");
        log.info("E2E_RECOVERY point=temperature-c recoveredAt={} recoveryValue={} polls={} "
                        + "success={} failure={} basis=first good poll after revoke",
                recovered.recoveredAt(), recovered.recoveryValue(), recovered.pollCount(),
                recovered.successCount(), recovered.failureCount());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(
                () -> assertTrue(store.status().healthy()));
    }

    @Test
    void modbusExceptionFaultClassifiesAndRecovers() {
        awaitGood("pressure-bar", 25.5d);

        FaultSpec fault = faultRegistry.inject(new FaultSpec(null,
                point("pressure-bar").getRegisterType(), point("pressure-bar").getAddress(),
                PointDecoder.requiredRegisters(point("pressure-bar").getDataType()),
                FaultType.MODBUS_EXCEPTION, 0, 0x02, 1, 0, FaultSpec.UNLIMITED));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            ReadingSnapshot snapshot = store.snapshot("pressure-bar");
            assertEquals(FailureReason.MODBUS_EXCEPTION, snapshot.failureReason());
            assertTrue(snapshot.quality() != PointQuality.GOOD);
        });
        log.info("E2E_FAULT_OBSERVED point=pressure-bar reason=MODBUS_EXCEPTION "
                + "basis=device returns exception code 0x02");

        faultRegistry.revoke(fault.id());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            ReadingSnapshot snapshot = store.snapshot("pressure-bar");
            assertEquals(PointQuality.GOOD, snapshot.quality());
            assertEquals(25.5d, snapshot.value());
            assertEquals(25.5d, snapshot.recoveryValue());
            assertEquals(FailureReason.MODBUS_EXCEPTION, snapshot.lastFailureReason());
        });
    }

    @Test
    void connectionDropFaultTriggersReconnectAndRecovery() {
        awaitGood("input-raw", 42500d);
        long reconnectsBefore = store.status().reconnects();

        FaultSpec fault = faultRegistry.inject(forPoint("input-raw", FaultType.CONNECTION_DROP));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            ReadingSnapshot snapshot = store.snapshot("input-raw");
            assertEquals(FailureReason.CONNECTION_CLOSED, snapshot.failureReason());
            assertTrue(store.status().reconnects() > reconnectsBefore);
        });
        log.info("E2E_FAULT_OBSERVED point=input-raw reason=CONNECTION_CLOSED "
                + "basis=server drops socket on matching request");

        faultRegistry.revoke(fault.id());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            ReadingSnapshot snapshot = store.snapshot("input-raw");
            assertEquals(PointQuality.GOOD, snapshot.quality());
            assertEquals(42500d, snapshot.value());
            assertEquals(42500d, snapshot.recoveryValue());
        });
    }

    @Test
    void fragmentedResponseStillDecodesWithoutFailures() {
        awaitGood("device-count", 305419896d);
        long failuresBefore = store.snapshot("device-count").failureCount();
        long pollsBefore = store.snapshot("device-count").pollCount();

        faultRegistry.inject(new FaultSpec(null,
                point("device-count").getRegisterType(), point("device-count").getAddress(),
                PointDecoder.requiredRegisters(point("device-count").getDataType()),
                FaultType.FRAGMENTED_RESPONSE, 0, 0, 1, 2, FaultSpec.UNLIMITED));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            ReadingSnapshot snapshot = store.snapshot("device-count");
            assertTrue(snapshot.pollCount() >= pollsBefore + 3);
            assertEquals(PointQuality.GOOD, snapshot.quality());
            assertEquals(305419896d, snapshot.value());
            assertEquals(failuresBefore, snapshot.failureCount(),
                    "fragmented responses must be reassembled, not counted as failures");
        });
        log.info("E2E_FRAGMENT point=device-count basis=1-byte chunks reassembled by MBAP length");
    }

    @Test
    void coalescedResponseDecodesAndCountsUnmatchedDuplicate() {
        awaitGood("signed-offset", -10d);
        long unmatchedBefore = store.status().unmatchedFrames();

        faultRegistry.inject(forPoint("signed-offset", FaultType.COALESCED_RESPONSE));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            ReadingSnapshot snapshot = store.snapshot("signed-offset");
            assertEquals(PointQuality.GOOD, snapshot.quality());
            assertEquals(-10d, snapshot.value());
            assertTrue(store.status().unmatchedFrames() > unmatchedBefore,
                    "duplicate frame glued into one segment must surface as unmatched");
        });
        log.info("E2E_COALESCE point=signed-offset unmatched={} basis=duplicate frame in one flush",
                store.status().unmatchedFrames());
    }

    @Test
    void runtimeRegisterOverrideDistinguishesDecodeConventions() {
        awaitGood("device-count", 305419896d);

        // 同一组原始字节 0x0102 0x0304 写入三个不同约定的 32 位点
        device.writeRegisters(com.github.highcumontoa.gateway.model.RegisterType.HOLDING,
                1, new int[]{0x0102, 0x0304});   // device-count: BIG_ENDIAN + HIGH_WORD_FIRST
        device.writeRegisters(com.github.highcumontoa.gateway.model.RegisterType.HOLDING,
                13, new int[]{0x0102, 0x0304});  // pressure-low-word-first: LOW_WORD_FIRST
        device.writeRegisters(com.github.highcumontoa.gateway.model.RegisterType.HOLDING,
                15, new int[]{0x0102, 0x0304});  // mixed-endian-float: LITTLE_ENDIAN + LOW_WORD_FIRST
        // 同值 0xFFF6 的符号约定：INT16 vs UINT16
        device.writeRegisters(com.github.highcumontoa.gateway.model.RegisterType.HOLDING,
                5, new int[]{0xFFF6});           // signed-offset: INT16
        device.writeRegisters(com.github.highcumontoa.gateway.model.RegisterType.INPUT,
                0, new int[]{0xFFF6});           // input-raw: UINT16

        double expectedBeHwf = 0x01020304L;                 // 16909060
        double expectedLwf = Float.intBitsToFloat(0x03040102);
        double expectedLeLwf = Float.intBitsToFloat(0x04030201);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertEquals(expectedBeHwf, store.snapshot("device-count").value());
            assertEquals(expectedLwf, store.snapshot("pressure-low-word-first").value());
            assertEquals(expectedLeLwf, store.snapshot("mixed-endian-float").value());
            assertEquals(-10d, store.snapshot("signed-offset").value());
            assertEquals(65526d, store.snapshot("input-raw").value());
        });
        log.info("E2E_DECODE raw=0102,0304 BE+HWF={} LWF={} LE+LWF={} raw=FFF6 INT16={} UINT16={} "
                        + "basis=same bytes distinguishable under each convention",
                expectedBeHwf, expectedLwf, expectedLeLwf, -10d, 65526d);
        assertTrue(expectedBeHwf != expectedLwf && expectedLwf != expectedLeLwf,
                "conventions must produce distinguishable results");

        device.resetRegisters();
        awaitGood("device-count", 305419896d);
        awaitGood("pressure-low-word-first", 25.5d);
        awaitGood("mixed-endian-float", 25.5d);
        awaitGood("input-raw", 42500d);
        log.info("E2E_DECODE_RESET basis=factory register image restored without restart");
    }

    @Test
    void outOfRangeOverrideLandsOnSpecificPointOnly() {
        awaitGood("temperature-c", 123.4d);

        // 250.0 超出 temperature-c 的 max=200
        device.writeRegisters(com.github.highcumontoa.gateway.model.RegisterType.HOLDING,
                0, new int[]{0x09C4});

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            ReadingSnapshot snapshot = store.snapshot("temperature-c");
            assertEquals(FailureReason.OUT_OF_RANGE, snapshot.failureReason());
            assertTrue(snapshot.quality() != PointQuality.GOOD);
            assertEquals(PointQuality.GOOD, store.snapshot("pressure-bar").quality(),
                    "out-of-range must land on the specific point only");
        });
        log.info("E2E_RANGE point=temperature-c reason=OUT_OF_RANGE basis=250.0 above max 200");

        device.resetRegisters();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            ReadingSnapshot snapshot = store.snapshot("temperature-c");
            assertEquals(PointQuality.GOOD, snapshot.quality());
            assertEquals(123.4d, snapshot.value());
            assertEquals(123.4d, snapshot.recoveryValue());
        });
    }

    @Test
    void concurrentQueriesStayConsistentDuringFault() throws Exception {
        awaitGood("temperature-c", 123.4d);
        FaultSpec fault = faultRegistry.inject(forPoint("temperature-c", FaultType.TIMEOUT));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertEquals(FailureReason.TIMEOUT, store.snapshot("temperature-c").failureReason()));

        int threads = 8;
        int iterations = 200;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<AssertionError> violations = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        for (ReadingSnapshot snapshot : store.snapshots()) {
                            assertEquals(snapshot.pollCount(),
                                    snapshot.successCount() + snapshot.failureCount());
                            assertNotNull(snapshot.pointName());
                        }
                        ReadingSnapshot single = store.snapshot("temperature-c");
                        assertEquals("temperature-c", single.pointName());
                        store.status();
                    }
                } catch (AssertionError e) {
                    synchronized (violations) {
                        violations.add(e);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        executor.shutdown();
        faultRegistry.revoke(fault.id());

        assertTrue(violations.isEmpty(), "concurrent queries must not observe drift: " + violations);
        log.info("E2E_CONCURRENCY threads={} iterations={} basis=poll==success+failure for every point",
                threads, iterations);
    }

    private void awaitGood(String pointName, double expected) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            ReadingSnapshot snapshot = store.snapshot(pointName);
            assertEquals(PointQuality.GOOD, snapshot.quality());
            assertEquals(expected, snapshot.value());
        });
    }

    private GatewayProperties.PointDefinition point(String name) {
        return properties.getPoints().stream()
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unknown point: " + name));
    }

    private FaultSpec forPoint(String pointName, FaultType type) {
        GatewayProperties.PointDefinition point = point(pointName);
        return new FaultSpec(null, point.getRegisterType(), point.getAddress(),
                PointDecoder.requiredRegisters(point.getDataType()), type,
                0, 0, 1, 0, FaultSpec.UNLIMITED);
    }
}
