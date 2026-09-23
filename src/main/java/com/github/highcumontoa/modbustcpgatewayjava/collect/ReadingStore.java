package com.github.highcumontoa.modbustcpgatewayjava.collect;

import com.github.highcumontoa.modbustcpgatewayjava.config.DeviceConfig;
import com.github.highcumontoa.modbustcpgatewayjava.config.GatewayProperties;
import com.github.highcumontoa.modbustcpgatewayjava.model.DeviceStatus;
import com.github.highcumontoa.modbustcpgatewayjava.model.FailReason;
import com.github.highcumontoa.modbustcpgatewayjava.model.PointReading;
import com.github.highcumontoa.modbustcpgatewayjava.model.Quality;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 读数与运行状态的并发安全存储。
 *
 * <ul>
 *   <li>同一 (deviceId, pointId) 只保留最新状态，整体快照在同一把锁上拷贝；</li>
 *   <li>成功轮次用 GOOD 读数整体替换；</li>
 *   <li>通信失败不删除旧值：新鲜窗口内降级为 UNCERTAIN，超出陈旧阈值降级为 STALE，
 *       从未成功过的点为 BAD；</li>
 *   <li>点级解码失败的点置 BAD（不保留本次任何数值），其余点照常 GOOD；</li>
 *   <li>所有计数器单调递增或由引擎显式复位语义，不存在“重复计数”路径。</li>
 * </ul>
 */
public class ReadingStore {

    private static final class DeviceState {
        final DeviceConfig config;
        final Map<String, PointReading> readings = new LinkedHashMap<>();
        boolean connected;
        long pollCount;
        long successCount;
        long failureCount;
        long timeoutCount;
        long reconnectCount;
        long consecutiveFailures;
        long lastPollAt;
        long lastSuccessAt;
        long lastFailureAt;
        FailReason lastFailReason;
        String lastError = "";

        DeviceState(DeviceConfig config) {
            this.config = config;
            long now = System.currentTimeMillis();
            for (var point : config.points()) {
                readings.put(point.id(), new PointReading(
                        config.id(), point.id(), null, null,
                        Quality.BAD, null, "尚未采集", now));
            }
        }
    }

    private final GatewayProperties properties;
    private final Map<String, DeviceState> devices = new ConcurrentHashMap<>();

    public ReadingStore(GatewayProperties properties) {
        this.properties = properties;
        for (DeviceConfig device : properties.getDevices()) {
            devices.put(device.id(), new DeviceState(device));
        }
    }

    /** 注册一台设备（测试中可在构造后追加）。 */
    public synchronized void registerDevice(DeviceConfig config) {
        devices.putIfAbsent(config.id(), new DeviceState(config));
    }

    // ---------- 轮次记账 ----------

    public synchronized void beginPoll(String deviceId) {
        DeviceState st = require(deviceId);
        st.pollCount++;
        st.lastPollAt = System.currentTimeMillis();
    }

    /**
     * 通信成功的一轮：已解码成功的点替换为 GOOD。
     * 未出现在 readings 中的点保持原状（由后续 pointFailure 或下一轮处理）。
     */
    public synchronized void commitSuccess(String deviceId, List<PointReading> goodReadings) {
        DeviceState st = require(deviceId);
        long now = System.currentTimeMillis();
        for (PointReading r : goodReadings) {
            if (!r.deviceId().equals(deviceId) || !st.readings.containsKey(r.pointId())) {
                throw new IllegalArgumentException(
                        "读数不属于该设备/数据点: " + deviceId + "/" + r.pointId());
            }
            st.readings.put(r.pointId(), r);
        }
        st.successCount++;
        st.consecutiveFailures = 0;
        st.lastSuccessAt = now;
        st.lastFailReason = null;
        st.lastError = "";
    }

    /** 点级失败（解码错误 / 配置错误）：本次数值作废，置 BAD，不影响旧轮次计数。 */
    public synchronized void commitPointFailure(String deviceId, PointReading reading) {
        DeviceState st = require(deviceId);
        if (!st.readings.containsKey(reading.pointId())) {
            throw new IllegalArgumentException("未知数据点: " + reading.pointId());
        }
        st.readings.put(reading.pointId(), reading);
    }

    /**
     * 通信级失败的一轮：没有任何新值。
     * 旧值按陈旧阈值降级为 UNCERTAIN/STALE；从未成功的点置 BAD。
     */
    public synchronized void commitFailure(String deviceId, FailReason reason, String message) {
        DeviceState st = require(deviceId);
        long now = System.currentTimeMillis();
        st.failureCount++;
        st.consecutiveFailures++;
        if (reason == FailReason.TIMEOUT || reason == FailReason.INCOMPLETE_FRAME) {
            st.timeoutCount++;
        }
        st.lastFailureAt = now;
        st.lastFailReason = reason;
        st.lastError = message == null ? "" : message;

        for (Map.Entry<String, PointReading> e : st.readings.entrySet()) {
            PointReading old = e.getValue();
            Quality q;
            Double value = null;
            String rawHex = null;
            if (old.quality() == Quality.GOOD || old.quality() == Quality.UNCERTAIN
                    || old.quality() == Quality.STALE) {
                value = old.value();
                rawHex = old.rawHex();
                boolean stale = now - old.sampledAt() > properties.getStaleThresholdMs();
                q = stale ? Quality.STALE : Quality.UNCERTAIN;
            } else {
                q = Quality.BAD;
            }
            e.setValue(new PointReading(deviceId, e.getKey(), value, rawHex, q, reason,
                    message == null ? reason.name() : message, old.sampledAt()));
        }
    }

    public synchronized void setConnected(String deviceId, boolean connected) {
        require(deviceId).connected = connected;
    }

    public synchronized void recordReconnect(String deviceId) {
        require(deviceId).reconnectCount++;
    }

    /**
     * 按陈旧阈值刷新所有保留值的质量：UNCERTAIN 超时 -> STALE；
     * 采集停止场景下 GOOD 保留值过老也 -> STALE。
     *
     * @return 状态发生变化的设备 id 列表
     */
    public synchronized List<String> refreshStale() {
        long now = System.currentTimeMillis();
        List<String> changed = new ArrayList<>();
        for (DeviceState st : devices.values()) {
            boolean touched = false;
            for (Map.Entry<String, PointReading> e : st.readings.entrySet()) {
                PointReading r = e.getValue();
                if ((r.quality() == Quality.GOOD || r.quality() == Quality.UNCERTAIN)
                        && r.value() != null
                        && now - r.sampledAt() > properties.getStaleThresholdMs()) {
                    e.setValue(new PointReading(r.deviceId(), r.pointId(), r.value(),
                            r.rawHex(), Quality.STALE, r.failReason(),
                            "保留值已超过陈旧阈值(" + properties.getStaleThresholdMs()
                                    + "ms)：" + r.message(),
                            r.sampledAt()));
                    touched = true;
                }
            }
            if (touched) {
                changed.add(st.config.id());
            }
        }
        return changed;
    }

    // ---------- 快照查询 ----------

    public synchronized List<PointReading> getReadings(String deviceId) {
        DeviceState st = devices.get(deviceId);
        if (st == null) {
            return List.of();
        }
        return List.copyOf(st.readings.values());
    }

    public synchronized Map<String, List<PointReading>> getAllReadings() {
        Map<String, List<PointReading>> out = new LinkedHashMap<>();
        for (DeviceState st : devices.values()) {
            out.put(st.config.id(), List.copyOf(st.readings.values()));
        }
        return out;
    }

    public synchronized DeviceStatus getStatus(String deviceId) {
        DeviceState st = devices.get(deviceId);
        return st == null ? null : toStatus(st);
    }

    public synchronized List<DeviceStatus> getAllStatus() {
        List<DeviceStatus> out = new ArrayList<>();
        for (DeviceState st : devices.values()) {
            out.add(toStatus(st));
        }
        return out;
    }

    private DeviceState require(String deviceId) {
        DeviceState st = devices.get(deviceId);
        if (st == null) {
            throw new IllegalArgumentException("未注册的设备: " + deviceId);
        }
        return st;
    }

    private static DeviceStatus toStatus(DeviceState st) {
        return new DeviceStatus(
                st.config.id(),
                st.connected ? "CONNECTED" : "DISCONNECTED",
                st.connected,
                st.pollCount, st.successCount, st.failureCount, st.timeoutCount,
                st.reconnectCount, st.consecutiveFailures,
                st.lastPollAt, st.lastSuccessAt, st.lastFailureAt,
                st.lastFailReason, st.lastError);
    }

    /** 点级失败输入（保留供批量构造使用）。 */
    public record FailInput(String pointId, FailReason reason, String message, String rawHex) {
    }
}
