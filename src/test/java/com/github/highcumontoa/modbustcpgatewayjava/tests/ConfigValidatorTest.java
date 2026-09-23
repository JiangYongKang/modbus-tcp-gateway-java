package com.github.highcumontoa.modbustcpgatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.highcumontoa.modbustcpgatewayjava.collect.ConfigValidator;
import com.github.highcumontoa.modbustcpgatewayjava.config.ByteOrder;
import com.github.highcumontoa.modbustcpgatewayjava.config.DeviceConfig;
import com.github.highcumontoa.modbustcpgatewayjava.config.GatewayProperties;
import com.github.highcumontoa.modbustcpgatewayjava.config.PointConfig;
import com.github.highcumontoa.modbustcpgatewayjava.config.PointType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 配置校验测试：错误必须可定位到设备/数据点，不得静默放过。 */
class ConfigValidatorTest {

    private static final Logger log = LoggerFactory.getLogger(ConfigValidatorTest.class);

    private GatewayProperties baseProps(PointConfig... points) {
        GatewayProperties props = new GatewayProperties();
        DeviceConfig device = new DeviceConfig("d1", "127.0.0.1", 11502, 1,
                1000, 1000, List.of(points));
        props.setDevices(List.of(device));
        return props;
    }

    private PointConfig point(String id, int address, PointType type, Double min, Double max) {
        return new PointConfig(id, address, type, ByteOrder.ABCD, 1, 0, min, max);
    }

    @Test
    @DisplayName("合法配置校验通过")
    void validConfig() {
        GatewayProperties props = baseProps(point("p1", 0, PointType.INT16, null, null));
        List<String> errors = ConfigValidator.validate(props);
        assertTrue(errors.isEmpty(), "合法配置不应有错误: " + errors);
    }

    @Test
    @DisplayName("点地址区间跨越 65536 边界必须报错并定位到点")
    void addressRangeOverflow() {
        GatewayProperties props = baseProps(point("p-wide", 65535, PointType.INT32, null, null));
        List<String> errors = ConfigValidator.validate(props);
        log.info("越界配置错误: {}", errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("p-wide") && e.contains("越界")));
    }

    @Test
    @DisplayName("量程 min>max 必须报错并定位到点")
    void contradictoryRange() {
        GatewayProperties props = baseProps(point("p-range", 0, PointType.INT16, 100d, 10d));
        List<String> errors = ConfigValidator.validate(props);
        log.info("量程矛盾配置错误: {}", errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("p-range") && e.contains("量程")));
    }

    @Test
    @DisplayName("设备 id 重复、点 id 重复、port 越界都必须被识别")
    void duplicatesAndBounds() {
        GatewayProperties props = new GatewayProperties();
        PointConfig p1 = point("same-id", 0, PointType.INT16, null, null);
        PointConfig p2 = point("same-id", 1, PointType.UINT16, null, null);
        DeviceConfig a = new DeviceConfig("dup", "127.0.0.1", 70000, 1, 1000, 1000, List.of(p1, p2));
        DeviceConfig b = new DeviceConfig("dup", "127.0.0.1", 11502, 1, 1000, 1000,
                List.of(point("x", 0, PointType.INT16, null, null)));
        props.setDevices(List.of(a, b));
        List<String> errors = ConfigValidator.validate(props);
        log.info("重复/越界配置错误: {}", errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("设备 id 重复")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("数据点 id")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("port 越界")));
    }

    @Test
    @DisplayName("字节序或类型缺失必须报错")
    void missingTypeOrOrder() {
        PointConfig noOrder = new PointConfig("p-no-order", 0, PointType.INT16,
                null, 1, 0, null, null);
        GatewayProperties props = baseProps(noOrder);
        List<String> errors = ConfigValidator.validate(props);
        log.info("缺失约定配置错误: {}", errors);
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("p-no-order")));
    }
}
