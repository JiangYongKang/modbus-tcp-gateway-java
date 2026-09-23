package com.github.highcumontoa.gateway.codec;

public record ModbusRequest(
        int transactionId,
        int unitId,
        int functionCode,
        int startAddress,
        int quantity) {
}
