package com.github.highcumontoa.gateway.config;

import com.github.highcumontoa.gateway.model.DataType;
import com.github.highcumontoa.gateway.model.OctetByteOrder;
import com.github.highcumontoa.gateway.model.RegisterType;
import com.github.highcumontoa.gateway.model.WordOrder;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {
    private Client client = new Client();
    private LocalSource localSource = new LocalSource();
    private List<PointDefinition> points = new ArrayList<>();

    public Client getClient() { return client; }
    public void setClient(Client client) { this.client = client; }
    public LocalSource getLocalSource() { return localSource; }
    public void setLocalSource(LocalSource localSource) { this.localSource = localSource; }
    public List<PointDefinition> getPoints() { return points; }
    public void setPoints(List<PointDefinition> points) { this.points = points; }

    public static class Client {
        private String host = "127.0.0.1";
        private int port = 15020;
        private int connectTimeoutMs = 1_000;
        private int responseTimeoutMs = 1_000;
        private int pollIntervalMs = 500;
        private int reconnectDelayMs = 200;
        private long staleThresholdMs = 2_000;

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public int getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
        public int getResponseTimeoutMs() { return responseTimeoutMs; }
        public void setResponseTimeoutMs(int responseTimeoutMs) { this.responseTimeoutMs = responseTimeoutMs; }
        public int getPollIntervalMs() { return pollIntervalMs; }
        public void setPollIntervalMs(int pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }
        public int getReconnectDelayMs() { return reconnectDelayMs; }
        public void setReconnectDelayMs(int reconnectDelayMs) { this.reconnectDelayMs = reconnectDelayMs; }
        public long getStaleThresholdMs() { return staleThresholdMs; }
        public void setStaleThresholdMs(long staleThresholdMs) { this.staleThresholdMs = staleThresholdMs; }
    }

    public static class LocalSource {
        private boolean enabled = true;
        private String host = "127.0.0.1";
        private int port = 15020;
        private int acceptBacklog = 50;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public int getAcceptBacklog() { return acceptBacklog; }
        public void setAcceptBacklog(int acceptBacklog) { this.acceptBacklog = acceptBacklog; }
    }

    public static class PointDefinition {
        private String name;
        private RegisterType registerType = RegisterType.HOLDING;
        private int unitId = 1;
        private int address;
        private DataType dataType = DataType.INT16;
        private OctetByteOrder byteOrder = OctetByteOrder.BIG_ENDIAN;
        private WordOrder wordOrder = WordOrder.HIGH_WORD_FIRST;
        private double scale = 1.0d;
        private double offset = 0.0d;
        private Double min;
        private Double max;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public RegisterType getRegisterType() { return registerType; }
        public void setRegisterType(RegisterType registerType) { this.registerType = registerType; }
        public int getUnitId() { return unitId; }
        public void setUnitId(int unitId) { this.unitId = unitId; }
        public int getAddress() { return address; }
        public void setAddress(int address) { this.address = address; }
        public DataType getDataType() { return dataType; }
        public void setDataType(DataType dataType) { this.dataType = dataType; }
        public OctetByteOrder getByteOrder() { return byteOrder; }
        public void setByteOrder(OctetByteOrder byteOrder) { this.byteOrder = byteOrder; }
        public WordOrder getWordOrder() { return wordOrder; }
        public void setWordOrder(WordOrder wordOrder) { this.wordOrder = wordOrder; }
        public double getScale() { return scale; }
        public void setScale(double scale) { this.scale = scale; }
        public double getOffset() { return offset; }
        public void setOffset(double offset) { this.offset = offset; }
        public Double getMin() { return min; }
        public void setMin(Double min) { this.min = min; }
        public void setMax(Double max) { this.max = max; }
        public Double getMax() { return max; }
    }
}
