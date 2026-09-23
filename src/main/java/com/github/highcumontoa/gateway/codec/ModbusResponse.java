package com.github.highcumontoa.gateway.codec;

public record ModbusResponse(
        int transactionId,
        int unitId,
        int functionCode,
        int[] registers,
        byte[] rawFrame,
        Integer exceptionCode) {

    public boolean exception() {
        return exceptionCode != null;
    }
}
