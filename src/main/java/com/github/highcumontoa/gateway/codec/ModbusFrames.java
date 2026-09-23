package com.github.highcumontoa.gateway.codec;

import com.github.highcumontoa.gateway.model.FailureReason;
import com.github.highcumontoa.gateway.transport.ModbusTransportException;

public final class ModbusFrames {
    private ModbusFrames() {
    }

    public static byte[] encodeReadRequest(ModbusRequest request) {
        validate(request);
        byte[] frame = new byte[12];
        writeUnsignedShort(frame, 0, request.transactionId());
        writeUnsignedShort(frame, 2, 0);
        writeUnsignedShort(frame, 4, 6);
        frame[6] = (byte) request.unitId();
        frame[7] = (byte) request.functionCode();
        writeUnsignedShort(frame, 8, request.startAddress());
        writeUnsignedShort(frame, 10, request.quantity());
        return frame;
    }

    private static void validate(ModbusRequest request) {
        if (request == null) {
            throw new ModbusTransportException(FailureReason.PROTOCOL_ERROR, "Modbus request must not be null");
        }
        if (request.transactionId() < 0 || request.transactionId() > 0xffff) {
            throw new ModbusTransportException(FailureReason.PROTOCOL_ERROR,
                    "Invalid transaction id: " + request.transactionId());
        }
        if (request.unitId() < 0 || request.unitId() > 0xff) {
            throw new ModbusTransportException(FailureReason.PROTOCOL_ERROR,
                    "Invalid unit id: " + request.unitId());
        }
        if (request.functionCode() != 3 && request.functionCode() != 4) {
            throw new ModbusTransportException(FailureReason.PROTOCOL_ERROR,
                    "Unsupported Modbus function code: " + request.functionCode());
        }
        if (request.startAddress() < 0 || request.startAddress() > 0xffff) {
            throw new ModbusTransportException(FailureReason.PROTOCOL_ERROR,
                    "Invalid start address: " + request.startAddress());
        }
        if (request.quantity() < 1 || request.quantity() > 125) {
            throw new ModbusTransportException(FailureReason.PROTOCOL_ERROR,
                    "Read quantity must be between 1 and 125, was: " + request.quantity());
        }
    }

    private static void writeUnsignedShort(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 8);
        target[offset + 1] = (byte) value;
    }
}
