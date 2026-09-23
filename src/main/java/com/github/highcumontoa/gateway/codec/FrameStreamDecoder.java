package com.github.highcumontoa.gateway.codec;

import com.github.highcumontoa.gateway.model.FailureReason;
import com.github.highcumontoa.gateway.transport.ModbusTransportException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

public class FrameStreamDecoder {
    private static final int HEADER_LENGTH = 7;

    public ModbusResponse readFrame(InputStream inputStream) {
        try {
            byte[] header = new byte[HEADER_LENGTH];
            int headerRead = readFully(inputStream, header);
            if (headerRead == 0) {
                return null;
            }
            if (headerRead < HEADER_LENGTH) {
                throw new ModbusTransportException(FailureReason.CONNECTION_CLOSED,
                        "Connection closed after " + headerRead + " of " + HEADER_LENGTH
                                + " MBAP header bytes; partialRaw="
                                + toHex(Arrays.copyOf(header, headerRead)));
            }

            int protocolId = unsignedShort(header, 2);
            int length = unsignedShort(header, 4);
            if (protocolId != 0) {
                throw new ModbusTransportException(FailureReason.FRAMING_ERROR,
                        "Invalid Modbus TCP protocol id: " + protocolId + "; raw=" + toHex(header));
            }
            if (length < 2 || length > 253) {
                throw new ModbusTransportException(FailureReason.FRAMING_ERROR,
                        "MBAP length must be between 2 and 253, was: " + length + "; raw=" + toHex(header));
            }

            int followingLength = length - 1;
            byte[] following = new byte[followingLength];
            int bytesRead = readFully(inputStream, following);
            if (bytesRead < followingLength) {
                throw new ModbusTransportException(FailureReason.CONNECTION_CLOSED,
                        "Connection closed after " + bytesRead + " of " + followingLength
                                + " following-bytes; partialRaw="
                                + toHex(concat(header, Arrays.copyOf(following, bytesRead))));
            }

            int pduLength = followingLength;
            byte[] pdu = following;
            byte[] rawFrame = concat(header, pdu);
            int transactionId = unsignedShort(header, 0);
            int unitId = header[6] & 0xff;
            int functionCode = pdu[0] & 0xff;

            if ((functionCode & 0x80) != 0) {
                if (pduLength != 2) {
                    throw malformed("Exception response must contain function code and one exception code, pduLength="
                            + pduLength, rawFrame);
                }
                return new ModbusResponse(transactionId, unitId, functionCode & 0x7f,
                        new int[0], rawFrame, pdu[1] & 0xff);
            }

            if (functionCode != 3 && functionCode != 4) {
                throw new ModbusTransportException(FailureReason.PROTOCOL_ERROR,
                        "Unsupported response function code: " + functionCode + "; raw=" + toHex(rawFrame));
            }
            if (pduLength < 2) {
                throw malformed("Read response is too short, pduLength=" + pduLength, rawFrame);
            }

            int byteCount = pdu[1] & 0xff;
            if (byteCount < 2 || byteCount > 250 || (byteCount & 1) != 0) {
                throw malformed("Invalid register byte count: " + byteCount, rawFrame);
            }
            if (pduLength != byteCount + 2) {
                throw malformed("PDU length " + pduLength + " does not match byte count "
                        + byteCount, rawFrame);
            }

            int[] registers = new int[byteCount / 2];
            for (int i = 0; i < registers.length; i++) {
                registers[i] = ((pdu[2 + i * 2] & 0xff) << 8) | (pdu[3 + i * 2] & 0xff);
            }
            return new ModbusResponse(transactionId, unitId, functionCode, registers, rawFrame, null);
        } catch (IOException e) {
            throw new ModbusTransportException(FailureReason.CONNECTION_CLOSED,
                    "I/O error while reading Modbus frame: " + e.getMessage(), e);
        }
    }

    private int readFully(InputStream inputStream, byte[] target) throws IOException {
        int offset = 0;
        while (offset < target.length) {
            int count = inputStream.read(target, offset, target.length - offset);
            if (count < 0) {
                return offset;
            }
            offset += count;
        }
        return offset;
    }

    private ModbusTransportException malformed(String message, byte[] raw) {
        return new ModbusTransportException(FailureReason.MALFORMED_RESPONSE,
                message + "; raw=" + toHex(raw));
    }

    private static int unsignedShort(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    public static String toHex(byte[] bytes) {
        if (bytes == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02X", value & 0xff));
        }
        return builder.toString();
    }
}
