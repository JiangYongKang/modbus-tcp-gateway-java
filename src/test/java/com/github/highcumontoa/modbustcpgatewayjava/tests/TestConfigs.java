package com.github.highcumontoa.modbustcpgatewayjava.tests;

import com.github.highcumontoa.modbustcpgatewayjava.config.DeviceConfig;
import java.util.List;

/** 测试用设备配置工厂。 */
final class TestConfigs {

    private TestConfigs() {
    }

    static DeviceConfig device(int port, int responseTimeoutMs) {
        return new DeviceConfig("test-device", "127.0.0.1", port,
                1, 500, responseTimeoutMs, List.of());
    }

    static DeviceConfig device(int port) {
        return device(port, 1000);
    }
}
