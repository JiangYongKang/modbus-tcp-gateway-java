package com.github.highcumontoa.gateway.runtime;

import com.github.highcumontoa.gateway.config.GatewayProperties;
import com.github.highcumontoa.gateway.model.DataType;
import com.github.highcumontoa.gateway.model.FailureReason;
import com.github.highcumontoa.gateway.model.PointQuality;
import com.github.highcumontoa.gateway.model.ReadingSnapshot;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestMethodOrder(MethodOrderer.MethodName.class)
class AcquisitionServiceTest {
    private static final Logger log = LoggerFactory.getLogger(AcquisitionServiceTest.class);

    @Test
    void classifiesModbusExceptionAndDoesNotPublishValue() throws Exception {
        try (ScriptedServer server = new ScriptedServer((input, output, socket, connection) -> {
            byte[] request = readExact(input, 12);
            byte[] response = {
                    request[0], request[1], 0, 0, 0, 3, request[6],
                    (byte) 0x83, 0x02
            };
            output.write(response);
            output.flush();
            log.warn("ACQUISITION_FAILURE rawRequest={} rawResponse={} reason=MODBUS_EXCEPTION basis=function bit 0x80 and code 02",
                    hex(request), hex(response));
            Thread.sleep(200);
        })) {
            AcquisitionFixture fixture = fixture(server.port(), 500, 100);
            AcquisitionService service = fixture.service();
            service.start();
            try {
                await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                    ReadingSnapshot snapshot = fixture.store().snapshot("temp");
                    assertEquals(FailureReason.MODBUS_EXCEPTION, snapshot.failureReason());
                    assertEquals(PointQuality.BAD, snapshot.quality());
                    assertEquals(null, snapshot.value());
                    assertTrue(snapshot.pollCount() >= 1);
                });
            } finally {
                service.stop();
            }
        }
    }

    @Test
    void reconnectsAfterBrokenConnectionAndContinuesPolling() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        try (ScriptedServer server = new ScriptedServer((input, output, socket, connection) -> {
            int id = connections.incrementAndGet();
            if (id == 1) {
                readExact(input, 12);
                log.warn("ACQUISITION_FAILURE raw=<unavailable after request read> reason=CONNECTION_CLOSED basis=server closes first socket without response");
                ScriptedServer.closeQuietly(socket);
                return;
            }
            byte[] request = readExact(input, 12);
            byte[] response = readResponse(request, 0x04, (byte) 0xD2);
            output.write(response);
            output.flush();
            log.info("ACQUISITION_RECOVERY connection={} rawResponse={} basis=valid response after reconnect",
                    id, hex(response));
        })) {
            AcquisitionFixture fixture = fixture(server.port(), 500, 50);
            AcquisitionService service = fixture.service();
            service.start();
            try {
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                    ReadingSnapshot snapshot = fixture.store().snapshot("temp");
                    assertEquals(PointQuality.GOOD, snapshot.quality());
                    assertEquals(123.4d, snapshot.value());
                    assertTrue(fixture.store().status().reconnects() >= 1);
                    assertTrue(snapshot.pollCount() >= 4);
                });
            } finally {
                service.stop();
            }
        }
    }

    private static AcquisitionFixture fixture(int port, int responseTimeoutMs, int reconnectDelayMs) {
        GatewayProperties properties = new GatewayProperties();
        properties.getClient().setHost("127.0.0.1");
        properties.getClient().setPort(port);
        properties.getClient().setConnectTimeoutMs(500);
        properties.getClient().setResponseTimeoutMs(responseTimeoutMs);
        properties.getClient().setPollIntervalMs(20);
        properties.getClient().setReconnectDelayMs(reconnectDelayMs);
        properties.getClient().setStaleThresholdMs(500);
        GatewayProperties.PointDefinition point = new GatewayProperties.PointDefinition();
        point.setName("temp");
        point.setDataType(DataType.INT16);
        point.setScale(0.1d);
        point.setAddress(0);
        properties.setPoints(List.of(point));
        ReadingStore store = new ReadingStore(properties, Clock.systemUTC());
        return new AcquisitionFixture(new AcquisitionService(properties, store), store);
    }

    record AcquisitionFixture(AcquisitionService service, ReadingStore store) {
    }

    private static byte[] readResponse(byte[] request, int high, int low) {
        return new byte[]{
                request[0], request[1], 0, 0, 0, 5, request[6], 3, 2,
                (byte) high, (byte) low
        };
    }

    private static byte[] readExact(InputStream input, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(data, offset, length - offset);
            if (count < 0) {
                throw new IOException("EOF after " + offset);
            }
            offset += count;
        }
        return data;
    }

    private static String hex(byte[] bytes) {
        return com.github.highcumontoa.gateway.codec.FrameStreamDecoder.toHex(bytes);
    }

    @FunctionalInterface
    interface ScriptedHandler {
        void handle(InputStream input, OutputStream output, Socket socket, int connection) throws Exception;
    }

    static final class ScriptedServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final Thread acceptThread;
        private final AtomicInteger connections = new AtomicInteger();
        private volatile boolean running = true;

        ScriptedServer(ScriptedHandler handler) throws IOException {
            serverSocket = new ServerSocket(0);
            acceptThread = new Thread(() -> {
                while (running) {
                    Socket socket;
                    try {
                        socket = serverSocket.accept();
                    } catch (IOException e) {
                        if (running) {
                            log.debug("Scripted server stopped accepting: {}", e.getMessage());
                        }
                        return;
                    }
                    int connection = connections.incrementAndGet();
                    Thread worker = new Thread(() -> {
                        try {
                            InputStream input = socket.getInputStream();
                            OutputStream output = socket.getOutputStream();
                            while (running && !socket.isClosed()) {
                                handler.handle(input, output, socket, connection);
                            }
                        } catch (Exception e) {
                            if (running) {
                                log.debug("Scripted server connection ended: {}", e.getMessage());
                            }
                        } finally {
                            closeQuietly(socket);
                        }
                    }, "scripted-modbus-connection-" + connection);
                    worker.setDaemon(true);
                    worker.start();
                }
            }, "scripted-modbus-server");
            acceptThread.setDaemon(true);
            acceptThread.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        private static void closeQuietly(Socket socket) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }

        @Override
        public void close() throws Exception {
            running = false;
            serverSocket.close();
            acceptThread.join(500);
        }
    }
}
