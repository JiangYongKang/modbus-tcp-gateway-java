package com.github.highcumontoa.modbustcpgatewayjava.collect;

import com.github.highcumontoa.modbustcpgatewayjava.config.DeviceConfig;
import com.github.highcumontoa.modbustcpgatewayjava.config.GatewayProperties;
import com.github.highcumontoa.modbustcpgatewayjava.config.PointConfig;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 配置校验：所有错误以“可定位到设备 / 数据点”的文本返回，
 * 网关启动时若存在任何错误将拒绝启动，绝不带着坏配置静默运行。
 */
public final class ConfigValidator {

    private ConfigValidator() {
    }

    public static List<String> validate(GatewayProperties properties) {
        List<String> errors = new ArrayList<>();
        if (properties == null) {
            errors.add("网关配置缺失");
            return errors;
        }
        if (properties.getPollIntervalMs() <= 0) {
            errors.add("gateway.poll-interval-ms 必须为正数: " + properties.getPollIntervalMs());
        }
        if (properties.getStaleThresholdMs() <= 0) {
            errors.add("gateway.stale-threshold-ms 必须为正数: " + properties.getStaleThresholdMs());
        }
        if (properties.getReconnectBackoffInitialMs() <= 0) {
            errors.add("重连初始退避必须为正数");
        }
        if (properties.getReconnectBackoffMaxMs() < properties.getReconnectBackoffInitialMs()) {
            errors.add("重连最大退避不得小于初始退避");
        }
        if (properties.getDevices() == null || properties.getDevices().isEmpty()) {
            errors.add("至少需要配置一台设备 gateway.devices[*]");
            return errors;
        }

        Set<String> deviceIds = new HashSet<>();
        for (DeviceConfig device : properties.getDevices()) {
            validateDevice(device, deviceIds, errors);
        }
        return errors;
    }

    private static void validateDevice(DeviceConfig device, Set<String> deviceIds,
                                       List<String> errors) {
        String prefix = "设备[" + (device.id() == null ? "<null>" : device.id()) + "]";
        if (device.id() == null || device.id().isBlank()) {
            errors.add("设备 id 不能为空");
        } else if (!deviceIds.add(device.id())) {
            errors.add(prefix + " 设备 id 重复");
        }
        if (device.host() == null || device.host().isBlank()) {
            errors.add(prefix + " host 不能为空");
        }
        if (device.port() < 1 || device.port() > 65535) {
            errors.add(prefix + " port 越界: " + device.port());
        }
        if (device.unitId() < 0 || device.unitId() > 255) {
            errors.add(prefix + " unit-id 越界(0..255): " + device.unitId());
        }
        if (device.connectTimeoutMs() <= 0) {
            errors.add(prefix + " connect-timeout-ms 必须为正数");
        }
        if (device.responseTimeoutMs() <= 0) {
            errors.add(prefix + " response-timeout-ms 必须为正数");
        }
        if (device.points() == null || device.points().isEmpty()) {
            errors.add(prefix + " 至少需要一个数据点");
            return;
        }

        Set<String> pointIds = new HashSet<>();
        for (PointConfig point : device.points()) {
            String pp = prefix + " 数据点[" + (point.id() == null ? "<null>" : point.id()) + "]";
            if (point.id() == null || point.id().isBlank()) {
                errors.add(prefix + " 数据点 id 不能为空");
            } else if (!pointIds.add(point.id())) {
                errors.add(pp + " 数据点 id 在该设备内重复");
            }
            if (point.type() == null) {
                errors.add(pp + " type 缺失");
            }
            if (point.order() == null) {
                errors.add(pp + " order(字节序/字序) 缺失");
            }
            if (point.address() < 0 || point.address() > 65535) {
                errors.add(pp + " address 越界(0..65535): " + point.address());
            } else if (point.type() != null) {
                long endExclusive = point.address() + point.type().registers();
                if (endExclusive > 65536) {
                    errors.add(pp + " 地址区间越界: 起始=" + point.address()
                            + " 宽度(寄存器)=" + point.type().registers());
                }
            }
            if (point.scale() == 0d || !Double.isFinite(point.scale())) {
                errors.add(pp + " scale 必须为非零有限数: " + point.scale());
            }
            if (!Double.isFinite(point.offset())) {
                errors.add(pp + " offset 必须为有限数: " + point.offset());
            }
            if (point.min() != null && point.max() != null && point.min() > point.max()) {
                errors.add(pp + " 量程配置矛盾: min(" + point.min()
                        + ") > max(" + point.max() + ")");
            }
        }
    }
}
