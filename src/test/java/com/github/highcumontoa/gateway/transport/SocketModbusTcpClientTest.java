package com.github.highcumontoa.gateway.transport;

import com.github.highcumontoa.gateway.codec.FrameStreamDecoder;
import com.github.highcumontoa.gateway.codec.ModbusRequest;
import com.github.highcumontoa.gateway.codec.ModbusResponse;
import com.github.highcumontoa.gateway.config.GatewayProperties;
import com.github.highcumontoa.gateway.model.FailureReason;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SocketModbusTcpClientTest {
    private static final Logger log = LoggerFactory.getLogger(SocketModbusTcpClientTest.class);

    @Test
    void readsResponseSplitIntoSingleByteFragments() throws Exception {
        try (TestServer server = new TestServer((input, output) -> {
            byte[] request = readExact(input, 12);
            byte[] response = responseFor(request, 0x00, 0x01, 0x02, 0x03);
            writeFragments(output, response, 1);
        })) {
            SocketModbusTcpClient client = server.client(1_000);
            client.connect();
            ModbusResponse response = client.readRegisters(request(1, 0, 2)).get(2, TimeUnit.SECONDS);

            log.info("TRANSPORT_TEST case=fragmented txn={} raw={} basis=one-byte TCP segments still form one MBAP frame",
                    response.transactionId(), FrameStreamDecoder.toHex(response.rawFrame()));
            assertArrayEquals(new int[]{0x0001, 0x0203}, response.registers());
            client.close();
        }
    }

    @Test
    void demultiplexesStickyOutOfOrderResponses() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (TestServer server = new TestServer((input, output) -> {
            byte[] firstRequest = readExact(input, 12);
            byte[] secondRequest = readExact(input, 12);
            byte[] first = responseFor(firstRequest, 0x00, 0x00, 0x00, 0x11);
            byte[] second = responseFor(secondRequest, 0x00, 0x00, 0x00, 0x22);
            output.write(second);
            output.write(first);
            output.flush();
        })) {
            SocketModbusTcpClient client = server.client(1_000);
            client.connect();
            Future<CompletableFuture<ModbusResponse>> firstFuture =
                    executor.submit(() -> client.readRegisters(request(11, 0, 2)));
            Future<CompletableFuture<ModbusResponse>> secondFuture =
                    executor.submit(() -> client.readRegisters(request(22, 0, 2)));
            CompletableFuture<ModbusResponse> first = firstFuture.get();
            CompletableFuture<ModbusResponse> second = secondFuture.get();
            ModbusResponse firstResponse = first.get(2, TimeUnit.SECONDS);
            ModbusResponse secondResponse = second.get(2, TimeUnit.SECONDS);

            log.info("TRANSPORT_TEST case=concurrent-out-of-order firstTxn={} firstValue={} secondTxn={} secondValue={} basis=write lock serializes requests; pending map matches transaction id",
                    firstResponse.transactionId(), firstResponse.registers()[1],
                    secondResponse.transactionId(), secondResponse.registers()[1]);
            assertEquals(11, firstResponse.transactionId());
            assertEquals(0x11, firstResponse.registers()[1]);
            assertEquals(22, secondResponse.transactionId());
            assertEquals(0x22, secondResponse.registers()[1]);
            executor.shutdownNow();
            client.close();
        }
    }

    @Test
    void reportsUnmatchedResponseWithoutCompletingAnotherRead() throws Exception {
        AtomicInteger unmatched = new AtomicInteger();
        ModbusClientListener listener = new ModbusClientListener() {
            @Override
            public void onUnmatchedResponse(ModbusResponse response, String rawHex) {
                unmatched.incrementAndGet();
                log.warn("TRANSPORT_FAILURE raw={} reason=UNMATCHED basis=transaction id has no pending request",
                        rawHex);
            }
        };
        try (TestServer server = new TestServer((input, output) -> {
            byte[] request = readExact(input, 12);
            output.write(responseFor(request, 0, 0, 0, 0x99).clone());
            byte[] duplicate = responseFor(request, 0, 0, 0, 0x88).clone();
            duplicate[0] = (byte) 0x77;
            duplicate[1] = (byte) 0x77;
            output.write(duplicate);
            output.flush();
        })) {
            SocketModbusTcpClient client = server.client(1_000, listener);
            client.connect();
            ModbusResponse response = client.readRegisters(request(1, 0, 2)).get(2, TimeUnit.SECONDS);
            assertEquals(0x99, response.registers()[1]);
            Thread.sleep(100);
            assertEquals(1, unmatched.get());
            client.close();
        }
    }

    @Test
    void classifiesResponseTimeout() throws Exception {
        try (TestServer server = new TestServer((input, output) -> {
            byte[] request = readExact(input, 12);
            log.warn("TRANSPORT_FAILURE case=timeout request={} basis=server never sends response",
                    FrameStreamDecoder.toHex(request));
            Thread.sleep(500);
        })) {
            SocketModbusTcpClient client = server.client(50);
            client.connect();
            CompletionResult result = captureFailure(
                    () -> client.readRegisters(request(1, 0, 1)).get(2, TimeUnit.SECONDS));

            assertEquals(FailureReason.TIMEOUT, result.error.reason());
            client.close();
        }
    }

    @Test
    void reconnectsAfterRemoteClosesConnection() throws Exception {
        try (TestServer server = new TestServer((input, output) -> {
            byte[] request = readExact(input, 12);
            byte[] response = responseFor(request, 0x00, 0x00, 0x00, 0x77);
            output.write(response);
            output.flush();
        })) {
            SocketModbusTcpClient first = server.client(500);
            first.connect();
            assertEquals(0x77, first.readRegisters(request(1, 0, 1))
                    .get(2, TimeUnit.SECONDS).registers()[1]);
            CompletionResult closed = captureFailure(() -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                first.readRegisters(request(2, 0, 1)).get(2, TimeUnit.SECONDS);
            });
            first.close();
            assertEquals(FailureReason.CONNECTION_CLOSED, closed.error.reason());

            SocketModbusTcpClient second = server.client(500);
            second.connect();
            ModbusResponse response = second.readRegisters(request(3, 0, 1)).get(2, TimeUnit.SECONDS);
            log.info("TRANSPORT_TEST case=reconnect newTxn={} raw={} basis=server accepted a fresh socket after close",
                    response.transactionId(), FrameStreamDecoder.toHex(response.rawFrame()));
            assertEquals(0x77, response.registers()[1]);
            second.close();
        }
    }

    private static CompletionResult captureFailure(ThrowingOperation operation) {
        try {
            operation.run();
            throw new AssertionError("Expected failure");
        } catch (Exception e) {
            Throwable candidate = e;
            while (candidate != null && !(candidate instanceof ModbusTransportException)) {
                candidate = candidate.getCause();
            }
            assertTrue(candidate instanceof ModbusTransportException);
            return new CompletionResult((ModbusTransportException) candidate);
        }
    }

    private static ModbusRequest request(int txn, int address, int quantity) {
        return new ModbusRequest(txn, 1, 3, address, quantity);
    }

    private static byte[] responseFor(byte[] request, int... registerBytes) {
        int txn = ((request[0] & 0xff) << 8) | (request[1] & 0xff);
        byte[] response = new byte[9 + registerBytes.length];
        response[0] = request[0];
        response[1] = request[1];
        response[4] = 0x00;
        response[5] = (byte) (3 + registerBytes.length);
        response[6] = request[6];
        response[7] = 3;
        response[8] = (byte) registerBytes.length;
        for (int i = 0; i < registerBytes.length; i++) {
            response[9 + i] = (byte) registerBytes[i];
        }
        return response;
    }

    private static byte[] readExact(InputStream input, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(data, offset, length - offset);
            if (count < 0) {
                throw new IOException("EOF after " + offset + " bytes");
            }
            offset += count;
        }
        return data;
    }

    private static void writeFragments(OutputStream output, byte[] data, int size) throws IOException, InterruptedException {
        for (int offset = 0; offset < data.length; offset += size) {
            int end = Math.min(data.length, offset + size);
            output.write(data, offset, end - offset);
            output.flush();
            Thread.sleep(5);
        }
    }

    @FunctionalInterface
    interface ThrowingOperation {
        void run() throws Exception;
    }

    record CompletionResult(ModbusTransportException error) {
    }

    @FunctionalInterface
    interface PeerHandler {
        void handle(InputStream input, OutputStream output) throws Exception;
    }

    static final class TestServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final ExecutorService acceptor = Executors.newSingleThreadExecutor();
        private final List<Socket> accepted = new ArrayList<>();

        TestServer(PeerHandler handler) throws IOException {
            serverSocket = new ServerSocket(0);
            acceptor.submit(() -> {
                while (!serverSocket.isClosed()) {
                    try {
                        Socket socket = serverSocket.accept();
                        synchronized (accepted) {
                            accepted.add(socket);
                        }
                        Thread worker = new Thread(() -> {
                            try (Socket current = socket) {
                                handler.handle(current.getInputStream(), current.getOutputStream());
                            } catch (Exception e) {
                                log.debug("Test peer handler ended: {}", e.getMessage(), e);
                            }
                        });
                        worker.setDaemon(true);
                        worker.start();
                    } catch (IOException ignored) {
                        return;
                    }
                }
            });
        }

        SocketModbusTcpClient client(int timeoutMs) {
            return client(timeoutMs, new ModbusClientListener() {
            });
        }

        SocketModbusTcpClient client(int timeoutMs, ModbusClientListener listener) {
            GatewayProperties.Client properties = new GatewayProperties.Client();
            properties.setHost("127.0.0.1");
            properties.setPort(serverSocket.getLocalPort());
            properties.setConnectTimeoutMs(500);
            properties.setResponseTimeoutMs(timeoutMs);
            return new SocketModbusTcpClient(properties, new FrameStreamDecoder(), listener);
        }

        @Override
        public void close() throws Exception {
            acceptor.shutdownNow();
            serverSocket.close();
            synchronized (accepted) {
                for (Socket socket : accepted) {
                    socket.close();
                }
            }
        }
    }
}
