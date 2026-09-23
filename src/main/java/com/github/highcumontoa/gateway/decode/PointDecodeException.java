package com.github.highcumontoa.gateway.decode;

import com.github.highcumontoa.gateway.model.FailureReason;

public class PointDecodeException extends RuntimeException {
    private final FailureReason reason;

    public PointDecodeException(FailureReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public FailureReason reason() {
        return reason;
    }
}
