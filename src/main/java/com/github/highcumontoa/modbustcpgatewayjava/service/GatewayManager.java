package com.github.highcumontoa.modbustcpgatewayjava.service;

import com.github.highcumontoa.modbustcpgatewayjava.collect.ConfigValidator;
import com.github.highcumontoa.modbustcpgatewayjava.collect.DeviceCollectionEngine;
import com.github.highcumontoa.modbustcpgatewayjava.collect.ReadingStore;
import com.github.highcumontoa.modbustcpgatewayjava.config.DeviceConfig;
import com.github.highcumontoa.modbustcpgatewayjava.config.GatewayProperties;
import com.github.highcumontoa.modbustcpgatewayjava.mock.MockModbusServer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 网关生命周期管理器。
 *
 * 启动顺序：配置校验（不通过直接抛异常拒绝启动）→ 可选启动内置 Mock 数据源
 * → 创建读数存储与每设备采集引擎 → 启动陈旧检测定时器。
 */
@Component
public class GatewayManager {

    private static final Logger log = LoggerFactory.getLogger(GatewayManager.class);

    private final GatewayProperties properties;
    private ReadingStore store;
    private MockModbusServer mockServer;
    private final Map<String, DeviceCollectionEngine> engines = new LinkedHashMap<>();
    private ScheduledExecutorService staleScheduler;
    private volatile boolean started;

    @org.springframework.beans.factory.annotation.Autowired
    public GatewayManager(GatewayProperties properties) {
        this.properties = properties;
    }

    /** 测试/嵌入式用法：直接注入已构造好的读数存储（不触发 {@link #start()}）。 */
    public GatewayManager(GatewayProperties properties, ReadingStore store) {
        this.properties = properties;
        this.store = store;
        this.started = true;
    }

    @PostConstruct
    public synchronized void start() {
        if (started) {
            return;
        }
        List<String> errors = ConfigValidator.validate(properties);
        if (!errors.isEmpty()) {
            for (String e : errors) {
                log.error("配置错误: {}", e);
            }
            throw new IllegalStateException("网关配置校验失败，共 " + errors.size() + " 项错误，拒绝启动");
        }

        if (properties.isMockServerEnabled()) {
            mockServer = new MockModbusServer(properties.getMockServerPort());
            mockServer.start();
            log.info("内置 Mock Modbus TCP 数据源已启动: 127.0.0.1:{}", mockServer.getPort());
            seedMockData(mockServer);
        }

        this.store = new ReadingStore(properties);
        for (DeviceConfig device : properties.getDevices()) {
            DeviceCollectionEngine engine = new DeviceCollectionEngine(device, properties, store);
            engines.put(device.id(), engine);
        }

        long refreshInterval = Math.max(200, properties.getStaleThresholdMs() / 2);
        staleScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "stale-refresher");
            t.setDaemon(true);
            return t;
        });
        staleScheduler.scheduleAtFixedRate(this::refreshStaleSafely,
                refreshInterval, refreshInterval, TimeUnit.MILLISECONDS);

        engines.values().forEach(DeviceCollectionEngine::start);
        started = true;
        log.info("采集网关已启动，设备数={}", engines.size());
    }

    @PreDestroy
    public synchronized void stop() {
        if (!started) {
            return;
        }
        engines.values().forEach(DeviceCollectionEngine::stop);
        if (staleScheduler != null) {
            staleScheduler.shutdownNow();
        }
        if (mockServer != null) {
            mockServer.stop();
        }
        started = false;
        log.info("采集网关已停止");
    }

    /** 不经过 Spring 容器的启动入口（供测试使用）。 */
    public static GatewayManager startStandalone(GatewayProperties properties) {
        GatewayManager manager = new GatewayManager(properties);
        manager.start();
        return manager;
    }

    /**
     * 为内置 Mock 数据源播种确定性寄存器值（unitId=1）：
     * addr0: INT16=250 → 0.1*250-40 = -15.0
     * addr2: UINT32=0x00010000（CDAB 字交换后为 1）
     * addr4: FLOAT32=3.14 的 DCBA(小端) 线序
     * addr8: INT64=12345（ABCD）→ *0.001 = 12.345
     */
    private static void seedMockData(MockModbusServer server) {
        int unit = 1;
        server.setRegister(unit, 0, 250);
        server.setRegister(unit, 1, 0);
        // 0x00010000 在线序上为 00 01 00 00（CDAB 重排后得到 1）
        server.setRegister(unit, 2, 0x0001);
        server.setRegister(unit, 3, 0x0000);
        // float 3.14f 大端字节 40 48 F5 C3，DCBA 线序需整体小端：C3 F5 48 40
        int bits = Float.floatToRawIntBits(3.14f);
        byte[] be = new byte[] {
                (byte) (bits >>> 24), (byte) (bits >>> 16),
                (byte) (bits >>> 8), (byte) bits};
        server.setRegister(unit, 4, ((be[3] & 0xFF) << 8) | (be[2] & 0xFF));
        server.setRegister(unit, 5, ((be[1] & 0xFF) << 8) | (be[0] & 0xFF));
        // int64 12345：高字在 addr8
        long energy = 12345L;
        for (int w = 3; w >= 0; w--) {
            server.setRegister(unit, 8 + (3 - w), (int) ((energy >>> (w * 16)) & 0xFFFF));
        }
    }

    private void refreshStaleSafely() {
        try {
            List<String> changed = store.refreshStale();
            for (String deviceId : changed) {
                log.info("[{}] 保留读数超过陈旧阈值，已标记为 STALE", deviceId);
            }
        } catch (Throwable t) {
            log.error("陈旧检测异常: {}", t.toString(), t);
        }
    }

    public ReadingStore getStore() {
        return store;
    }

    public MockModbusServer getMockServer() {
        return mockServer;
    }

    public Map<String, DeviceCollectionEngine> getEngines() {
        return Map.copyOf(engines);
    }
}
