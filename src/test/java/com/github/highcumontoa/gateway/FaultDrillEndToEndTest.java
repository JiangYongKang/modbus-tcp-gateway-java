package com.github.highcumontoa.gateway;

import com.github.highcumontoa.gateway.model.FailureReason;
import com.github.highcumontoa.gateway.model.GatewayHealthSnapshot;
import com.github.highcumontoa.gateway.model.GatewayStatusSnapshot;
import com.github.highcumontoa.gateway.model.PointHealthSnapshot;
import com.github.highcumontoa.gateway.model.PointQuality;
import com.github.highcumontoa.gateway.model.ReadingSnapshot;
import com.github.highcumontoa.gateway.model.RegisterType;
import com.github.highcumontoa.gateway.simulator.FaultProfile;
import com.github.highcumontoa.gateway.simulator.FaultType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FaultDrillEndToEndTest {
    private static final Logger log = LoggerFactory.getLogger(FaultDrillEndToEndTest.class);

    @LocalServerPort
    int httpPort;

    @Autowired
    TestRestTemplate http;

    private String base;

    @DynamicPropertySource
    static void gatewayProperties(DynamicPropertyRegistry registry) throws IOException {
        int modbusPort = freePort();
        registry.add("gateway.local-source.port", () -> modbusPort);
        registry.add("gateway.client.port", () -> modbusPort);
        registry.add("gateway.client.response-timeout-ms", () -> "400");
        registry.add("gateway.client.connect-timeout-ms", () -> "400");
        registry.add("gateway.client.poll-interval-ms", () -> "50");
        registry.add("gateway.client.reconnect-delay-ms", () -> "50");
        registry.add("gateway.client.stale-threshold-ms", () -> "2000");
    }

    @BeforeEach
    void setUp() {
        base = "http://127.0.0.1:" + httpPort + "/api";
        awaitGood("temperature-c");
    }

    @AfterEach
    void tearDown() {
        http.delete(base + "/simulator/faults");
        http.delete(base + "/simulator/registers");
    }

    @Test
    void modbusExceptionFaultIsClassifiedAndRecovers() {
        ReadingSnapshot before = reading("temperature-c");

        FaultProfile fault = new FaultProfile();
        fault.setType(FaultType.MODBUS_EXCEPTION);
        fault.setPointName("temperature-c");
        fault.setExceptionCode(0x02);
        ResponseEntity<FaultProfile> created = http.postForEntity(
                base + "/simulator/faults", fault, FaultProfile.class);
        assertEquals(HttpStatus.OK, created.getStatusCode());
        assertNotNull(created.getBody().getId());
        log.warn("DRILL case=modbus-exception faultId={} basis=point-scoped exception injection",
                created.getBody().getId());

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            ReadingSnapshot snapshot = reading("temperature-c");
            assertEquals(FailureReason.MODBUS_EXCEPTION, snapshot.failureReason());
            assertNotEquals(PointQuality.GOOD, snapshot.quality());
        });

        PointHealthSnapshot inFault = pointHealth("temperature-c");
        assertTrue(inFault.faultActive());
        assertFalse(inFault.healthy());
        assertNotNull(inFault.lastFaultStartedAt());
        assertEquals(FailureReason.MODBUS_EXCEPTION, inFault.lastFailureReason());
        assertNull(inFault.lastRecoveredAt());
        assertFalse(gatewayHealth().healthy());
        log.warn("DRILL case=modbus-exception phase=injected startedAt={} reason={}",
                inFault.lastFaultStartedAt(), inFault.lastFailureReason());

        http.delete(base + "/simulator/faults");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            ReadingSnapshot snapshot = reading("temperature-c");
            assertEquals(PointQuality.GOOD, snapshot.quality());
            assertEquals(123.4d, snapshot.value());
        });
        PointHealthSnapshot recovered = pointHealth("temperature-c");
        assertFalse(recovered.faultActive());
        assertTrue(recovered.healthy());
        assertNotNull(recovered.lastRecoveredAt());
        assertEquals(123.4d, recovered.firstGoodValueAfterRecovery());
        assertFalse(recovered.lastRecoveredAt().isBefore(recovered.lastFaultStartedAt()));
        assertTrue(gatewayHealth().healthy());

        ReadingSnapshot after = reading("temperature-c");
        assertEquals(after.pollCount(), after.successCount() + after.failureCount());
        assertTrue(after.successCount() > before.successCount());
        assertTrue(after.failureCount() > before.failureCount());
        log.warn("DRILL case=modbus-exception phase=recovered recoveredAt={} firstGood={} "
                        + "poll={} success={} failure={} basis=counts stay consistent, no double count",
                recovered.lastRecoveredAt(), recovered.firstGoodValueAfterRecovery(),
                after.pollCount(), after.successCount(), after.failureCount());
    }

    @Test
    void timeoutFaultProducesTimeoutReasonAndRecovers() {
        FaultProfile fault = new FaultProfile();
        fault.setType(FaultType.TIMEOUT);
        fault.setPointName("temperature-c");
        http.postForEntity(base + "/simulator/faults", fault, FaultProfile.class);

        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            ReadingSnapshot snapshot = reading("temperature-c");
            assertEquals(FailureReason.TIMEOUT, snapshot.failureReason());
        });
        assertTrue(status().timeouts() >= 1);
        log.warn("DRILL case=timeout phase=injected timeouts={} basis=device swallows request, "
                + "gateway response-timeout fires", status().timeouts());

        http.delete(base + "/simulator/faults");
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            assertEquals(PointQuality.GOOD, reading("temperature-c").quality());
            assertEquals(123.4d, reading("temperature-c").value());
        });
        PointHealthSnapshot recovered = pointHealth("temperature-c");
        assertEquals(FailureReason.TIMEOUT, recovered.lastFailureReason());
        assertNotNull(recovered.lastRecoveredAt());
        assertEquals(123.4d, recovered.firstGoodValueAfterRecovery());
    }

    @Test
    void connectionDropFaultClosesConnectionAndGatewayReconnects() {
        GatewayStatusSnapshot baseline = status();

        FaultProfile fault = new FaultProfile();
        fault.setType(FaultType.CONNECTION_DROP);
        fault.setPointName("temperature-c");
        http.postForEntity(base + "/simulator/faults", fault, FaultProfile.class);

        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            ReadingSnapshot snapshot = reading("temperature-c");
            assertEquals(FailureReason.CONNECTION_CLOSED, snapshot.failureReason());
        });
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertTrue(status().disconnects() > baseline.disconnects()));
        log.warn("DRILL case=connection-drop phase=injected disconnects={} basis=device closes "
                + "socket without response", status().disconnects());

        http.delete(base + "/simulator/faults");
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            assertEquals(PointQuality.GOOD, reading("temperature-c").quality());
            assertEquals(123.4d, reading("temperature-c").value());
        });
        assertTrue(status().reconnects() >= 1);
    }

    @Test
    void fragmentedResponseStillDecodesToGoodReading() {
        FaultProfile fault = new FaultProfile();
        fault.setType(FaultType.FRAGMENT);
        fault.setPointName("temperature-c");
        fault.setFragmentCount(4);
        fault.setFragmentDelayMs(15);
        http.postForEntity(base + "/simulator/faults", fault, FaultProfile.class);

        ReadingSnapshot before = reading("temperature-c");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            ReadingSnapshot snapshot = reading("temperature-c");
            assertTrue(snapshot.successCount() >= before.successCount() + 3);
        });
        ReadingSnapshot after = reading("temperature-c");
        assertEquals(PointQuality.GOOD, after.quality());
        assertEquals(123.4d, after.value());
        assertEquals(before.failureCount(), after.failureCount());
        log.warn("DRILL case=fragment phase=injected raw={} basis=response split into 4 TCP chunks "
                + "still forms one valid frame", after.rawHex());
    }

    @Test
    void coalescedResponseIsCountedAsUnmatchedAndReadingStaysGood() {
        GatewayStatusSnapshot baseline = status();

        FaultProfile fault = new FaultProfile();
        fault.setType(FaultType.COALESCE);
        fault.setPointName("temperature-c");
        http.postForEntity(base + "/simulator/faults", fault, FaultProfile.class);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertTrue(status().unmatchedFrames() > baseline.unmatchedFrames()));
        ReadingSnapshot snapshot = reading("temperature-c");
        assertEquals(PointQuality.GOOD, snapshot.quality());
        assertEquals(123.4d, snapshot.value());
        log.warn("DRILL case=coalesce phase=injected unmatched={} basis=two frames glued in one "
                + "segment, duplicate txn id has no pending request", status().unmatchedFrames());
    }

    @Test
    void latencyFaultWithinTimeoutKeepsReadingsGood() {
        FaultProfile fault = new FaultProfile();
        fault.setType(FaultType.LATENCY);
        fault.setPointName("temperature-c");
        fault.setDelayMs(150);
        http.postForEntity(base + "/simulator/faults", fault, FaultProfile.class);

        ReadingSnapshot before = reading("temperature-c");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            ReadingSnapshot snapshot = reading("temperature-c");
            assertTrue(snapshot.successCount() >= before.successCount() + 2);
        });
        ReadingSnapshot after = reading("temperature-c");
        assertEquals(PointQuality.GOOD, after.quality());
        assertEquals(before.failureCount(), after.failureCount());
        log.warn("DRILL case=latency phase=injected delayMs=150 basis=delay below 400ms "
                + "response-timeout keeps readings GOOD");
    }

    @Test
    void registerOverrideChangesDecodedValuesAndResetRestores() {
        awaitGood("device-count");

        List<Map<String, Object>> overrides = List.of(
                Map.of("registerType", "HOLDING", "address", 0, "value", 500),
                Map.of("registerType", "HOLDING", "address", 1, "value", 0x0000),
                Map.of("registerType", "HOLDING", "address", 2, "value", 0x0064));
        ResponseEntity<Void> response = http.exchange(base + "/simulator/registers",
                HttpMethod.PUT, new HttpEntity<>(overrides), Void.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        log.warn("DRILL case=register-override phase=injected raw=[0x01F4,0x0000,0x0064] "
                + "basis=runtime register rewrite without restart");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertEquals(50.0d, reading("temperature-c").value());
            assertEquals(100.0d, reading("device-count").value());
        });
        Map<String, List<Integer>> banks = http.exchange(base + "/simulator/registers",
                HttpMethod.GET, null, new ParameterizedTypeReference<Map<String, List<Integer>>>() {
                }).getBody();
        assertEquals(500, banks.get("holding").get(0));

        http.delete(base + "/simulator/registers");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertEquals(123.4d, reading("temperature-c").value());
            assertEquals(305419896.0d, reading("device-count").value());
        });
        log.warn("DRILL case=register-override phase=reset basis=initial deterministic image restored");
    }

    @Test
    void sameRawBytesDecodeDifferentlyUnderEachConvention() {
        awaitGood("pressure-bar");
        List<Map<String, Object>> overrides = List.of(
                Map.of("registerType", "HOLDING", "address", 3, "value", 0x41CC),
                Map.of("registerType", "HOLDING", "address", 4, "value", 0x0000),
                Map.of("registerType", "HOLDING", "address", 13, "value", 0x41CC),
                Map.of("registerType", "HOLDING", "address", 14, "value", 0x0000),
                Map.of("registerType", "HOLDING", "address", 15, "value", 0x41CC),
                Map.of("registerType", "HOLDING", "address", 16, "value", 0x0000));
        http.exchange(base + "/simulator/registers", HttpMethod.PUT,
                new HttpEntity<>(overrides), Void.class);

        double expectedLowWordFirst = Float.intBitsToFloat(0x000041CC);
        double expectedMixedEndian = Float.intBitsToFloat(0x0000CC41);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertEquals(25.5d, reading("pressure-bar").value());
            assertEquals(expectedLowWordFirst, reading("pressure-low-word-first").value());
            assertEquals(expectedMixedEndian, reading("mixed-endian-float").value());
        });
        assertNotEquals(reading("pressure-bar").value(), reading("pressure-low-word-first").value());
        assertNotEquals(reading("pressure-low-word-first").value(),
                reading("mixed-endian-float").value());
        log.warn("DRILL case=decode-conventions raw=[0x41CC,0x0000] bigEndianHighFirst=25.5 "
                        + "lowWordFirst={} mixedEndian={} basis=same bytes, per-convention results "
                        + "are deterministic and distinguishable",
                expectedLowWordFirst, expectedMixedEndian);
    }

    @Test
    void outOfRangeValueLandsOnSpecificPointOnly() {
        List<Map<String, Object>> overrides = List.of(
                Map.of("registerType", "HOLDING", "address", 0, "value", 0x4E20));
        http.exchange(base + "/simulator/registers", HttpMethod.PUT,
                new HttpEntity<>(overrides), Void.class);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            ReadingSnapshot snapshot = reading("temperature-c");
            assertEquals(FailureReason.OUT_OF_RANGE, snapshot.failureReason());
            assertNotEquals(PointQuality.GOOD, snapshot.quality());
        });
        ReadingSnapshot faulty = reading("temperature-c");
        assertEquals(123.4d, faulty.value());
        assertEquals(123.4d, faulty.lastGoodValue());
        ReadingSnapshot neighbour = reading("signed-offset");
        assertEquals(PointQuality.GOOD, neighbour.quality());
        assertEquals(-10.0d, neighbour.value());
        log.warn("DRILL case=out-of-range phase=injected raw=0x4E20 scaled=2000.0 max=200 "
                + "basis=range violation stays on temperature-c, last good value retained, "
                + "neighbour point unaffected");

        http.delete(base + "/simulator/registers");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertEquals(PointQuality.GOOD, reading("temperature-c").quality());
            assertEquals(123.4d, reading("temperature-c").value());
        });
    }

    @Test
    void concurrentQueriesDuringFaultStayConsistent() throws Exception {
        ReadingSnapshot neighbourBaseline = reading("signed-offset");
        FaultProfile fault = new FaultProfile();
        fault.setType(FaultType.MODBUS_EXCEPTION);
        fault.setPointName("temperature-c");
        http.postForEntity(base + "/simulator/faults", fault, FaultProfile.class);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertTrue(pointHealth("temperature-c").faultActive()));

        int threads = 8;
        int iterations = 25;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    for (int j = 0; j < iterations; j++) {
                        assertOk(http.getForEntity(base + "/readings", String.class));
                        assertOk(http.getForEntity(base + "/readings/temperature-c", String.class));
                        assertOk(http.getForEntity(base + "/readings/temperature-c/health", String.class));
                        assertOk(http.getForEntity(base + "/health", String.class));
                        assertOk(http.getForEntity(base + "/status", String.class));
                    }
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    done.countDown();
                }
            });
            thread.start();
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        assertTrue(errors.isEmpty(), () -> "concurrent query errors: " + errors);

        ReadingSnapshot snapshot = reading("temperature-c");
        assertEquals(snapshot.pollCount(), snapshot.successCount() + snapshot.failureCount());
        ReadingSnapshot neighbour = reading("signed-offset");
        assertEquals(PointQuality.GOOD, neighbour.quality());
        assertEquals(neighbourBaseline.failureCount(), neighbour.failureCount());
        assertEquals(neighbour.pollCount(), neighbour.successCount() + neighbour.failureCount());
        log.warn("DRILL case=concurrent-queries temperature={}/{} neighbourFailures={} "
                        + "basis=concurrent reads do not drift counts or leak faults across points",
                snapshot.successCount(), snapshot.failureCount(), neighbour.failureCount());

        http.delete(base + "/simulator/faults");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertTrue(pointHealth("temperature-c").healthy()));
    }

    @Test
    void invalidFaultRequestsAreRejected() {
        FaultProfile unknownPoint = new FaultProfile();
        unknownPoint.setType(FaultType.TIMEOUT);
        unknownPoint.setPointName("does-not-exist");
        ResponseEntity<String> unknown = http.postForEntity(base + "/simulator/faults",
                unknownPoint, String.class);
        assertEquals(HttpStatus.BAD_REQUEST, unknown.getStatusCode());

        FaultProfile noType = new FaultProfile();
        ResponseEntity<String> missing = http.postForEntity(base + "/simulator/faults",
                noType, String.class);
        assertEquals(HttpStatus.BAD_REQUEST, missing.getStatusCode());

        ResponseEntity<String> unknownDelete = http.exchange(base + "/simulator/faults/nope",
                HttpMethod.DELETE, null, String.class);
        assertEquals(HttpStatus.NOT_FOUND, unknownDelete.getStatusCode());
        assertTrue(http.getForEntity(base + "/simulator/faults", List.class)
                .getBody().isEmpty());
    }

    private void awaitGood(String pointName) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            ReadingSnapshot snapshot = reading(pointName);
            assertEquals(PointQuality.GOOD, snapshot.quality());
        });
    }

    private void assertOk(ResponseEntity<String> response) {
        if (response.getStatusCode() != HttpStatus.OK) {
            throw new AssertionError("Expected 200 but got " + response.getStatusCode());
        }
    }

    private ReadingSnapshot reading(String pointName) {
        return http.getForObject(base + "/readings/" + pointName, ReadingSnapshot.class);
    }

    private PointHealthSnapshot pointHealth(String pointName) {
        return http.getForObject(base + "/readings/" + pointName + "/health",
                PointHealthSnapshot.class);
    }

    private GatewayHealthSnapshot gatewayHealth() {
        return http.getForObject(base + "/health", GatewayHealthSnapshot.class);
    }

    private GatewayStatusSnapshot status() {
        return http.getForObject(base + "/status", GatewayStatusSnapshot.class);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
