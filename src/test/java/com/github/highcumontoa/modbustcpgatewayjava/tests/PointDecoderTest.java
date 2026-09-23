package com.github.highcumontoa.modbustcpgatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.highcumontoa.modbustcpgatewayjava.codec.DecodeException;
import com.github.highcumontoa.modbustcpgatewayjava.codec.PointDecoder;
import com.github.highcumontoa.modbustcpgatewayjava.config.ByteOrder;
import com.github.highcumontoa.modbustcpgatewayjava.config.PointConfig;
import com.github.highcumontoa.modbustcpgatewayjava.config.PointType;
import com.github.highcumontoa.modbustcpgatewayjava.protocol.HexUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 数据点解码约定测试：同一组字节在不同字节序/字序、符号、缩放量程下必须得到确定结果；
 * 越界与类型不匹配必须抛出可定位到数据点的 DecodeException。
 */
class PointDecoderTest {

    private static final Logger log = LoggerFactory.getLogger(PointDecoderTest.class);

    private final PointDecoder decoder = new PointDecoder();

    private PointConfig p(String id, PointType type, ByteOrder order,
                         double scale, double offset, Double min, Double max) {
        return new PointConfig(id, 0, type, order, scale, offset, min, max);
    }

    @Test
    @DisplayName("UINT16/INT16：有符号与无符号解释")
    void int16Signedness() throws DecodeException {
        byte[] wire = HexUtils.fromHex("FFFF");
        double unsigned = decoder.decode(p("u", PointType.UINT16, ByteOrder.ABCD, 1, 0, null, null), wire, 0);
        double signed = decoder.decode(p("s", PointType.INT16, ByteOrder.ABCD, 1, 0, null, null), wire, 0);
        log.info("原始=FFFF UINT16={} INT16={}", unsigned, signed);
        assertEquals(65535d, unsigned, 0d);
        assertEquals(-1d, signed, 0d);
    }

    @Test
    @DisplayName("INT32 四种字节序/字序对同一 32 位模式 0x12345678 得到确定结果")
    void int32FourOrders() throws DecodeException {
        // ABCD 线序即大端 12 34 56 78
        byte[] abcd = HexUtils.fromHex("12345678");
        double abcdVal = decoder.decode(p("p", PointType.INT32, ByteOrder.ABCD, 1, 0, null, null), abcd, 0);
        double badc = decoder.decode(p("p", PointType.INT32, ByteOrder.BADC, 1, 0, null, null), HexUtils.fromHex("34127856"), 0);
        double cdab = decoder.decode(p("p", PointType.INT32, ByteOrder.CDAB, 1, 0, null, null), HexUtils.fromHex("56781234"), 0);
        double dcba = decoder.decode(p("p", PointType.INT32, ByteOrder.DCBA, 1, 0, null, null), HexUtils.fromHex("78563412"), 0);
        log.info("INT32 0x12345678: ABCD={} BADC={} CDAB={} DCBA={}", abcdVal, badc, cdab, dcba);
        assertEquals(0x12345678, abcdVal, 0d);
        assertEquals(0x12345678, badc, 0d);
        assertEquals(0x12345678, cdab, 0d);
        assertEquals(0x12345678, dcba, 0d);

        // 反向验证：同一段线序字节用不同约定解释，结果必须不同且确定
        double directBadc = decoder.decode(p("p", PointType.INT32, ByteOrder.BADC, 1, 0, null, null), abcd, 0);
        double directCdab = decoder.decode(p("p", PointType.INT32, ByteOrder.CDAB, 1, 0, null, null), abcd, 0);
        double directDcba = decoder.decode(p("p", PointType.INT32, ByteOrder.DCBA, 1, 0, null, null), abcd, 0);
        log.info("同一线序 12 34 56 78 不同约定: BADC={} CDAB={} DCBA={}", directBadc, directCdab, directDcba);
        assertEquals(0x34127856, directBadc, 0d);
        assertEquals(0x56781234L, directCdab, 0d);
        assertEquals(0x78563412L, directDcba, 0d);
    }

    @Test
    @DisplayName("FLOAT32：DCBA(整体小端) 线序正确还原 π")
    void float32LittleEndian() throws DecodeException {
        int bits = Float.floatToRawIntBits(3.14f);
        byte[] little = new byte[] {
                (byte) bits, (byte) (bits >>> 8), (byte) (bits >>> 16), (byte) (bits >>> 24)};
        double v = decoder.decode(p("f", PointType.FLOAT32, ByteOrder.DCBA, 1, 0, null, null), little, 0);
        log.info("FLOAT32 线序={} DCBA 解码={}", HexUtils.toHex(little), v);
        assertEquals(3.14f, (float) v, 0d);
    }

    @Test
    @DisplayName("INT64：8 字节大端 + scale=0.001 + offset")
    void int64ScaleOffset() throws DecodeException {
        byte[] wire = HexUtils.fromHex("0000000000003039"); // 12345
        PointConfig point = new PointConfig("energy", 0, PointType.INT64, ByteOrder.ABCD,
                0.001, 2.0, null, null);
        double v = decoder.decode(point, wire, 0);
        log.info("INT64 原始={} scale=0.001 offset=2 => {}", HexUtils.toHex(wire), v);
        assertEquals(14.345, v, 1e-9);
    }

    @Test
    @DisplayName("寄存器区内偏移读取（合并区间中的第二个点）")
    void offsetWithinRange() throws DecodeException {
        byte[] range = HexUtils.fromHex("00FA00010000");
        PointConfig second = new PointConfig("p2", 2, PointType.UINT32, ByteOrder.CDAB,
                1, 0, null, null);
        double v = decoder.decode(second, range, 2);
        log.info("区间={} 偏移2 的 UINT32/CDAB={}", HexUtils.toHex(range), v);
        assertEquals(1d, v, 0d);
    }

    @Test
    @DisplayName("越界：响应数据区不足必须报错并定位数据点")
    void outOfBounds() {
        PointConfig point = p("long-point", PointType.INT32, ByteOrder.ABCD, 1, 0, null, null);
        DecodeException ex = assertThrows(DecodeException.class,
                () -> decoder.decode(point, HexUtils.fromHex("0001"), 0));
        log.info("越界判定: point={} msg={}", ex.pointId(), ex.getMessage());
        assertEquals("long-point", ex.pointId());
        assertTrue(ex.getMessage().contains("越界"));
    }

    @Test
    @DisplayName("量程：超出 [min,max] 必须报错，绝不静默产出")
    void rangeLimitViolation() {
        PointConfig point = p("limited", PointType.INT16, ByteOrder.ABCD, 1, 0, -50d, 100d);
        DecodeException ex = assertThrows(DecodeException.class,
                () -> decoder.decode(point, HexUtils.fromHex("03E8"), 0)); // 1000 > 100
        log.info("量程判定: point={} msg={}", ex.pointId(), ex.getMessage());
        assertTrue(ex.getMessage().contains("高于上限"));
    }

    @Test
    @DisplayName("非法 scale=0 必须报错")
    void zeroScale() {
        PointConfig point = p("bad-scale", PointType.INT16, ByteOrder.ABCD, 0, 0, null, null);
        DecodeException ex = assertThrows(DecodeException.class,
                () -> decoder.decode(point, HexUtils.fromHex("0001"), 0));
        log.info("缩放判定: point={} msg={}", ex.pointId(), ex.getMessage());
        assertEquals("bad-scale", ex.pointId());
    }

    @Test
    @DisplayName("奇数偏移（类型不匹配的字节对齐）必须报错")
    void oddOffset() {
        PointConfig point = p("odd", PointType.INT16, ByteOrder.ABCD, 1, 0, null, null);
        DecodeException ex = assertThrows(DecodeException.class,
                () -> decoder.decode(point, HexUtils.fromHex("00000000"), 1));
        log.info("对齐判定: point={} msg={}", ex.pointId(), ex.getMessage());
        assertTrue(ex.getMessage().contains("偏移非法"));
    }
}
