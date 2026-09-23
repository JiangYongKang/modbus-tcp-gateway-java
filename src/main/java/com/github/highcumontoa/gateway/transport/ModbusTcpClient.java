package com.github.highcumontoa.gateway.transport;

import com.github.highcumontoa.gateway.codec.ModbusRequest;
import com.github.highcumontoa.gateway.codec.ModbusResponse;

import java.util.concurrent.CompletableFuture;

public interface ModbusTcpClient {
    void connect();
    CompletableFuture<ModbusResponse> readRegisters(ModbusRequest request);
    boolean isConnected();
    void close();
}
