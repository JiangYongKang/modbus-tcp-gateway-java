package com.github.highcumontoa.gateway.transport;

import com.github.highcumontoa.gateway.codec.ModbusResponse;
import com.github.highcumontoa.gateway.model.FailureReason;

public interface ModbusClientListener {
    default void onUnmatchedResponse(ModbusResponse response, String rawHex) {
    }

    default void onInvalidFrame(FailureReason reason, String message, String rawHex) {
    }
}
