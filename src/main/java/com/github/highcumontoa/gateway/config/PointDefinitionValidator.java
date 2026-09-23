package com.github.highcumontoa.gateway.config;

import com.github.highcumontoa.gateway.decode.PointDecoder;
import com.github.highcumontoa.gateway.model.FailureReason;
import com.github.highcumontoa.gateway.transport.ModbusTransportException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class PointDefinitionValidator {
    private PointDefinitionValidator() {
    }

    public static void validate(List<GatewayProperties.PointDefinition> points) {
        if (points == null) {
            throw new ModbusTransportException(FailureReason.CONFIG_ERROR, "Point list must not be null");
        }

        Set<String> names = new HashSet<>();
        for (int i = 0; i < points.size(); i++) {
            GatewayProperties.PointDefinition point = points.get(i);
            String label = point == null ? "points[" + i + ']'
                    : (point.getName() == null || point.getName().isBlank()
                            ? "points[" + i + ']' : point.getName());
            validatePoint(point, label, names);
        }
    }

    private static void validatePoint(GatewayProperties.PointDefinition point, String label,
                                      Set<String> names) {
        if (point == null) {
            configError(label + " must not be null");
        }
        if (point.getName() == null || point.getName().isBlank()) {
            configError(label + " name must not be blank");
        }
        if (!names.add(point.getName())) {
            configError("Duplicate point name: " + point.getName());
        }
        if (point.getUnitId() < 0 || point.getUnitId() > 247) {
            configError("Point '" + label + "' unitId must be between 0 and 247");
        }
        int registerCount = PointDecoder.requiredRegisters(point.getDataType());
        if (point.getAddress() < 0 || point.getAddress() > 0xffff) {
            configError("Point '" + label + "' address must be between 0 and 65535");
        }
        if (point.getAddress() + registerCount > 0x10000) {
            configError("Point '" + label + "' reads beyond register 65535");
        }
        if (!Double.isFinite(point.getScale()) || point.getScale() == 0.0d) {
            configError("Point '" + label + "' scale must be finite and non-zero");
        }
        if (!Double.isFinite(point.getOffset())) {
            configError("Point '" + label + "' offset must be finite");
        }
        if (point.getMin() != null && !Double.isFinite(point.getMin())) {
            configError("Point '" + label + "' min must be finite");
        }
        if (point.getMax() != null && !Double.isFinite(point.getMax())) {
            configError("Point '" + label + "' max must be finite");
        }
        if (point.getMin() != null && point.getMax() != null && point.getMin() > point.getMax()) {
            configError("Point '" + label + "' min must not be greater than max");
        }
    }

    private static void configError(String message) {
        throw new ModbusTransportException(FailureReason.CONFIG_ERROR, message);
    }
}
