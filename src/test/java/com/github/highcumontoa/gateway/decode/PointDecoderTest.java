package com.github.highcumontoa.gateway.decode;

import com.github.highcumontoa.gateway.config.GatewayProperties;
import com.github.highcumontoa.gateway.model.DataType;
import com.github.highcumontoa.gateway.model.FailureReason;
import com.github.highcumontoa.gateway.model.OctetByteOrder;
import com.github.highcumontoa.gateway.model.WordOrder;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PointDecoderTest {
    private static final Logger log = LoggerFactory.getLogger(PointDecoderTest.class);

    @Test
    void decodesSignedAndUnsigned16BitRegisters() {
        int[] raw = {0xfff6};
        GatewayProperties.PointDefinition signed = point("signed", DataType.INT16,
                OctetByteOrder.BIG_ENDIAN, WordOrder.HIGH_WORD_FIRST, 1.0, 0.0);
        GatewayProperties.PointDefinition unsigned = point("unsigned", DataType.UINT16,
                OctetByteOrder.BIG_ENDIAN, WordOrder.HIGH_WORD_FIRST, 1.0, 0.0);

        double signedValue = PointDecoder.decode(signed, raw);
        double unsignedValue = PointDecoder.decode(unsigned, raw);

        log.info("DECODE_TEST raw={} signed={} unsigned={} basis=INT16 sign-extends; UINT16 zero-extends",
                hex(raw), signedValue, unsignedValue);
        assertEquals(-10.0d, signedValue);
        assertEquals(65526.0d, unsignedValue);
    }

    @Test
    void decodesAll32BitWordAndByteOrders() {
        GatewayProperties.PointDefinition abcd = point("abcd", DataType.UINT32,
                OctetByteOrder.BIG_ENDIAN, WordOrder.HIGH_WORD_FIRST, 1.0, 0.0);
        GatewayProperties.PointDefinition badc = point("badc", DataType.UINT32,
                OctetByteOrder.LITTLE_ENDIAN, WordOrder.HIGH_WORD_FIRST, 1.0, 0.0);
        GatewayProperties.PointDefinition cdab = point("cdab", DataType.UINT32,
                OctetByteOrder.BIG_ENDIAN, WordOrder.LOW_WORD_FIRST, 1.0, 0.0);
        GatewayProperties.PointDefinition dcba = point("dcba", DataType.UINT32,
                OctetByteOrder.LITTLE_ENDIAN, WordOrder.LOW_WORD_FIRST, 1.0, 0.0);
        int[] raw = {0x1234, 0x5678};

        assertEquals(0x12345678L, PointDecoder.decode(abcd, raw), 0.0d);
        assertEquals(0x34127856L, PointDecoder.decode(badc, raw), 0.0d);
        assertEquals(0x56781234L, PointDecoder.decode(cdab, raw), 0.0d);
        assertEquals(0x78563412L, PointDecoder.decode(dcba, raw), 0.0d);
        log.info("DECODE_TEST raw={} order=ABCD,BADC,CDAB,DCBA basis=word swap then word-internal byte swap",
                hex(raw));
    }

    @Test
    void decodesSigned32BitAndFloatWithScaleAndOffset() {
        GatewayProperties.PointDefinition signed = point("negative32", DataType.INT32,
                OctetByteOrder.BIG_ENDIAN, WordOrder.HIGH_WORD_FIRST, 1.0, 0.0);
        GatewayProperties.PointDefinition scaled = point("scaled", DataType.INT16,
                OctetByteOrder.BIG_ENDIAN, WordOrder.HIGH_WORD_FIRST, 0.1, -0.5);
        GatewayProperties.PointDefinition floating = point("float", DataType.FLOAT32,
                OctetByteOrder.BIG_ENDIAN, WordOrder.HIGH_WORD_FIRST, 1.0, 0.0);

        double signedValue = PointDecoder.decode(signed, new int[]{0xffff, 0xffff});
        double scaledValue = PointDecoder.decode(scaled, new int[]{0x04d2});
        float floatValue = (float) PointDecoder.decode(floating, new int[]{0x41cc, 0x0000});

        log.info("DECODE_TEST signedRaw=FFFFFFFF signed={} scaledRaw=04D2 scaled={} floatRaw=41CC0000 float={}",
                signedValue, scaledValue, floatValue);
        assertEquals(-1.0d, signedValue);
        assertEquals(122.9d, scaledValue, 0.000001d);
        assertEquals(25.5f, floatValue, 0.000001f);
    }

    @Test
    void rejectsWrongRegisterCountAndOutOfRangeValues() {
        GatewayProperties.PointDefinition uint32 = point("bad-count", DataType.UINT32,
                OctetByteOrder.BIG_ENDIAN, WordOrder.HIGH_WORD_FIRST, 1.0, 0.0);
        GatewayProperties.PointDefinition limited = point("limited", DataType.INT16,
                OctetByteOrder.BIG_ENDIAN, WordOrder.HIGH_WORD_FIRST, 1.0, 0.0);
        limited.setMin(0.0d);
        limited.setMax(5.0d);

        PointDecodeException countError = assertThrows(PointDecodeException.class,
                () -> PointDecoder.decode(uint32, new int[]{0x0001}));
        PointDecodeException rangeError = assertThrows(PointDecodeException.class,
                () -> PointDecoder.decode(limited, new int[]{0xfff6}));

        log.warn("DECODE_FAILURE raw=0001 reason={} basis=UINT32 needs 2 registers", countError.reason());
        log.warn("DECODE_FAILURE raw=FFF6 reason={} basis=value -10 is outside [0,5]", rangeError.reason());
        assertEquals(FailureReason.DECODE_ERROR, countError.reason());
        assertEquals(FailureReason.OUT_OF_RANGE, rangeError.reason());
    }

    private static GatewayProperties.PointDefinition point(String name, DataType type,
                                                           OctetByteOrder byteOrder,
                                                           WordOrder wordOrder,
                                                           double scale, double offset) {
        GatewayProperties.PointDefinition definition = new GatewayProperties.PointDefinition();
        definition.setName(name);
        definition.setDataType(type);
        definition.setByteOrder(byteOrder);
        definition.setWordOrder(wordOrder);
        definition.setScale(scale);
        definition.setOffset(offset);
        return definition;
    }

    private static String hex(int[] registers) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < registers.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(String.format("%04X", registers[i] & 0xffff));
        }
        return builder.append(']').toString();
    }
}
