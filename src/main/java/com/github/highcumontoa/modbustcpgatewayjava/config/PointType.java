package com.github.highcumontoa.modbustcpgatewayjava.config;

/**
 * 支持的点类型及其寄存器宽度（以 16 位寄存器计）。
 * 寄存器类型只采集保持寄存器（FC=0x03）。
 */
public enum PointType {
    INT16(1),
    UINT16(1),
    INT32(2),
    UINT32(2),
    INT64(4),
    FLOAT32(2),
    FLOAT64(4);

    private final int registers;

    PointType(int registers) {
        this.registers = registers;
    }

    public int registers() {
        return registers;
    }

    public int bytes() {
        return registers * 2;
    }
}
