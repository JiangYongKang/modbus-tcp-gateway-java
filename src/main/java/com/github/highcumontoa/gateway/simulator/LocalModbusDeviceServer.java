package com.github.highcumontoa.gateway.simulator;

import com.github.highcumontoa.gateway.config.GatewayProperties;
import com.github.highcumontoa.gateway.fault.FaultRegistry;
import com.github.highcumontoa.gateway.fault.FaultSpec;
import com.github.highcumontoa.gateway.model.RegisterType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Component
public class LocalModbusDeviceServer implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(LocalModbusDeviceServer.class);
    private static final int REGISTER_BANK_SIZE = 64;

    private final GatewayProperties properties;
    private final FaultRegistry faultRegistry;
    private final Object registerLock = new Object();
    private final int[] holdingRegisters = initialHoldingRegisters();
    private final int[] inputRegisters = initialInputRegisters();
    private final ExecutorService connectionWorkers = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "local-modbus-device-connection");
        thread.setDaemon(true);
        return thread;
    });
    private volatile ServerSocket serverSocket;
    private volatile Thread acceptThread;
    private volatile boolean running;

    public LocalModbusDeviceServer(GatewayProperties properties, FaultRegistry faultRegistry) {
        this.properties = properties;
        this.faultRegistry = faultRegistry;
    }

    @Override
    public synchronized void start() {
        if (running || !properties.getLocalSource().isEnabled()) {
            return;
        }
        try {
            ServerSocket socket = new ServerSocket(properties.getLocalSource().getPort(),
                    properties.getLocalSource().getAcceptBacklog(),
                    java.net.InetAddress.getByName(properties.getLocalSource().getHost()));
            serverSocket = socket;
            running = true;
            Thread thread = new Thread(this::acceptLoop, "local-modbus-device-accept");
            thread.setDaemon(true);
            acceptThread = thread;
            thread.start();
            log.info("Local deterministic Modbus TCP device listening on {}:{}",
                    properties.getLocalSource().getHost(), properties.getLocalSource().getPort());
        } catch (IOException e) {
            running = false;
            throw new IllegalStateException("Failed to start local Modbus device on "
                    + properties.getLocalSource().getHost() + ':'
                    + properties.getLocalSource().getPort(), e);
        }
    }

    @Override
    public void stop() {
        running = false;
        ServerSocket current = serverSocket;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
            }
        }
        connectionWorkers.shutdownNow();
        try {
            connectionWorkers.awaitTermination(500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        serverSocket = null;
        acceptThread = null;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return 10;
    }

    private void acceptLoop() {
        ServerSocket current = serverSocket;
        while (running && current != null) {
            try {
                Socket socket = current.accept();
                socket.setTcpNoDelay(true);
                connectionWorkers.submit(() -> serveConnection(socket));
            } catch (SocketException e) {
                if (running) {
                    log.warn("Local device accept socket failed: {}", e.getMessage());
                }
            } catch (IOException e) {
                if (running) {
                    log.warn("Failed to accept local device connection", e);
                }
            }
        }
    }

    private void serveConnection(Socket socket) {
        try (Socket current = socket) {
            InputStream input = current.getInputStream();
            OutputStream output = current.getOutputStream();
            while (!current.isClosed()) {
                handleOneRequest(input, output);
            }
        } catch (SocketException e) {
            log.debug("Local device connection closed: {}", e.getMessage());
        } catch (IOException e) {
            log.debug("Local device connection ended: {}", e.getMessage());
        }
    }

    private void handleOneRequest(InputStream input, OutputStream output) throws IOException {
        byte[] header = new byte[7];
        if (!readFully(input, header)) {
            throw new SocketException("peer closed during MBAP header");
        }
        int protocolId = unsignedShort(header, 2);
        int length = unsignedShort(header, 4);
        if (protocolId != 0 || length != 6) {
            throw new IOException("Invalid MBAP header, protocolId=" + protocolId + ", length=" + length);
        }
        byte[] pdu = new byte[5];
        if (!readFully(input, pdu)) {
            throw new SocketException("peer closed during PDU");
        }

        int transactionId = unsignedShort(header, 0);
        int unitId = header[6] & 0xff;
        int functionCode = pdu[0] & 0xff;
        int startAddress = unsignedShort(pdu, 1);
        int quantity = unsignedShort(pdu, 3);
        if (functionCode != 3 && functionCode != 4) {
            sendException(output, transactionId, unitId, functionCode, 0x01);
            return;
        }
        if (quantity < 1 || quantity > 125) {
            sendException(output, transactionId, unitId, functionCode, 0x03);
            return;
        }
        int[] bank = functionCode == 3 ? holdingRegisters : inputRegisters;
        if (startAddress >= bank.length || startAddress + quantity > bank.length) {
            sendException(output, transactionId, unitId, functionCode, 0x02);
            return;
        }

        RegisterType registerType = functionCode == 3 ? RegisterType.HOLDING : RegisterType.INPUT;
        var fault = faultRegistry.claim(registerType, startAddress, quantity);
        if (fault.isPresent()) {
            applyFault(fault.get(), output, transactionId, unitId, functionCode,
                    buildResponseLocked(transactionId, unitId, functionCode, bank, startAddress, quantity));
            return;
        }

        byte[] response = buildResponseLocked(transactionId, unitId, functionCode, bank, startAddress, quantity);
        synchronized (output) {
            output.write(response);
            output.flush();
        }
    }

    private static byte[] buildResponse(int transactionId, int unitId, int functionCode,
                                        int[] bank, int startAddress, int quantity) {
        int pduLength = 2 + quantity * 2;
        byte[] response = new byte[7 + pduLength];
        writeUnsignedShort(response, 0, transactionId);
        writeUnsignedShort(response, 2, 0);
        writeUnsignedShort(response, 4, pduLength + 1);
        response[6] = (byte) unitId;
        response[7] = (byte) functionCode;
        response[8] = (byte) (quantity * 2);
        for (int i = 0; i < quantity; i++) {
            writeUnsignedShort(response, 9 + i * 2, bank[startAddress + i]);
        }
        return response;
    }

    private byte[] buildResponseLocked(int transactionId, int unitId, int functionCode,
                                       int[] bank, int startAddress, int quantity) {
        synchronized (registerLock) {
            return buildResponse(transactionId, unitId, functionCode, bank, startAddress, quantity);
        }
    }

    /**
     * 运行期改写一段寄存器。越界或值非法时抛出 IllegalArgumentException，不做部分写入。
     */
    public void writeRegisters(RegisterType registerType, int address, int[] values) {
        if (values == null || values.length == 0) {
            throw new IllegalArgumentException("values must not be empty");
        }
        int[] bank = bankOf(registerType);
        if (address < 0 || address + values.length > bank.length) {
            throw new IllegalArgumentException("Register range " + address + ".."
                    + (address + values.length - 1) + " exceeds bank size " + bank.length);
        }
        synchronized (registerLock) {
            for (int i = 0; i < values.length; i++) {
                int value = values[i];
                if (value < 0 || value > 0xFFFF) {
                    throw new IllegalArgumentException("Register value out of range: " + value);
                }
                bank[address + i] = value;
            }
        }
        log.info("REGISTERS_OVERRIDDEN type={} address={} count={}", registerType, address, values.length);
    }

    /** 恢复出厂寄存器映像，撤掉所有运行期改写。 */
    public void resetRegisters() {
        synchronized (registerLock) {
            System.arraycopy(initialHoldingRegisters(), 0, holdingRegisters, 0, REGISTER_BANK_SIZE);
            System.arraycopy(initialInputRegisters(), 0, inputRegisters, 0, REGISTER_BANK_SIZE);
        }
        log.info("REGISTERS_RESET to factory image");
    }

    /** 读取当前寄存器映像的一段，用于核对运行期改写结果。 */
    public int[] readRegisters(RegisterType registerType, int address, int quantity) {
        int[] bank = bankOf(registerType);
        if (address < 0 || quantity < 1 || address + quantity > bank.length) {
            throw new IllegalArgumentException("Register range " + address + ".."
                    + (address + quantity - 1) + " exceeds bank size " + bank.length);
        }
        synchronized (registerLock) {
            return Arrays.copyOfRange(bank, address, address + quantity);
        }
    }

    public int bankSize() {
        return REGISTER_BANK_SIZE;
    }

    private int[] bankOf(RegisterType registerType) {
        return registerType == RegisterType.INPUT ? inputRegisters : holdingRegisters;
    }

    private void applyFault(FaultSpec fault, OutputStream output,
                            int transactionId, int unitId, int functionCode,
                            byte[] response) throws IOException {
        log.info("FAULT_INJECT id={} type={} txn={} fc={}", fault.id(), fault.type(),
                transactionId, functionCode);
        switch (fault.type()) {
            case RESPONSE_DELAY -> {
                sleepQuietly(fault.delayMs());
                synchronized (output) {
                    output.write(response);
                    output.flush();
                }
            }
            case TIMEOUT -> {
                // 吞掉请求，永不响应；客户端按超时处理。
            }
            case MODBUS_EXCEPTION -> sendException(output, transactionId, unitId, functionCode,
                    fault.exceptionCode() == 0 ? 0x02 : fault.exceptionCode());
            case CONNECTION_DROP -> throw new SocketException("Fault injected: connection dropped");
            case FRAGMENTED_RESPONSE -> {
                int chunkSize = fault.chunkSize() <= 0 ? 1 : fault.chunkSize();
                synchronized (output) {
                    for (int offset = 0; offset < response.length; offset += chunkSize) {
                        int length = Math.min(chunkSize, response.length - offset);
                        output.write(response, offset, length);
                        output.flush();
                        sleepQuietly(fault.chunkDelayMs());
                    }
                }
            }
            case COALESCED_RESPONSE -> {
                // 同一帧复制两份、一次 flush 发出，制造“几段粘在一起”的 TCP segment。
                synchronized (output) {
                    output.write(response);
                    output.write(response);
                    output.flush();
                }
            }
        }
    }

    private static void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void sendException(OutputStream output, int transactionId, int unitId,
                               int functionCode, int exceptionCode) throws IOException {
        byte[] response = new byte[9];
        writeUnsignedShort(response, 0, transactionId);
        writeUnsignedShort(response, 2, 0);
        writeUnsignedShort(response, 4, 3);
        response[6] = (byte) unitId;
        response[7] = (byte) (functionCode | 0x80);
        response[8] = (byte) exceptionCode;
        output.write(response);
        output.flush();
    }

    private boolean readFully(InputStream input, byte[] target) throws IOException {
        int offset = 0;
        while (offset < target.length) {
            int count = input.read(target, offset, target.length - offset);
            if (count < 0) {
                return offset == 0;
            }
            offset += count;
        }
        return true;
    }

    private static int unsignedShort(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }

    private static void writeUnsignedShort(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 8);
        target[offset + 1] = (byte) value;
    }

    private static int[] initialHoldingRegisters() {
        int[] registers = new int[REGISTER_BANK_SIZE];
        registers[0] = 0x04D2;       // 1234
        registers[1] = 0x1234;       // high word, signed 32-bit = 305419896
        registers[2] = 0x5678;       // low word
        registers[3] = 0x41CC;       // high float 25.5
        registers[4] = 0x0000;       // low float
        registers[5] = 0xFFF6;       // signed 16-bit = -10
        registers[6] = 0x1234;       // float? intentionally available raw
        registers[7] = 0x5678;
        registers[8] = 0xFFFF;       // signed 32-bit = -1
        registers[9] = 0xFFFF;
        registers[10] = 0x04D2;      // scale 0.1 => 123.4
        registers[11] = 0xA5A5;
        registers[12] = 0x5A5A;
        registers[13] = 0x0000;       // low word of 25.5
        registers[14] = 0x41CC;       // high word of 25.5
        registers[15] = 0x0000;       // reversed low word of 25.5
        registers[16] = 0xCC41;       // reversed high word of 25.5
        return registers;
    }

    private static int[] initialInputRegisters() {
        int[] registers = new int[REGISTER_BANK_SIZE];
        registers[0] = 0xA604;       // unsigned 16-bit = 42500
        registers[1] = 0x0001;
        registers[2] = 0x86A0;       // unsigned 32-bit = 100000
        Arrays.fill(registers, 3, REGISTER_BANK_SIZE, 0);
        return registers;
    }
}
