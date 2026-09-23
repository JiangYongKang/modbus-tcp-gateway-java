package com.github.highcumontoa.gateway.config;

import com.github.highcumontoa.gateway.model.DataType;
import com.github.highcumontoa.gateway.model.FailureReason;
import com.github.highcumontoa.gateway.transport.ModbusTransportException;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PointDefinitionValidatorTest {
    private static final Logger log = LoggerFactory.getLogger(PointDefinitionValidatorTest.class);

    @Test
    void rejectsDuplicateNamesAndInvalidScaleWithPointIdentity() {
        GatewayProperties.PointDefinition first = point("temp", DataType.INT16);
        GatewayProperties.PointDefinition duplicate = point("temp", DataType.FLOAT32);

        ModbusTransportException error = assertThrows(ModbusTransportException.class,
                () -> PointDefinitionValidator.validate(List.of(first, duplicate)));

        log.warn("CONFIG_FAILURE points=temp,temp reason={} basis=point names must uniquely identify readings",
                error.reason());
        assertEquals(FailureReason.CONFIG_ERROR, error.reason());
        assertTrue(error.getMessage().contains("Duplicate point name: temp"));
    }

    @Test
    void rejectsOutOfBoundsAddressesZeroScaleAndInvalidRanges() {
        GatewayProperties.PointDefinition endOfMap = point("past-end", DataType.UINT32);
        endOfMap.setAddress(0xffff);
        ModbusTransportException addressError = assertThrows(ModbusTransportException.class,
                () -> PointDefinitionValidator.validate(List.of(endOfMap)));

        GatewayProperties.PointDefinition badScale = point("bad-scale", DataType.INT16);
        badScale.setScale(0.0d);
        ModbusTransportException scaleError = assertThrows(ModbusTransportException.class,
                () -> PointDefinitionValidator.validate(List.of(badScale)));

        GatewayProperties.PointDefinition badRange = point("bad-range", DataType.INT16);
        badRange.setMin(10.0d);
        badRange.setMax(0.0d);
        ModbusTransportException rangeError = assertThrows(ModbusTransportException.class,
                () -> PointDefinitionValidator.validate(List.of(badRange)));

        log.warn("CONFIG_FAILURE point=past-end reason={} basis=UINT32 crosses 65535", addressError.reason());
        log.warn("CONFIG_FAILURE point=bad-scale reason={} basis=scale must be finite non-zero", scaleError.reason());
        log.warn("CONFIG_FAILURE point=bad-range reason={} basis=min must not exceed max", rangeError.reason());
        assertEquals(FailureReason.CONFIG_ERROR, addressError.reason());
        assertEquals(FailureReason.CONFIG_ERROR, scaleError.reason());
        assertEquals(FailureReason.CONFIG_ERROR, rangeError.reason());
    }

    private static GatewayProperties.PointDefinition point(String name, DataType type) {
        GatewayProperties.PointDefinition point = new GatewayProperties.PointDefinition();
        point.setName(name);
        point.setDataType(type);
        point.setAddress(0);
        point.setScale(1.0d);
        return point;
    }
}
