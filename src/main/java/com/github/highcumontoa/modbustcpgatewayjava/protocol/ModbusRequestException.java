package com.github.highcumontoa.modbustcpgatewayjava.protocol;

import com.github.highcumontoa.modbustcpgatewayjava.model.FailReason;

/**
 * 一次 Modbus 请求从建连到收包全过程中的失败，携带可区分的失败原因。
 */
public class ModbusRequestException extends Exception {

    private final FailReason reason;

    public ModbusRequestException(FailReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public ModbusRequestException(FailReason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public FailReason reason() {
        return reason;
    }
}
