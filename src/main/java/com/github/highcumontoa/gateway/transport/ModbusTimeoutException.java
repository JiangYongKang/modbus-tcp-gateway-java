package com.github.highcumontoa.gateway.transport;

import com.github.highcumontoa.gateway.model.FailureReason;

public class ModbusTimeoutException extends ModbusTransportException {
    public ModbusTimeoutException(String message) {
        super(FailureReason.TIMEOUT, message);
    }
}
