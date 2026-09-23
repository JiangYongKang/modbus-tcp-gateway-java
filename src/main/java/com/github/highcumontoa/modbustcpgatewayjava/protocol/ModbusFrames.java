package com.github.highcumontoa.modbustcpgatewayjava.protocol;

import com.github.highcumontoa.modbustcpgatewayjava.model.FailReason;

/**
 * Modbus TCP 帧（MBAP + PDU）的常量、构造与解析工具。
 *
 * <pre>
 * MBAP(7B): transactionId(2) protocolId(2=0) length(2, 含 unitId 与 PDU) unitId(1)
 * 请求 PDU(5B): functionCode(1=0x03) startAddress(2) quantity(2)
 * 正常响应 PDU: functionCode(1=0x03) byteCount(1) data(byteCount)
 * 异常响应 PDU: functionCode(1=0x83) exceptionCode(1)
 * </pre>
 */
public final class ModbusFrames {

    public static final int FUNCTION_READ_HOLDING_REGISTERS = 0x03;
    public static final int MBAP_PREFIX = 6;          // 不含 unitId
    public static final int MBAP_AND_UNIT = 7;
    public static final int MIN_REQUEST_LEN = 12;
    public static final int MAX_PDU_DATA = 250;
    public static final int MAX_REGISTERS = 125;     // FC03 单次最多 125 寄存器
    public static final int PROTOCOL_ID = 0;

    private ModbusFrames() {
    }

    /** 构造读保持寄存器请求帧（固定 12 字节）。 */
    public static byte[] buildReadHoldingRegisters(int transactionId, int unitId,
                                                   int address, int quantity) {
        if (quantity < 1 || quantity > MAX_REGISTERS) {
            throw new IllegalArgumentException("寄存器数量必须在 1.." + MAX_REGISTERS);
        }
        if (address < 0 || address > 0xFFFF) {
            throw new IllegalArgumentException("寄存器地址必须在 0..65535");
        }
        byte[] frame = new byte[MIN_REQUEST_LEN];
        frame[0] = (byte) (transactionId >>> 8);
        frame[1] = (byte) transactionId;
        frame[2] = 0;
        frame[3] = 0;
        frame[4] = 0;
        frame[5] = 6; // unitId + 5 字节 PDU
        frame[6] = (byte) unitId;
        frame[7] = FUNCTION_READ_HOLDING_REGISTERS;
        frame[8] = (byte) (address >>> 8);
        frame[9] = (byte) address;
        frame[10] = (byte) (quantity >>> 8);
        frame[11] = (byte) quantity;
        return frame;
    }

    public static int transactionId(byte[] frame) {
        return u16(frame, 0);
    }

    public static int protocolId(byte[] frame) {
        return u16(frame, 2);
    }

    /** MBAP length 字段：unitId 与 PDU 的字节数。 */
    public static int lengthField(byte[] frame) {
        return u16(frame, 4);
    }

    public static int unitId(byte[] frame) {
        return frame[6] & 0xFF;
    }

    public static int functionCode(byte[] frame) {
        return frame[7] & 0xFF;
    }

    public static int byteCount(byte[] frame) {
        return frame[8] & 0xFF;
    }

    public static boolean isExceptionFunction(int functionCode) {
        return (functionCode & 0x80) != 0;
    }

    /** Modbus 异常码 -> 可区分失败原因。 */
    public static FailReason mapExceptionCode(int code) {
        return switch (code) {
            case 0x01 -> FailReason.MODBUS_ILLEGAL_FUNCTION;
            case 0x02 -> FailReason.MODBUS_ILLEGAL_DATA_ADDRESS;
            case 0x03 -> FailReason.MODBUS_ILLEGAL_DATA_VALUE;
            case 0x04 -> FailReason.MODBUS_SERVER_DEVICE_FAILURE;
            default -> FailReason.MODBUS_EXCEPTION_OTHER;
        };
    }

    private static int u16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }
}
