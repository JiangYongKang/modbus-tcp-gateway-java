package com.github.highcumontoa.gateway.simulator;

public enum FaultType {
    LATENCY,
    TIMEOUT,
    MODBUS_EXCEPTION,
    CONNECTION_DROP,
    FRAGMENT,
    COALESCE
}
