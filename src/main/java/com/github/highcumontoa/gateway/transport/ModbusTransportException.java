package com.github.highcumontoa.gateway.transport;

import com.github.highcumontoa.gateway.model.FailureReason;

public class ModbusTransportException extends RuntimeException {
    private final FailureReason reason;

    public ModbusTransportException(FailureReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public ModbusTransportException(FailureReason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public FailureReason reason() {
        return reason;
    }
}
