package com.github.highcumontoa.modbustcpgatewayjava.protocol;

import com.github.highcumontoa.modbustcpgatewayjava.model.FailReason;

/**
 * TCP 流上的 Modbus 帧重组器：
 * 处理半包（数据不完整时等待后续字节）与粘包（一次到达多帧时逐帧切出）。
 *
 * 一旦 MBAP 头部自相矛盾（protocolId 非 0、length 非法、整帧超过协议上限），
 * 立即抛出 ILLEGAL_FRAME，调用方必须丢弃该连接——继续解析无法保证对齐。
 */
public class FramingBuffer {

    /** Modbus TCP 最大帧：MBAP 6 + length 上限（unitId + 最大 PDU 253） */
    static final int MAX_FRAME_LEN = 6 + 253;

    private byte[] buf = new byte[512];
    private int len;

    public void append(byte[] chunk, int n) {
        if (n <= 0) {
            return;
        }
        ensure(len + n);
        System.arraycopy(chunk, 0, buf, len, n);
        len += n;
    }

    /**
     * 尝试从缓冲中取出一帧。
     *
     * @return 完整帧的拷贝；数据不足（半包）时返回 null
     * @throws ModbusRequestException 帧头自相矛盾时，原因为 ILLEGAL_FRAME
     */
    public byte[] pollFrame() throws ModbusRequestException {
        if (len < ModbusFrames.MBAP_AND_UNIT) {
            return null;
        }
        int protocolId = ModbusFrames.protocolId(buf);
        if (protocolId != ModbusFrames.PROTOCOL_ID) {
            throw new ModbusRequestException(FailReason.ILLEGAL_FRAME,
                    "MBAP protocolId 非 0，实际=" + protocolId
                            + "，原始字节=" + HexUtils.toHex(buf, 0, Math.min(len, 16)));
        }
        int lengthField = ModbusFrames.lengthField(buf);
        if (lengthField < 2) {
            throw new ModbusRequestException(FailReason.ILLEGAL_FRAME,
                    "MBAP length 非法（必须 >=2），实际=" + lengthField);
        }
        int total = ModbusFrames.MBAP_PREFIX + lengthField;
        if (total > MAX_FRAME_LEN) {
            throw new ModbusRequestException(FailReason.ILLEGAL_FRAME,
                    "整帧长度超过 Modbus TCP 上限 " + MAX_FRAME_LEN + "，声明长度=" + total);
        }
        if (len < total) {
            return null; // 半包：等待更多字节
        }
        byte[] frame = new byte[total];
        System.arraycopy(buf, 0, frame, 0, total);
        int remaining = len - total;
        if (remaining > 0) {
            System.arraycopy(buf, total, buf, 0, remaining);
        }
        len = remaining;
        return frame;
    }

    public boolean isEmpty() {
        return len == 0;
    }

    public int size() {
        return len;
    }

    /** 当前缓冲内容的拷贝（用于打印残缺报文）。 */
    public byte[] copyBytes() {
        byte[] out = new byte[len];
        System.arraycopy(buf, 0, out, 0, len);
        return out;
    }

    public void clear() {
        len = 0;
    }

    private void ensure(int capacity) {
        if (capacity <= buf.length) {
            return;
        }
        int newCap = Math.max(buf.length * 2, capacity);
        // 超过单帧上限仍持续增长说明对端持续塞入无边界数据，直接拒绝
        if (newCap > MAX_FRAME_LEN * 2) {
            len = 0;
            throw new IllegalStateException("缓冲增长超过协议上限，疑似无边界数据流");
        }
        byte[] grown = new byte[newCap];
        System.arraycopy(buf, 0, grown, 0, len);
        buf = grown;
    }
}
