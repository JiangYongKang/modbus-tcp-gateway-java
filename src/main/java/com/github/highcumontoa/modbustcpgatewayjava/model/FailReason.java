package com.github.highcumontoa.modbustcpgatewayjava.model;

/**
 * 可区分的失败原因分类。任何失败读数都必须携带其中之一。
 */
public enum FailReason {
    /** 配置错误（数据点定义非法、越界、类型不匹配等） */
    CONFIG_ERROR,
    /** 响应报文可以接收但无法按数据点约定解码 */
    DECODE_ERROR,
    /** 帧结构非法（长度字段不符、功能码不支持、PDU 损坏等） */
    ILLEGAL_FRAME,
    /** 流中数据不完整（半包，且在超时窗口内未收齐） */
    INCOMPLETE_FRAME,
    /** MBAP 事务号 / 单元号与请求不匹配（乱序、串号） */
    TRANSACTION_MISMATCH,
    /** 等待响应超时 */
    TIMEOUT,
    /** 无法建立连接（拒绝、不可达） */
    CONNECT_FAILED,
    /** 已建立的连接被对端关闭或重置 */
    CONNECTION_LOST,
    /** Modbus 异常码 0x01 */
    MODBUS_ILLEGAL_FUNCTION,
    /** Modbus 异常码 0x02 */
    MODBUS_ILLEGAL_DATA_ADDRESS,
    /** Modbus 异常码 0x03 */
    MODBUS_ILLEGAL_DATA_VALUE,
    /** Modbus 异常码 0x04 */
    MODBUS_SERVER_DEVICE_FAILURE,
    /** 其他 Modbus 异常码 */
    MODBUS_EXCEPTION_OTHER
}
