package com.github.highcumontoa.modbustcpgatewayjava.codec;

import com.github.highcumontoa.modbustcpgatewayjava.config.ByteOrder;
import com.github.highcumontoa.modbustcpgatewayjava.config.PointConfig;
import com.github.highcumontoa.modbustcpgatewayjava.config.PointType;
import java.util.Objects;

/**
 * 将寄存器区原始字节按数据点约定解码为工程量值。
 *
 * 约定定义：
 * <ul>
 *   <li>线序基准 ABCD：高字在低地址，字内高字节在前（Modbus 标准大端）；</li>
 *   <li>BADC：字顺序不变，每个字内部两个字节交换；</li>
 *   <li>CDAB：字整体逆序（低字在低地址），字内字节顺序不变；</li>
 *   <li>DCBA：字逆序且字内字节交换（整体小端）。</li>
 * </ul>
 * 工程量 = 原始值 * scale + offset，随后做 [min,max] 量程校验。
 * 任何越界、非有限浮点、NaN 等异常都抛出 {@link DecodeException}，绝不静默产出错误数值。
 */
public class PointDecoder {

    /**
     * @param point         数据点配置
     * @param registerBytes FC=0x03 响应数据区的完整字节（线序）
     * @param pointOffset   该点数据在 registerBytes 内的字节偏移
     */
    public double decode(PointConfig point, byte[] registerBytes, int pointOffset)
            throws DecodeException {
        Objects.requireNonNull(point, "point config must not be null");
        Objects.requireNonNull(registerBytes, "register bytes must not be null");
        if (pointOffset < 0 || pointOffset % 2 != 0) {
            throw new DecodeException(null, point.id(),
                    "字节偏移非法（必须为非负偶数）: " + pointOffset);
        }
        int width = point.type().bytes();
        if (pointOffset + width > registerBytes.length) {
            throw new DecodeException(null, point.id(),
                    "寄存器数据越界: 点需要 " + width + " 字节，偏移 " + pointOffset
                            + "，响应数据区仅 " + registerBytes.length + " 字节");
        }

        byte[] raw = reorder(point, registerBytes, pointOffset);
        double nativeValue = interpret(point.id(), point.type(), raw);
        if (!Double.isFinite(nativeValue)) {
            throw new DecodeException(null, point.id(),
                    "解码得到非有限数值（NaN/Infinity），按非法读数处理");
        }
        if (point.scale() == 0d) {
            throw new DecodeException(null, point.id(), "缩放系数 scale 不能为 0");
        }
        double engineering = nativeValue * point.scale() + point.offset();
        if (!Double.isFinite(engineering)) {
            throw new DecodeException(null, point.id(),
                    "缩放/偏移后得到非有限数值，按非法读数处理");
        }
        if (point.min() != null && engineering < point.min()) {
            throw new DecodeException(null, point.id(),
                    "工程量 " + engineering + " 低于下限 " + point.min());
        }
        if (point.max() != null && engineering > point.max()) {
            throw new DecodeException(null, point.id(),
                    "工程量 " + engineering + " 高于上限 " + point.max());
        }
        return engineering;
    }

    /** 按字序 / 字节序约定把点的原始字节重排为标准大端序列。 */
    private byte[] reorder(PointConfig point, byte[] data, int offset) {
        PointType type = point.type();
        ByteOrder order = point.order() == null ? ByteOrder.ABCD : point.order();
        int words = type.registers();
        byte[] out = new byte[type.bytes()];
        boolean wordSwap = order == ByteOrder.CDAB || order == ByteOrder.DCBA;
        boolean byteSwap = order == ByteOrder.BADC || order == ByteOrder.DCBA;
        for (int w = 0; w < words; w++) {
            int srcWord = wordSwap ? words - 1 - w : w;
            int src = offset + srcWord * 2;
            int dst = w * 2;
            if (byteSwap) {
                out[dst] = data[src + 1];
                out[dst + 1] = data[src];
            } else {
                out[dst] = data[src];
                out[dst + 1] = data[src + 1];
            }
        }
        return out;
    }

    /** 把已重排为大端的字节按类型解释为 double。 */
    private double interpret(String pointId, PointType type, byte[] b) throws DecodeException {
        try {
            return switch (type) {
                case INT16 -> (double) (short) u16(b, 0);
                case UINT16 -> (double) u16(b, 0);
                case INT32 -> (double) (int) u32(b, 0);
                case UINT32 -> (double) u32(b, 0);
                case INT64 -> (double) longAt(b, 0);
                case FLOAT32 -> (double) Float.intBitsToFloat((int) u32(b, 0));
                case FLOAT64 -> Double.longBitsToDouble(longAt(b, 0));
            };
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new DecodeException(null, pointId, "类型解释时字节宽度不足: " + e.getMessage());
        }
    }

    private static int u16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static long u32(byte[] b, int off) {
        return ((long) (b[off] & 0xFF) << 24)
                | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8)
                | (b[off + 3] & 0xFF);
    }

    private static long longAt(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (b[off + i] & 0xFFL);
        }
        return v;
    }
}
