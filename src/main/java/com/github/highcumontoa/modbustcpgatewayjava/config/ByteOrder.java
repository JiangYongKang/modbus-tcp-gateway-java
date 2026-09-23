package com.github.highcumontoa.modbustcpgatewayjava.config;

/**
 * 多寄存器数据的排列约定。
 * 字（word）= Modbus 寄存器（16 位）；BYTE 描述一个字内部两个字节的顺序，
 * WORD 描述多个字在地址上的排列顺序。
 */
public enum ByteOrder {
    /** 高字在前；字内高字节在前（ABCD，Big Endian，Modbus 默认） */
    ABCD,
    /** 高字在前；字内低字节在前（BADC，字节交换） */
    BADC,
    /** 低字在前；字内高字节在前（CDAB，字交换，Schneider 常见） */
    CDAB,
    /** 低字在前；字内低字节在前（DCBA，Little Endian） */
    DCBA
}
