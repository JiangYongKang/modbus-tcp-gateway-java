package com.github.highcumontoa.gateway.decode;

import com.github.highcumontoa.gateway.config.GatewayProperties;
import com.github.highcumontoa.gateway.model.DataType;
import com.github.highcumontoa.gateway.model.FailureReason;
import com.github.highcumontoa.gateway.model.OctetByteOrder;
import com.github.highcumontoa.gateway.model.WordOrder;

public final class PointDecoder {
    private PointDecoder() {
    }

    public static double decode(GatewayProperties.PointDefinition point, int[] registers) {
        if (point == null) {
            throw new PointDecodeException(FailureReason.CONFIG_ERROR, "Point definition must not be null");
        }
        if (registers == null) {
            throw new PointDecodeException(FailureReason.DECODE_ERROR,
                    "Register payload must not be null for point '" + point.getName() + '\'');
        }

        int required = requiredRegisters(point.getDataType());
        if (registers.length != required) {
            throw new PointDecodeException(FailureReason.DECODE_ERROR,
                    "Point '" + point.getName() + "' expects " + required + " registers for "
                            + point.getDataType() + ", received " + registers.length);
        }

        long rawBits = switch (point.getDataType()) {
            case INT16, UINT16 -> Integer.toUnsignedLong(registers[0]);
            case INT32, UINT32, FLOAT32 -> decodeLong(point, registers);
        };

        double rawValue = switch (point.getDataType()) {
            case INT16 -> (short) rawBits;
            case UINT16 -> rawBits;
            case INT32 -> (int) rawBits;
            case UINT32 -> rawBits;
            case FLOAT32 -> Float.intBitsToFloat((int) rawBits);
        };

        if (!Double.isFinite(rawValue)) {
            throw new PointDecodeException(FailureReason.OUT_OF_RANGE,
                    "Point '" + point.getName() + "' decoded to non-finite value " + rawValue);
        }
        double scale = point.getScale();
        double offset = point.getOffset();
        if (!Double.isFinite(scale) || scale == 0.0d) {
            throw new PointDecodeException(FailureReason.CONFIG_ERROR,
                    "Point '" + point.getName() + "' has invalid scale: " + scale);
        }
        if (!Double.isFinite(offset)) {
            throw new PointDecodeException(FailureReason.CONFIG_ERROR,
                    "Point '" + point.getName() + "' has invalid offset: " + offset);
        }

        double value = rawValue * scale + offset;
        if (!Double.isFinite(value)) {
            throw new PointDecodeException(FailureReason.OUT_OF_RANGE,
                    "Point '" + point.getName() + "' conversion produced non-finite value " + value);
        }
        if (point.getMin() != null && value < point.getMin()) {
            throw new PointDecodeException(FailureReason.OUT_OF_RANGE,
                    "Point '" + point.getName() + "' value " + value + " is below min "
                            + point.getMin());
        }
        if (point.getMax() != null && value > point.getMax()) {
            throw new PointDecodeException(FailureReason.OUT_OF_RANGE,
                    "Point '" + point.getName() + "' value " + value + " is above max "
                            + point.getMax());
        }
        return value;
    }

    public static int requiredRegisters(DataType dataType) {
        return switch (dataType) {
            case INT16, UINT16 -> 1;
            case INT32, UINT32, FLOAT32 -> 2;
        };
    }

    private static long decodeLong(GatewayProperties.PointDefinition point, int[] registers) {
        int wordAtLow = point.getWordOrder() == WordOrder.LOW_WORD_FIRST ? registers[0] : registers[1];
        int wordAtHigh = point.getWordOrder() == WordOrder.LOW_WORD_FIRST ? registers[1] : registers[0];
        if (point.getByteOrder() == OctetByteOrder.LITTLE_ENDIAN) {
            wordAtLow = swapWordBytes(wordAtLow);
            wordAtHigh = swapWordBytes(wordAtHigh);
        }
        return ((long) wordAtHigh << 16) | (wordAtLow & 0xffffL);
    }

    private static int swapWordBytes(int register) {
        return ((register & 0xff) << 8) | ((register >>> 8) & 0xff);
    }
}
