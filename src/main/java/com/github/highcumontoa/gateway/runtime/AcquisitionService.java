package com.github.highcumontoa.gateway.runtime;

import com.github.highcumontoa.gateway.codec.FrameStreamDecoder;
import com.github.highcumontoa.gateway.codec.ModbusRequest;
import com.github.highcumontoa.gateway.codec.ModbusResponse;
import com.github.highcumontoa.gateway.config.GatewayProperties;
import com.github.highcumontoa.gateway.config.PointDefinitionValidator;
import com.github.highcumontoa.gateway.decode.PointDecodeException;
import com.github.highcumontoa.gateway.decode.PointDecoder;
import com.github.highcumontoa.gateway.model.FailureReason;
import com.github.highcumontoa.gateway.transport.ModbusTimeoutException;
import com.github.highcumontoa.gateway.transport.ModbusClientListener;
import com.github.highcumontoa.gateway.transport.ModbusTransportException;
import com.github.highcumontoa.gateway.transport.SocketModbusTcpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class AcquisitionService implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(AcquisitionService.class);

    private final GatewayProperties properties;
    private final ReadingStore store;
    private final AtomicInteger transactionIds = new AtomicInteger(0);
    private final ModbusClientListener clientListener = new ModbusClientListener() {
        @Override
        public void onUnmatchedResponse(com.github.highcumontoa.gateway.codec.ModbusResponse response,
                                        String rawHex) {
            store.recordUnmatchedFrame();
            log.warn("Unmatched response will not become a reading txn={} unit={} fc={} raw={}",
                    response.transactionId(), response.unitId(), response.functionCode(), rawHex);
        }

        @Override
        public void onInvalidFrame(FailureReason reason, String message, String rawHex) {
            store.recordFailure(reason, message, rawHex);
        }
    };
    private volatile SocketModbusTcpClient client;
    private volatile Thread workerThread;
    private volatile boolean running;

    public AcquisitionService(GatewayProperties properties, ReadingStore store) {
        this.properties = properties;
        this.store = store;
        PointDefinitionValidator.validate(properties.getPoints());
        store.initialize(properties.getPoints());
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        client = new SocketModbusTcpClient(properties.getClient(), new FrameStreamDecoder(), clientListener);
        running = true;
        Thread thread = new Thread(this::runLoop, "modbus-acquisition-worker");
        thread.setDaemon(true);
        workerThread = thread;
        thread.start();
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        SocketModbusTcpClient current = client;
        if (current != null) {
            current.close();
        }
        Thread currentThread = workerThread;
        if (currentThread != null) {
            currentThread.interrupt();
            try {
                currentThread.join(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        workerThread = null;
        client = null;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return 20;
    }

    private void runLoop() {
        while (running) {
            long cycle;
            synchronized (this) {
                store.beginCycle();
                cycle = store.status().cycle();
            }

            if (!ensureConnected(cycle)) {
                sleep(properties.getClient().getReconnectDelayMs());
                continue;
            }
            pollAllPoints(cycle);
            sleep(properties.getClient().getPollIntervalMs());
        }
    }

    private boolean ensureConnected(long cycle) {
        SocketModbusTcpClient current = client;
        if (current != null && current.isConnected()) {
            return true;
        }
        SocketModbusTcpClient candidate = new SocketModbusTcpClient(
                properties.getClient(), new FrameStreamDecoder(), clientListener);
        store.recordConnectionAttempt("CONNECTING");
        try {
            candidate.connect();
            client = candidate;
            store.recordConnected();
            return true;
        } catch (ModbusTransportException e) {
            candidate.close();
            store.recordDisconnected("DISCONNECTED", e.getMessage());
            failAllPoints(cycle, e.reason(), e.getMessage(), "");
            return false;
        }
    }

    private void pollAllPoints(long cycle) {
        for (GatewayProperties.PointDefinition point : properties.getPoints()) {
            if (!running) {
                return;
            }
            pollOnePoint(point, cycle);
        }
    }

    private void pollOnePoint(GatewayProperties.PointDefinition point, long cycle) {
        int transactionId = nextTransactionId();
        int quantity = PointDecoder.requiredRegisters(point.getDataType());
        ModbusRequest request = new ModbusRequest(transactionId, point.getUnitId(),
                point.getRegisterType().functionCode(), point.getAddress(), quantity);
        String rawHex = "";
        try {
            CompletableFuture<ModbusResponse> future = client.readRegisters(request);
            ModbusResponse response = future.get();
            rawHex = FrameStreamDecoder.toHex(response.rawFrame());
            validateResponse(point, request, response);
            double value = PointDecoder.decode(point, response.registers());
            store.recordSuccess(point.getName(), value, rawHex, cycle);
            log.info("POINT_OK name={} txn={} fc={} raw={} decoded={} scale={} offset={} final={}",
                    point.getName(), transactionId, request.functionCode(), rawHex,
                    describeRaw(response.registers()), point.getScale(), point.getOffset(), value);
        } catch (ExecutionException | CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            FailureReason reason = reasonOf(cause);
            String message = cause.getMessage() == null ? reason.name() : cause.getMessage();
            if (cause instanceof ModbusTimeoutException) {
                reason = FailureReason.TIMEOUT;
            }
            store.recordFailure(point.getName(), reason, message, rawHex, cycle);
            log.warn("POINT_BAD name={} txn={} reason={} raw={} basis={}", point.getName(),
                    transactionId, reason, rawHex, message);
            if (reason == FailureReason.CONNECTION_CLOSED || reason == FailureReason.CONNECTION_FAILED
                    || reason == FailureReason.TIMEOUT) {
                markConnectionClosed(message);
            }
        } catch (ModbusTransportException | PointDecodeException e) {
            store.recordFailure(point.getName(), e instanceof PointDecodeException decode
                    ? decode.reason() : ((ModbusTransportException) e).reason(), e.getMessage(), rawHex, cycle);
            log.warn("POINT_BAD name={} txn={} reason={} raw={} basis={}", point.getName(),
                    transactionId, e instanceof PointDecodeException decode ? decode.reason()
                            : ((ModbusTransportException) e).reason(), rawHex, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            store.recordFailure(point.getName(), FailureReason.CANCELLED,
                    "Acquisition interrupted", rawHex, cycle);
        }
    }

    private void validateResponse(GatewayProperties.PointDefinition point, ModbusRequest request,
                                  ModbusResponse response) {
        if (response.exception()) {
            throw new ModbusTransportException(FailureReason.MODBUS_EXCEPTION,
                    "Device returned Modbus exception code " + response.exceptionCode()
                            + " for function " + (request.functionCode() & 0xff));
        }
        if (response.transactionId() != request.transactionId()) {
            throw new ModbusTransportException(FailureReason.TRANSACTION_MISMATCH,
                    "Expected transaction id " + request.transactionId() + ", received "
                            + response.transactionId());
        }
        if (response.unitId() != request.unitId()) {
            throw new ModbusTransportException(FailureReason.UNIT_MISMATCH,
                    "Expected unit id " + request.unitId() + ", received " + response.unitId());
        }
        if (response.functionCode() != request.functionCode()) {
            throw new ModbusTransportException(FailureReason.FUNCTION_MISMATCH,
                    "Expected function code " + request.functionCode() + ", received "
                            + response.functionCode());
        }
        int expected = PointDecoder.requiredRegisters(point.getDataType());
        if (response.registers().length != expected) {
            throw new ModbusTransportException(FailureReason.MALFORMED_RESPONSE,
                    "Point '" + point.getName() + "' expects " + expected + " registers, received "
                            + response.registers().length);
        }
    }

    private void failAllPoints(long cycle, FailureReason reason, String message, String rawHex) {
        for (GatewayProperties.PointDefinition point : properties.getPoints()) {
            store.recordFailure(point.getName(), reason, message, rawHex, cycle);
        }
    }

    private void markConnectionClosed(String message) {
        SocketModbusTcpClient current = client;
        if (current != null) {
            current.close();
        }
        store.recordDisconnected("DISCONNECTED", message);
    }

    private int nextTransactionId() {
        return transactionIds.updateAndGet(current -> current == 0xffff ? 1 : current + 1);
    }

    private FailureReason reasonOf(Throwable throwable) {
        if (throwable instanceof ModbusTransportException transport) {
            return transport.reason();
        }
        return FailureReason.UNKNOWN;
    }

    private String describeRaw(int[] registers) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < registers.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(String.format("0x%04X", registers[i] & 0xffff));
        }
        return builder.append(']').toString();
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(Math.max(0, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
