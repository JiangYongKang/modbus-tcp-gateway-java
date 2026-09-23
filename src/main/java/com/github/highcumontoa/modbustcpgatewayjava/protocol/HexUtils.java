package com.github.highcumontoa.modbustcpgatewayjava.protocol;

/**
 * 原始报文字节与十六进制文本互转，用于失败判定日志与测试断言。
 */
public final class HexUtils {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private HexUtils() {
    }

    /** 字节序列 -> "00 03 00 00" 形式（大写、空格分隔） */
    public static String toHex(byte[] bytes) {
        if (bytes == null) {
            return "";
        }
        return toHex(bytes, 0, bytes.length);
    }

    public static String toHex(byte[] bytes, int offset, int length) {
        if (bytes == null || length <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(length * 3);
        int end = Math.min(offset + length, bytes.length);
        for (int i = offset; i < end; i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            int v = bytes[i] & 0xFF;
            sb.append(HEX[v >>> 4]).append(HEX[v & 0x0F]);
        }
        return sb.toString();
    }

    public static byte[] fromHex(String text) {
        String compact = text == null ? "" : text.replaceAll("\\s+", "");
        if (compact.length() % 2 != 0) {
            throw new IllegalArgumentException("十六进制文本长度必须为偶数: " + text);
        }
        byte[] out = new byte[compact.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(compact.charAt(i * 2), 16);
            int lo = Character.digit(compact.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("非法十六进制字符: " + text);
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
