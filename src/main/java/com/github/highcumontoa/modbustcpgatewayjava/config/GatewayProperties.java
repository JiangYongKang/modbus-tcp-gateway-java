package com.github.highcumontoa.modbustcpgatewayjava.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 网关运行配置（application.properties: gateway.*）。
 */
@Component
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    /** 采集轮询周期（毫秒） */
    private long pollIntervalMs = 1000;

    /** 上一次成功读数超过该毫秒数即降级为 STALE */
    private long staleThresholdMs = 5000;

    /** 重连初始间隔（毫秒） */
    private long reconnectBackoffInitialMs = 500;

    /** 重连最大间隔（毫秒） */
    private long reconnectBackoffMaxMs = 10000;

    /** 是否在本地启动内置 Mock Modbus TCP 服务 */
    private boolean mockServerEnabled = true;

    /** Mock 服务监听端口 */
    private int mockServerPort = 11502;

    /** 设备列表 */
    private List<DeviceConfig> devices = new ArrayList<>();

    public long getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }

    public long getStaleThresholdMs() { return staleThresholdMs; }
    public void setStaleThresholdMs(long staleThresholdMs) { this.staleThresholdMs = staleThresholdMs; }

    public long getReconnectBackoffInitialMs() { return reconnectBackoffInitialMs; }
    public void setReconnectBackoffInitialMs(long v) { this.reconnectBackoffInitialMs = v; }

    public long getReconnectBackoffMaxMs() { return reconnectBackoffMaxMs; }
    public void setReconnectBackoffMaxMs(long v) { this.reconnectBackoffMaxMs = v; }

    public boolean isMockServerEnabled() { return mockServerEnabled; }
    public void setMockServerEnabled(boolean mockServerEnabled) { this.mockServerEnabled = mockServerEnabled; }

    public int getMockServerPort() { return mockServerPort; }
    public void setMockServerPort(int mockServerPort) { this.mockServerPort = mockServerPort; }

    public List<DeviceConfig> getDevices() { return devices; }
    public void setDevices(List<DeviceConfig> devices) { this.devices = devices; }
}
