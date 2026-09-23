package com.github.highcumontoa.gateway.model;

public enum RegisterType {
    HOLDING(3), INPUT(4);

    private final int functionCode;

    RegisterType(int functionCode) {
        this.functionCode = functionCode;
    }

    public int functionCode() {
        return functionCode;
    }
}
