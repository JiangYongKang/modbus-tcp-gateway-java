package com.github.highcumontoa.modbustcpgatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import com.github.highcumontoa.modbustcpgatewayjava.protocol.ModbusRequestException;
import com.github.highcumontoa.modbustcpgatewayjava.protocol.ModbusTcpClient;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 响应超时、连接中断自动恢复与运行计数一致性测试。
 */
class TimeoutReconnectTest {

    private static final Logger log = LoggerFactory.getLogger(TimeoutReconnectTest.class);

    private MockModbusServer server;

    @BeforeEach
    void setUp() {
        server = new MockModbusServer(0);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private GatewayProperties props(int port, int responseTimeoutMs) {
        GatewayProperties props = new GatewayProperties();
        props.setPollIntervalMs(30);
        props.setStaleThresholdMs(1000);
        props.setReconnectBackoffInitialMs(30);
        props.setReconnectBackoffMaxMs(300);
        props.setMockServerEnabled(false);
        DeviceConfig device = new DeviceConfig("dev", "127.0.0.1", port, 1,
                500, responseTimeoutMs,
                List.of(new PointConfig("v", 0, PointType.INT16, ByteOrder.ABCD,
                        1, 0, null, null)));
        props.setDevices(List.of(device));
        return props;
    }

    @Test
    @DisplayName("对端静默不响应：归类 TIMEOUT，且连接被关闭，下次必须重连")
    void silentPeerTimesOut() throws Exception {
        server.enqueueSilentThenHang();
        ModbusTcpClient client = new ModbusTcpClient(props(server.getPort(), 300).getDevices().get(0));
        try {
            client.connect();            ModbusRequestException ex = assertThrows(ModbusRequestException.class,
                    () -> client.readHoldingRegisters(0, 1));
            log.info("超时判定: reason={} msg={}", ex.reason(), ex.getMessage());
            assertEquals(FailReason.TIMEOUT, ex.reason());
            assertFalse(client.isConnected(), "超时后底层连接必须关闭，杜绝陈旧字节污染");
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName("连接拒绝（无监听端口）：归类 CONNECT_FAILED")
    void connectionRefused() {
        // 用一个已关闭的临时端口
        int deadPort;
        try (java.net.ServerSocket tmp = new java.net.ServerSocket(0)) {
            deadPort = tmp.getLocalPort();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        DeviceConfig cfg = new DeviceConfig("dev", "127.0.0.1", deadPort, 1,
                300, 300, List.of());
        ModbusTcpClient client = new ModbusTcpClient(cfg);
        ModbusRequestException ex = assertThrows(ModbusRequestException.class, client::connect);
        log.info("拒绝连接判定: reason={} msg={}", ex.reason(), ex.getMessage());
        assertEquals(FailReason.CONNECT_FAILED, ex.reason());
    }

    @Test
    @DisplayName("断连后自动恢复：成功→中途断连→失败降级→自动重连→恢复 GOOD，计数不漂移")
    void autoRecoverAfterDrop() throws Exception {
        GatewayProperties props = props(server.getPort(), 500);
        server.setRegister(1, 0, 0x0007); // 正常值 7
        server.enqueueEchoOnceThenClose(); // 第一条连接：应答一次后断开

        ReadingStore store = new ReadingStore(props);
        DeviceCollectionEngine engine = new DeviceCollectionEngine(
                props.getDevices().get(0), props, store);
        try {
            engine.start();

            waitUntil(() -> store.getStatus("dev").successCount() >= 1, 3000);
            // 第一条连接已成功；随后连接被断开，且后续没有脚本 -> 默认寄存器映像继续服务
            waitUntil(() -> store.getStatus("dev").reconnectCount() >= 1
                    || engine.getReconnectCount() >= 1, 5000);

            server.setRegister(1, 0, 0x0009);
            waitUntil(() -> lastValue(store) == 9.0d, 5000);

            DeviceStatus status = store.getStatus("dev");
            PointReading reading = store.getReadings("dev").get(0);
            log.info("恢复后状态: poll={} success={} failure={} reconnect={} reading={}",
                    status.pollCount(), status.successCount(), status.failureCount(),
                    status.reconnectCount(), reading);
            assertEquals(Quality.GOOD, reading.quality());
            assertEquals(9.0d, reading.value(), 0d);
            assertTrue(status.reconnectCount() >= 1);
            // 计数守恒：成功+失败 == 总轮次（每轮恰好记账一次）
            assertEquals(status.pollCount(),
                    status.successCount() + status.failureCount(),
                    "轮次记账必须守恒");
            assertEquals(0, status.consecutiveFailures(),
                    "恢复成功后连续失败计数必须清零");
        } finally {
            engine.stop();
        }
    }

    @Test
    @DisplayName("重连退避：服务不可用时重连次数受限，恢复后继续工作")
    void backoffWhileDown() throws Exception {
        // 先停掉服务器制造不可达，稍后重启同端口
        int port = server.getPort();
        GatewayProperties props = props(port, 400);
        server.stop();

        ReadingStore store = new ReadingStore(props);
        DeviceCollectionEngine engine = new DeviceCollectionEngine(
                props.getDevices().get(0), props, store);
        try {
            engine.start();
            Thread.sleep(800);
            long reconnectAttemptsDuringDown = store.getStatus("dev").failureCount();
            assertTrue(reconnectAttemptsDuringDown >= 2,
                    "服务不可用期间应多次退避重试，实际失败轮次=" + reconnectAttemptsDuringDown);
            DeviceStatus down = store.getStatus("dev");
            log.info("宕机期间: failure={} timeout={} lastReason={}",
                    down.failureCount(), down.timeoutCount(), down.lastFailReason());
            assertEquals(FailReason.CONNECT_FAILED, down.lastFailReason());

            // 重启服务
            MockModbusServer restarted = new MockModbusServer(port);
            restarted.start();
            restarted.setRegister(1, 0, 0x0005);
            try {
                waitUntil(() -> lastValue(store) == 5.0d, 5000);
                PointReading reading = store.getReadings("dev").get(0);
                log.info("服务恢复后读数: value={} quality={}", reading.value(), reading.quality());
                assertEquals(5.0d, reading.value(), 0d);
                assertEquals(Quality.GOOD, reading.quality());
            } finally {
                restarted.stop();
            }
        } finally {
            engine.stop();
        }
    }

    @Test
    @DisplayName("陈旧降级：断连后旧值先 UNCERTAIN 后 STALE，质量标记可通过查询接口看到")
    void staleQualityAfterProlongedFailure() throws Exception {
        GatewayProperties props = props(server.getPort(), 400);
        props.setStaleThresholdMs(400);
        // 用全新 store 以应用新阈值
        server.setRegister(1, 0, 0x0003);

        ReadingStore store = new ReadingStore(props);
        DeviceCollectionEngine engine = new DeviceCollectionEngine(
                props.getDevices().get(0), props, store);
        int port = server.getPort();
        try {
            engine.start();
            waitUntil(() -> store.getStatus("dev").successCount() >= 1, 3000);
            PointReading good = store.getReadings("dev").get(0);
            assertEquals(3.0d, good.value(), 0d);
            assertNotNull(good.rawHex());

            // 停掉服务器，触发持续失败
            server.stop();
            waitUntil(() -> store.getReadings("dev").get(0).quality() == Quality.UNCERTAIN, 3000);
            PointReading uncertain = store.getReadings("dev").get(0);
            log.info("失败初期(降级): value={} quality={} reason={}",
                    uncertain.value(), uncertain.quality(), uncertain.failReason());
            assertEquals(3.0d, uncertain.value(), "旧值必须保留，不得清空或改写");
            assertEquals(Quality.UNCERTAIN, uncertain.quality());

            waitUntil(() -> store.getReadings("dev").get(0).quality() == Quality.STALE, 3000);
            PointReading stale = store.getReadings("dev").get(0);
            log.info("陈旧后: value={} quality={} reason={} msg={}",
                    stale.value(), stale.quality(), stale.failReason(), stale.message());
            assertEquals(Quality.STALE, stale.quality());
            assertEquals(3.0d, stale.value(), "陈旧读数保留旧值但必须有明确质量标记");

            DeviceStatus status = store.getStatus("dev");
            assertTrue(status.failureCount() > 0);
            log.info("设备状态: {}", status);
        } finally {
            engine.stop();
        }
    }

    private static double lastValue(ReadingStore store) {
        PointReading r = store.getReadings("dev").get(0);
        return r.value() == null ? Double.NaN : r.value();
    }

    private static void waitUntil(java.util.function.BooleanSupplier cond, long timeoutMs)
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
