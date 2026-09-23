package com.github.highcumontoa.gateway.codec;

import com.github.highcumontoa.gateway.model.FailureReason;
import com.github.highcumontoa.gateway.transport.ModbusTransportException;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameStreamDecoderTest {
    private static final Logger log = LoggerFactory.getLogger(FrameStreamDecoderTest.class);
    private final FrameStreamDecoder decoder = new FrameStreamDecoder();

    @Test
    void decodesValidFrameAfterArbitraryFragmentBoundaries() throws IOException {
        byte[] frame = frame(0x0102, 0x01, 0x03, 0x04, 0x12, 0x34, 0x56, 0x78);
        for (int chunkSize = 1; chunkSize <= frame.length; chunkSize++) {
            ModbusResponse response = decoder.readFrame(chunked(frame, chunkSize));
            assertEquals(0x0102, response.transactionId());
            assertEquals(1, response.unitId());
            assertEquals(3, response.functionCode());
            assertArrayEquals(new int[]{0x1234, 0x5678}, response.registers());
            assertArrayEquals(frame, response.rawFrame());
        }
        log.info("FRAME_TEST raw={} basis=header length 7 + PDU length 6 was parsed through every fragment size",
                FrameStreamDecoder.toHex(frame));
    }

    @Test
    void readsOnlyOneFrameWhenFramesAreSticky() {
        byte[] first = frame(1, 1, 3, 2, 0x00, 0x01);
        byte[] second = frame(2, 1, 3, 2, 0x00, 0x02);
        InputStream input = new ByteArrayInputStream(concat(first, second));

        ModbusResponse response = decoder.readFrame(input);

        assertEquals(1, response.transactionId());
        assertArrayEquals(new int[]{1}, response.registers());
        log.info("FRAME_TEST rawFirst={} rawFollowing={} basis=readFrame consumes exactly MBAP length only",
                FrameStreamDecoder.toHex(first), FrameStreamDecoder.toHex(second));
    }

    @Test
    void rejectsInvalidProtocolTruncatedAndMismatchedLengths() {
        assertFailure(replace(frame(1, 1, 3, 2, 1, 2), 3, 0x01), FailureReason.FRAMING_ERROR,
                "protocol id must be zero");
        assertFailure(concat(head(1, 0, 254), new byte[]{3, 2}), FailureReason.FRAMING_ERROR,
                "MBAP length has valid range");
        assertFailure(new byte[]{0, 1, 0, 0, 0, 5, 1, 3, 4, 1, 2},
                FailureReason.MALFORMED_RESPONSE, "PDU length must match byte count");
        assertFailure(new byte[]{0, 1, 0, 0, 0, 5}, FailureReason.CONNECTION_CLOSED,
                "partial header EOF is incomplete");
    }

    @Test
    void parsesModbusExceptionResponse() {
        byte[] raw = {0x00, 0x09, 0x00, 0x00, 0x00, 0x03, 0x01,
                (byte) 0x83, 0x02};
        ModbusResponse response = decoder.readFrame(new ByteArrayInputStream(raw));

        assertTrue(response.exception());
        assertEquals(9, response.transactionId());
        assertEquals(3, response.functionCode());
        assertEquals(2, response.exceptionCode());
        log.info("FRAME_TEST raw={} basis=function high bit 0x80 with exception code 2",
                FrameStreamDecoder.toHex(raw));
    }

    @Test
    void returnsNullOnCleanEndOfStream() {
        assertNull(decoder.readFrame(new ByteArrayInputStream(new byte[0])));
    }

    private void assertFailure(byte[] raw, FailureReason expected, String basis) {
        ModbusTransportException error = assertThrows(ModbusTransportException.class,
                () -> decoder.readFrame(new ByteArrayInputStream(raw)));
        log.warn("FRAME_FAILURE raw={} reason={} basis={}", FrameStreamDecoder.toHex(raw),
                error.reason(), basis);
        assertEquals(expected, error.reason());
    }

    private static byte[] frame(int txn, int unit, int function, int byteCount, int... registerBytes) {
        int pduLength = 2 + byteCount;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeShort(output, txn);
        writeShort(output, 0);
        writeShort(output, pduLength + 1);
        output.write(unit);
        output.write(function);
        output.write(byteCount);
        for (int value : registerBytes) {
            output.write(value);
        }
        return output.toByteArray();
    }

    private static byte[] head(int txn, int protocol, int length) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeShort(output, txn);
        writeShort(output, protocol);
        writeShort(output, length);
        return output.toByteArray();
    }

    private static byte[] replace(byte[] source, int offset, int value) {
        byte[] result = Arrays.copyOf(source, source.length);
        result[offset] = (byte) value;
        return result;
    }

    private static InputStream chunked(byte[] data, int chunkSize) {
        return new InputStream() {
            private int offset;

            @Override
            public int read() {
                return offset >= data.length ? -1 : data[offset++] & 0xff;
            }

            @Override
            public int read(byte[] buffer, int off, int len) {
                if (offset >= data.length) {
                    return -1;
                }
                int count = Math.min(Math.min(chunkSize, len), data.length - offset);
                System.arraycopy(data, offset, buffer, off, count);
                offset += count;
                return count;
            }
        };
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static void writeShort(ByteArrayOutputStream output, int value) {
        output.write(value >>> 8);
        output.write(value);
    }
}
