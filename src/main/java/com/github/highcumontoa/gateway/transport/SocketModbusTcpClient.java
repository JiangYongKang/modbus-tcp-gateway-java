package com.github.highcumontoa.gateway.transport;

import com.github.highcumontoa.gateway.codec.FrameStreamDecoder;
import com.github.highcumontoa.gateway.codec.ModbusFrames;
import com.github.highcumontoa.gateway.codec.ModbusRequest;
import com.github.highcumontoa.gateway.codec.ModbusResponse;
import com.github.highcumontoa.gateway.config.GatewayProperties;
import com.github.highcumontoa.gateway.model.FailureReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public class SocketModbusTcpClient implements ModbusTcpClient {
    private static final Logger log = LoggerFactory.getLogger(SocketModbusTcpClient.class);

    private final GatewayProperties.Client properties;
    private final FrameStreamDecoder decoder;
    private final ModbusClientListener listener;
    private final ConcurrentHashMap<Integer, CompletableFuture<ModbusResponse>> pending =
            new ConcurrentHashMap<>();
    private final AtomicInteger transactionIds = new AtomicInteger(0);
    private final ReentrantLock writeLock = new ReentrantLock();

    private volatile Socket socket;
    private volatile OutputStream outputStream;
    private volatile Thread readerThread;
    private volatile boolean closed;

    public SocketModbusTcpClient(GatewayProperties.Client properties) {
        this(properties, new FrameStreamDecoder(), new ModbusClientListener() {
        });
    }

    public SocketModbusTcpClient(GatewayProperties.Client properties, FrameStreamDecoder decoder) {
        this(properties, decoder, new ModbusClientListener() {
        });
    }

    public SocketModbusTcpClient(GatewayProperties.Client properties, FrameStreamDecoder decoder,
                                 ModbusClientListener listener) {
        this.properties = properties;
        this.decoder = decoder;
        this.listener = listener;
    }

    @Override
    public synchronized void connect() {
        if (closed) {
            throw new ModbusTransportException(FailureReason.CONNECTION_FAILED,
                    "Client has already been closed");
        }
        if (isConnected()) {
            return;
        }

        Socket candidate = new Socket();
        try {
            candidate.setTcpNoDelay(true);
            candidate.setSoTimeout(0);
            candidate.connect(new InetSocketAddress(properties.getHost(), properties.getPort()),
                    properties.getConnectTimeoutMs());
        } catch (IOException e) {
            closeQuietly(candidate);
            throw new ModbusTransportException(FailureReason.CONNECTION_FAILED,
                    "Failed to connect to " + properties.getHost() + ':' + properties.getPort()
                            + ": " + e.getMessage(), e);
        }

        socket = candidate;
        try {
            outputStream = candidate.getOutputStream();
        } catch (IOException e) {
            closeSocketAfterFailure("Unable to obtain socket output stream: " + e.getMessage(), e);
            throw new ModbusTransportException(FailureReason.CONNECTION_FAILED,
                    "Unable to obtain socket output stream: " + e.getMessage(), e);
        }

        Thread thread = new Thread(this::readLoop, "modbus-tcp-reader");
        thread.setDaemon(true);
        thread.setUncaughtExceptionHandler((ignored, throwable) ->
                failAll(FailureReason.CONNECTION_CLOSED,
                        "Reader thread terminated: " + throwable.getMessage()));
        readerThread = thread;
        thread.start();
        log.info("Modbus TCP connection established: {}:{}", properties.getHost(), properties.getPort());
    }

    @Override
    public CompletableFuture<ModbusResponse> readRegisters(ModbusRequest request) {
        Socket currentSocket = socket;
        OutputStream currentOutput = outputStream;
        if (closed) {
            return failedFuture(new ModbusTransportException(FailureReason.CANCELLED, "Client is closed"));
        }
        if (currentSocket == null || currentSocket.isClosed() || !currentSocket.isConnected()
                || currentOutput == null) {
            return failedFuture(new ModbusTransportException(FailureReason.CONNECTION_CLOSED,
                    "Connection is not established"));
        }

        byte[] frame = ModbusFrames.encodeReadRequest(request);
        CompletableFuture<ModbusResponse> future = new CompletableFuture<>();
        pending.put(request.transactionId(), future);
        try {
            writeLock.lock();
            try {
                currentOutput.write(frame);
                currentOutput.flush();
            } finally {
                writeLock.unlock();
            }
            log.debug("Modbus request txn={} unit={} fc={} raw={}", request.transactionId(),
                    request.unitId(), request.functionCode(), FrameStreamDecoder.toHex(frame));
        } catch (Exception e) {
            pending.remove(request.transactionId());
            FailureReason reason = e instanceof ModbusTransportException transport
                    ? transport.reason() : FailureReason.CONNECTION_CLOSED;
            String message = "Failed to write Modbus request: " + e.getMessage();
            closeSocketAfterFailure(message, e);
            return failedFuture(new ModbusTransportException(reason, message, e));
        }

        return future
                .orTimeout(properties.getResponseTimeoutMs(), TimeUnit.MILLISECONDS)
                .whenComplete((response, throwable) -> pending.remove(request.transactionId()))
                .exceptionallyCompose(throwable -> {
                    Throwable cause = unwrap(throwable);
                    if (cause instanceof TimeoutException) {
                        return CompletableFuture.failedFuture(new ModbusTimeoutException(
                                "Timed out after " + properties.getResponseTimeoutMs()
                                        + " ms waiting for txn=" + request.transactionId()));
                    }
                    if (cause instanceof ModbusTransportException transport) {
                        return CompletableFuture.failedFuture(transport);
                    }
                    return CompletableFuture.failedFuture(throwable);
                });
    }

    @Override
    public boolean isConnected() {
        Socket current = socket;
        Thread currentReader = readerThread;
        return !closed && current != null && current.isConnected() && !current.isClosed()
                && outputStream != null;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        failAll(FailureReason.CANCELLED, "Modbus client is closing");
        closeQuietly(socket);
        socket = null;
        outputStream = null;
        Thread currentReader = readerThread;
        if (currentReader != null) {
            currentReader.interrupt();
        }
        readerThread = null;
    }

    private void readLoop() {
        Socket currentSocket = socket;
        FailureReason failureReason = FailureReason.CONNECTION_CLOSED;
        String failureMessage = "Connection closed by remote peer";
        try {
            while (!closed && !currentSocket.isClosed()) {
                ModbusResponse response = decoder.readFrame(currentSocket.getInputStream());
                if (response == null) {
                    break;
                }
                dispatch(response);
            }
        } catch (ModbusTransportException e) {
            failureReason = e.reason();
            failureMessage = e.getMessage();
            listener.onInvalidFrame(e.reason(), e.getMessage(), "");
            log.warn("Modbus frame/transport failure: {} reason={}", e.getMessage(), failureReason);
        } catch (IOException e) {
            failureMessage = "I/O failure in reader loop: " + e.getMessage();
            log.warn(failureMessage, e);
        } finally {
            failAll(failureReason, failureMessage);
            synchronized (this) {
                if (socket == currentSocket) {
                    closeQuietly(currentSocket);
                    socket = null;
                    outputStream = null;
                    readerThread = null;
                }
            }
        }
    }

    private void dispatch(ModbusResponse response) {
        CompletableFuture<ModbusResponse> future = pending.remove(response.transactionId());
        if (future == null) {
            String rawHex = FrameStreamDecoder.toHex(response.rawFrame());
            listener.onUnmatchedResponse(response, rawHex);
            log.warn("Discarding unmatched Modbus response txn={} unit={} fc={} raw={}",
                    response.transactionId(), response.unitId(), response.functionCode(), rawHex);
            return;
        }
        log.debug("Modbus response txn={} unit={} fc={} exception={} raw={}",
                response.transactionId(), response.unitId(), response.functionCode(),
                response.exception(), FrameStreamDecoder.toHex(response.rawFrame()));
        future.complete(response);
    }

    private void failAll(FailureReason reason, String message) {
        ModbusTransportException error = new ModbusTransportException(reason, message);
        for (Map.Entry<Integer, CompletableFuture<ModbusResponse>> entry : pending.entrySet()) {
            if (pending.remove(entry.getKey()) != null) {
                entry.getValue().completeExceptionally(error);
            }
        }
    }

    private void closeSocketAfterFailure(String message, Throwable cause) {
        Socket current = socket;
        failAll(FailureReason.CONNECTION_CLOSED, message);
        closeQuietly(current);
        synchronized (this) {
            if (socket == current) {
                socket = null;
                outputStream = null;
                if (readerThread != null) {
                    readerThread.interrupt();
                }
                readerThread = null;
            }
        }
        if (cause == null) {
            log.warn(message);
        } else {
            log.warn(message, cause);
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current != null && current.getCause() != null
                && (current instanceof CompletionException || current instanceof ExecutionException)) {
            current = current.getCause();
        }
        return current;
    }

    private static <T> CompletableFuture<T> failedFuture(Throwable throwable) {
        return CompletableFuture.failedFuture(throwable);
    }

    private static void closeQuietly(Socket candidate) {
        if (candidate != null) {
            try {
                candidate.close();
            } catch (IOException ignored) {
            }
        }
    }
}
