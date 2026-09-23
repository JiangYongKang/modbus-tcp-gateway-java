package com.github.highcumontoa.modbustcpgatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.highcumontoa.modbustcpgatewayjava.collect.DeviceCollectionEngine;
import com.github.highcumontoa.modbustcpgatewayjava.collect.ReadingStore;
import com.github.highcumontoa.modbustcpgatewayjava.config.ByteOrder;
import com.github.highcumontoa.modbustcpgatewayjava.config.DeviceConfig;
import com.github.highcumontoa.modbustcpgatewayjava.config.GatewayProperties;
import com.github.highcumontoa.modbustcpgatewayjava.config.PointConfig;
import com.github.highcumontoa.modbustcpgatewayjava.config.PointType;
import com.github.highcumontoa.modbustcpgatewayjava.mock.MockModbusServer;
import com.github.highcumontoa.modbustcpgatewayjava.model.DeviceStatus;
import com.github.highcumontoa.modbustcpgatewayjava.model.FailReason;
import com.github.highcumontoa.modbustcpgatewayjava.model.PointReading;
import com.github.highcumontoa.modbustcpgatewayjava.model.Quality;
import com.github.highcumontoa.modbustcpgatewayjava.service.GatewayManager;
import com.github.highcumontoa.modbustcpgatewayjava.web.GatewayController;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 端到端场景：本地 Mock TCP 数据源 -> 采集引擎 -> 存储 -> REST 控制器。
 * 覆盖正常多类型采集、点级解码失败隔离、并发读取快照一致性、源端抖动下计数不漂移。
 */
class IntegrationScenarioTest {

    private static final Logger log = LoggerFactory.getLogger(IntegrationScenarioTest.class);

    private MockModbusServer server;
    private GatewayProperties props;
    private ReadingStore store;
    private DeviceCollectionEngine engine;

    private static PointConfig pc(String id, int addr, PointType type, ByteOrder order,
                                  double scale, double offset, Double min, Double max) {
        return new PointConfig(id, addr, type, order, scale, offset, min, max);
    }

    @BeforeEach
    void setUp() {
        server = new MockModbusServer(0);
        server.start();
        props = new GatewayProperties();
        props.setPollIntervalMs(30);
        props.setStaleThresholdMs(2000);
        props.setReconnectBackoffInitialMs(30);
        props.setReconnectBackoffMaxMs(300);
        props.setMockServerEnabled(false);

        DeviceConfig device = new DeviceConfig("line1", "127.0.0.1", server.getPort(), 1,
                500, 800, List.of(
                        pc("temp", 0, PointType.INT16, ByteOrder.ABCD, 0.1, 0, -50d, 150d),
                        pc("counter", 1, PointType.UINT32, ByteOrder.ABCD, 1, 0, null, null),
                        pc("ratio", 3, PointType.FLOAT32, ByteOrder.ABCD, 1, 0, 0d, 1d)));
        props.setDevices(List.of(device));
        store = new ReadingStore(props);
        engine = new DeviceCollectionEngine(device, props, store);
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.stop();
        }
        server.stop();
    }

    private void seedGoodValues() {
        server.setRegisters(1, 0, new int[] {
                250,          // temp INT16 = 250 -> 25.0
                0x0000, 0x04D2, // counter UINT32 addr1 占 addr1..2 = 1234
                0x3F80, 0x0000  // ratio FLOAT32 addr3 = 1.0f (0x3F800000)
        });
    }

    @Test
    @DisplayName("多类型点一轮批量读取后，REST 控制器返回 GOOD 快照")
    void endToEndGoodReadings() throws Exception {
        seedGoodValues();
        engine.start();
        waitFor(() -> store.getStatus("line1").successCount() >= 2, 3000);

        GatewayController controller = new GatewayController(new GatewayManager(props, store));
        List<DeviceStatus> statuses = controller.allStatus();
        Map<String, List<PointReading>> readings = controller.allReadings();

        DeviceStatus status = statuses.get(0);
        log.info("状态快照: {}", status);
        assertEquals("CONNECTED", status.state());
        assertEquals(0, status.failureCount());

        PointReading temp = byId(readings.get("line1"), "temp");
        PointReading counter = byId(readings.get("line1"), "counter");
        PointReading ratio = byId(readings.get("line1"), "ratio");
        log.info("temp={} counter={} ratio={}", temp, counter, ratio);

        assertEquals(25.0d, temp.value(), 1e-9);
        assertEquals(1234d, counter.value(), 0d);
        assertEquals(1.0d, ratio.value(), 1e-6);
        assertEquals(Quality.GOOD, temp.quality());
        assertNotNull(temp.rawHex());
        // 状态与读数设备 id 对齐，无错位
        assertEquals(3, readings.get("line1").size());
    }

    @Test
    @DisplayName("单点越界只影响该点（BAD/DECODE_ERROR），同轮其他点仍 GOOD，且计数按轮次只记一次成功")
    void pointDecodeFailureIsolation() throws Exception {
        seedGoodValues();
        engine.start();
        waitFor(() -> byId(store.getReadings("line1"), "temp").quality() == Quality.GOOD, 3000);

        // 把 temp 原始值改成越界：0x8000 -> -32768 * 0.1 = -3276.8，低于 -50
        server.setRegister(1, 0, 0x8000);
        waitFor(() -> byId(store.getReadings("line1"), "temp").quality() == Quality.BAD, 3000);

        List<PointReading> readings = store.getReadings("line1");
        PointReading temp = byId(readings, "temp");
        PointReading counter = byId(readings, "counter");
        PointReading ratio = byId(readings, "ratio");
        log.info("点级失败隔离: temp={} counter={} ratio={}", temp, counter, ratio);

        assertEquals(Quality.BAD, temp.quality());
        assertEquals(FailReason.DECODE_ERROR, temp.failReason());
        assertNull(temp.value(), "失败点不得产出数值");
        assertTrue(temp.message().contains("temp"), "必须能定位到数据点");
        assertEquals(Quality.GOOD, counter.quality());
        assertEquals(Quality.GOOD, ratio.quality());

        DeviceStatus status = store.getStatus("line1");
        log.info("设备计数: poll={} success={} failure={}",
                status.pollCount(), status.successCount(), status.failureCount());
        assertEquals(0, status.failureCount(), "点级解码失败不属于通信失败轮次");
        assertEquals(status.pollCount(), status.successCount(),
                "每轮通信成功只记一次成功，不因点数重复计数");
    }

    @Test
    @DisplayName("源端抖动（多次中途断连）下：读数不重复错位，计数守恒，恢复后全部 GOOD")
    void flappingSourceKeepsConsistentState() throws Exception {
        seedGoodValues();
        // 前三条连接都在应答一次后断开，第四条起恢复正常
        server.enqueueEchoOnceThenClose();
        server.enqueueEchoOnceThenClose();
        server.enqueueEchoOnceThenClose();

        engine.start();
        waitFor(() -> engine.getReconnectCount() >= 3, 8000);

        // 等待后续若干轮稳定成功
        long successAtReconnect = store.getStatus("line1").successCount();
        waitFor(() -> store.getStatus("line1").successCount() >= successAtReconnect + 5, 5000);

        List<PointReading> readings = store.getReadings("line1");
        for (PointReading r : readings) {
            assertEquals(Quality.GOOD, r.quality(), "恢复后所有点应为 GOOD: " + r);
            assertNotNull(r.value());
        }
        DeviceStatus status = store.getStatus("line1");
        log.info("抖动后状态: {}", status);
        assertEquals(status.pollCount(), status.successCount() + status.failureCount(),
                "总轮次=成功+失败，计数不漂移");
        assertTrue(status.reconnectCount() >= 3);
        assertEquals(0, status.consecutiveFailures());
    }

    @Test
    @DisplayName("并发查询：多个线程在采集进行中读取快照，不抛异常且每条快照自洽")
    void concurrentSnapshotReads() throws Exception {
        seedGoodValues();
        engine.start();
        waitFor(() -> store.getStatus("line1").successCount() >= 1, 3000);

        int threads = 8;
        CountDownLatch done = new CountDownLatch(threads);
        AtomicReference<Throwable> error = new AtomicReference<>();
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try {
                    long deadline = System.currentTimeMillis() + 1500;
                    while (System.currentTimeMillis() < deadline) {
                        List<PointReading> snapshot = store.getReadings("line1");
                        DeviceStatus status = store.getStatus("line1");
                        if (snapshot.size() != 3
                                || snapshot.stream().noneMatch(r -> r.pointId().equals("temp"))
                                || status.pollCount() < status.successCount()) {
                            throw new AssertionError("快照不自洽: " + snapshot);
                        }
                    }
                } catch (Throwable e) {
                    error.compareAndSet(null, e);
                } finally {
                    done.countDown();
                }
            });
            t.start();
        }
        done.await();
        assertNull(error.get(), "并发读取期间不应出现异常/错位");
        log.info("并发快照读取完成，pollCount={}", store.getStatus("line1").pollCount());
    }

    private static PointReading byId(List<PointReading> readings, String id) {
        return readings.stream().filter(r -> r.pointId().equals(id)).findFirst().orElseThrow();
    }

    private static void waitFor(java.util.function.BooleanSupplier cond, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("条件在 " + timeoutMs + "ms 内未满足");
    }
}
