package com.github.highcumontoa.modbustcpgatewayjava.collect;

import com.github.highcumontoa.modbustcpgatewayjava.codec.DecodeException;
import com.github.highcumontoa.modbustcpgatewayjava.codec.PointDecoder;
import com.github.highcumontoa.modbustcpgatewayjava.config.DeviceConfig;
import com.github.highcumontoa.modbustcpgatewayjava.config.GatewayProperties;
import com.github.highcumontoa.modbustcpgatewayjava.config.PointConfig;
import com.github.highcumontoa.modbustcpgatewayjava.model.FailReason;
import com.github.highcumontoa.modbustcpgatewayjava.model.PointReading;
import com.github.highcumontoa.modbustcpgatewayjava.model.Quality;
import com.github.highcumontoa.modbustcpgatewayjava.protocol.HexUtils;
import com.github.highcumontoa.modbustcpgatewayjava.protocol.ModbusRequestException;
import com.github.highcumontoa.modbustcpgatewayjava.protocol.ModbusTcpClient;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单设备采集引擎。
 *
 * <ul>
 *   <li>按地址把相邻数据点合并成最少的 FC=0x03 读取区间（单区间上限 125 寄存器）；</li>
 *   <li>连接由本引擎懒建立；通信失败后下一轮按指数退避重连并记录 reconnectCount；</li>
 *   <li>任何一轮只产生一个轮次记账（beginPoll 一次）：
 *       通信失败整轮 commitFailure，点解码失败逐点 commitPointFailure，
 *       成功的点 commitSuccess，计数不会因重连/并发漂移；</li>
 *   <li>所有失败日志打印原始报文与判定依据。</li>
 * </ul>
 */
public class DeviceCollectionEngine {

    private static final Logger log = LoggerFactory.getLogger(DeviceCollectionEngine.class);

    /** 一次读取区间。 */
    private record ReadRange(int startAddress, int quantity, List<PointConfig> points) {
    }

    private final DeviceConfig device;
    private final GatewayProperties properties;
    private final ReadingStore store;
    private final PointDecoder decoder = new PointDecoder();
    private final List<ReadRange> ranges;

    private final ModbusTcpClient client;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> task;
    private volatile boolean running;

    private long backoffMs;
    private boolean wasConnected;
    private long pollCount;
    private long reconnectCount;

    public DeviceCollectionEngine(DeviceConfig device, GatewayProperties properties,
                                  ReadingStore store) {
        this.device = device;
        this.properties = properties;
        this.store = store;
        this.client = new ModbusTcpClient(device);
        this.ranges = planRanges(device.points());
        this.backoffMs = properties.getReconnectBackoffInitialMs();
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "collector-" + device.id());
            t.setDaemon(true);
            return t;
        });
        scheduleNext(0);
    }

    public synchronized void stop() {
        running = false;
        if (task != null) {
            task.cancel(false);
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        client.close();
        store.setConnected(device.id(), false);
    }

    public long getPollCount() {
        return pollCount;
    }

    public long getReconnectCount() {
        return reconnectCount;
    }

    public ModbusTcpClient getClient() {
        return client;
    }

    private void scheduleNext(long delayMs) {
        if (!running) {
            return;
        }
        task = scheduler.schedule(this::pollSafely, delayMs, TimeUnit.MILLISECONDS);
    }

    private void pollSafely() {
        try {
            long delay;
            synchronized (this) {
                delay = doPoll();
            }
            scheduleNext(delay);
        } catch (Throwable t) {
            // 调度线程绝不能因未捕获异常死亡
            log.error("[{}] 采集轮次发生未预期异常，稍后重试: {}", device.id(), t.toString(), t);
            scheduleNext(backoffMs);
        }
    }

    /**
     * 执行一轮采集。
     *
     * @return 下一轮建议延迟（毫秒）
     */
    private long doPoll() {
        pollCount++;
        store.beginPoll(device.id());

        if (!client.isConnected()) {
            // wasConnected=true 且当前未连接，意味着此前至少成功连过一次——这才是“重连”
            boolean reconnecting = wasConnected;
            try {
                client.connect();
                if (reconnecting) {
                    reconnectCount++;
                    store.recordReconnect(device.id());
                    log.warn("[{}] 检测到连接重建（第 {} 次重连）", device.id(), reconnectCount);
                }
                wasConnected = true;
                store.setConnected(device.id(), true);
                backoffMs = properties.getReconnectBackoffInitialMs();
            } catch (ModbusRequestException e) {
                store.setConnected(device.id(), false);
                store.commitFailure(device.id(), e.reason(),
                        "建立连接失败: " + e.getMessage());
                log.warn("[{}] CONNECT 失败 reason={} detail={}",
                        device.id(), e.reason(), e.getMessage());
                long wait = nextBackoff();
                return wait;
            }
        }

        List<PointReading> good = new ArrayList<>();
        boolean communicationFailed = false;

        for (ReadRange range : ranges) {
            byte[] data;
            try {
                data = client.readHoldingRegisters(range.startAddress(), range.quantity());
            } catch (ModbusRequestException e) {
                communicationFailed = true;
                store.setConnected(device.id(), false);
                store.commitFailure(device.id(), e.reason(), e.getMessage());
                log.warn("[{}] 读取失败 addr={} qty={} reason={} detail={}",
                        device.id(), range.startAddress(), range.quantity(),
                        e.reason(), e.getMessage());
                break;
            }

            String rangeHex = HexUtils.toHex(data);
            for (PointConfig point : range.points()) {
                int offset = (point.address() - range.startAddress()) * 2;
                String pointHex = sliceHex(data, offset, point.type().bytes());
                long now = System.currentTimeMillis();
                try {
                    double value = decoder.decode(point, data, offset);
                    good.add(new PointReading(device.id(), point.id(), value, pointHex,
                            Quality.GOOD, null,
                            "解码成功: 原始=" + pointHex + " 线序区间=" + rangeHex, now));
                } catch (DecodeException e) {
                    String msg = "解码失败 point=" + point.id() + " type=" + point.type()
                            + " order=" + point.order() + " 原始=" + pointHex
                            + " 判定=" + e.getMessage();
                    store.commitPointFailure(device.id(), new PointReading(
                            device.id(), point.id(), null, pointHex, Quality.BAD,
                            FailReason.DECODE_ERROR, msg, now));
                    log.warn("[{}] {}", device.id(), msg);
                }
            }
        }

        if (!communicationFailed) {
            if (!good.isEmpty()) {
                store.commitSuccess(device.id(), good);
            }
            return properties.getPollIntervalMs();
        }
        // 通信失败：下一轮将走重连分支，使用退避
        return nextBackoff();
    }

    /** 手动立即执行一轮（同步），供测试与触发接口使用。 */
    public void pollOnce() {
        synchronized (this) {
            doPoll();
        }
    }

    private long nextBackoff() {
        long current = backoffMs;
        backoffMs = Math.min(backoffMs * 2, properties.getReconnectBackoffMaxMs());
        return current;
    }

    private static String sliceHex(byte[] data, int offset, int length) {
        return HexUtils.toHex(data, offset, length);
    }

    /**
     * 将数据点按地址排序并合并相邻（地址首尾相接，允许重叠）区间。
     * 任一合并后区间超过 125 寄存器则拆分。
     */
    static List<ReadRange> planRanges(List<PointConfig> points) {
        List<PointConfig> sorted = points.stream()
                .sorted(Comparator.comparingInt(PointConfig::address))
                .toList();
        List<ReadRange> result = new ArrayList<>();

        int i = 0;
        while (i < sorted.size()) {
            int start = sorted.get(i).address();
            int endExclusive = start + sorted.get(i).type().registers();
            List<PointConfig> group = new ArrayList<>();
            group.add(sorted.get(i));
            int j = i + 1;
            while (j < sorted.size()) {
                PointConfig next = sorted.get(j);
                int nextEnd = next.address() + next.type().registers();
                // 相邻或重叠且不超 125 寄存器则合并
                if (next.address() <= endExclusive && nextEnd - start <= 125) {
                    group.add(next);
                    endExclusive = Math.max(endExclusive, nextEnd);
                    j++;
                } else {
                    break;
                }
            }
            result.add(new ReadRange(start, endExclusive - start, List.copyOf(group)));
            i = j;
        }
        return result;
    }
}
