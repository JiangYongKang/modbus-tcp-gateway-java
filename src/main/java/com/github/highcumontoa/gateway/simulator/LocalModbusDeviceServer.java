package com.github.highcumontoa.gateway.simulator;

import com.github.highcumontoa.gateway.config.GatewayProperties;
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
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Component
public class LocalModbusDeviceServer implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(LocalModbusDeviceServer.class);
    private static final int REGISTER_BANK_SIZE = 64;

    private final GatewayProperties properties;
    private final FaultOrchestrator orchestrator;
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

    public LocalModbusDeviceServer(GatewayProperties properties, FaultOrchestrator orchestrator) {
        this.properties = properties;
        this.orchestrator = orchestrator;
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

    public void setRegister(RegisterType registerType, int address, int value) {
        int[] bank = bankOf(registerType);
        if (address < 0 || address >= bank.length) {
            throw new IllegalArgumentException("Register address out of range: " + address);
        }
        if (value < 0 || value > 0xFFFF) {
            throw new IllegalArgumentException("Register value out of range: " + value);
        }
        synchronized (bank) {
            bank[address] = value;
        }
        log.warn("REGISTER_OVERRIDE registerType={} address={} value=0x{}", registerType, address,
                Integer.toHexString(value).toUpperCase());
    }

    public void resetRegisters() {
        synchronized (holdingRegisters) {
            System.arraycopy(initialHoldingRegisters(), 0, holdingRegisters, 0, holdingRegisters.length);
        }
        synchronized (inputRegisters) {
            System.arraycopy(initialInputRegisters(), 0, inputRegisters, 0, inputRegisters.length);
        }
        log.warn("REGISTER_RESET basis=restore deterministic initial register image");
    }

    public int[] snapshotRegisters(RegisterType registerType) {
        int[] bank = bankOf(registerType);
        synchronized (bank) {
            return Arrays.copyOf(bank, bank.length);
        }
    }

    private int[] bankOf(RegisterType registerType) {
        return registerType == RegisterType.INPUT ? inputRegisters : holdingRegisters;
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
                handleOneRequest(current, input, output);
            }
        } catch (SocketException e) {
            log.debug("Local device connection closed: {}", e.getMessage());
        } catch (IOException e) {
            log.debug("Local device connection ended: {}", e.getMessage());
        }
    }

    private void handleOneRequest(Socket socket, InputStream input, OutputStream output)
            throws IOException {
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
        RegisterType registerType = functionCode == 3 ? RegisterType.HOLDING : RegisterType.INPUT;
        int[] bank = bankOf(registerType);
        if (startAddress >= bank.length || startAddress + quantity > bank.length) {
            sendException(output, transactionId, unitId, functionCode, 0x02);
            return;
        }

        Optional<FaultProfile> fault = orchestrator.matchFault(registerType, startAddress, quantity);
        if (fault.isPresent() && applyFault(fault.get(), socket, output,
                transactionId, unitId, functionCode, registerType, startAddress, quantity)) {
            return;
        }

        byte[] response = buildResponse(transactionId, unitId, functionCode, bank, startAddress, quantity);
        writeResponse(output, response, fault.orElse(null));
    }

    /**
     * Applies a matched fault. Returns true when the normal response path must be skipped.
     */
    private boolean applyFault(FaultProfile fault, Socket socket, OutputStream output,
                               int transactionId, int unitId,
                               int functionCode, RegisterType registerType,
                               int startAddress, int quantity) throws IOException {
        String basis = "registerType=" + registerType + " address=[" + startAddress + ','
                + (startAddress + quantity - 1) + "] faultId=" + fault.getId();
        switch (fault.getType()) {
            case LATENCY -> {
                log.warn("FAULT_APPLY type=LATENCY delayMs={} {}", fault.getDelayMs(), basis);
                sleepQuietly(fault.getDelayMs());
                return false;
            }
            case TIMEOUT -> {
                log.warn("FAULT_APPLY type=TIMEOUT action=swallow-request {}", basis);
                return true;
            }
            case MODBUS_EXCEPTION -> {
                log.warn("FAULT_APPLY type=MODBUS_EXCEPTION exceptionCode={} {}",
                        fault.getExceptionCode(), basis);
                sendException(output, transactionId, unitId, functionCode, fault.getExceptionCode());
                return true;
            }
            case CONNECTION_DROP -> {
                log.warn("FAULT_APPLY type=CONNECTION_DROP action=close-socket {}", basis);
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
                return true;
            }
            case FRAGMENT, COALESCE -> {
                return false;
            }
        }
        return false;
    }

    private void writeResponse(OutputStream output, byte[] response,
                               FaultProfile fault) throws IOException {
        synchronized (output) {
            if (fault != null && fault.getType() == FaultType.COALESCE) {
                byte[] coalesced = new byte[response.length * 2];
                System.arraycopy(response, 0, coalesced, 0, response.length);
                System.arraycopy(response, 0, coalesced, response.length, response.length);
                output.write(coalesced);
                output.flush();
                log.warn("FAULT_APPLY type=COALESCE action=glue-duplicate-frame frameBytes={} "
                        + "totalBytes={} faultId={}", response.length, coalesced.length, fault.getId());
                return;
            }
            if (fault != null && fault.getType() == FaultType.FRAGMENT) {
                int chunks = Math.min(fault.getFragmentCount(), response.length);
                int chunkSize = Math.max(1, response.length / chunks);
                int offset = 0;
                int index = 0;
                while (offset < response.length) {
                    int end = index == chunks - 1
                            ? response.length
                            : Math.min(response.length, offset + chunkSize);
                    output.write(response, offset, end - offset);
                    output.flush();
                    log.warn("FAULT_APPLY type=FRAGMENT chunk={}/{} bytes=[{},{}) faultId={}",
                            index + 1, chunks, offset, end, fault.getId());
                    offset = end;
                    index++;
                    if (offset < response.length) {
                        sleepQuietly(fault.getFragmentDelayMs());
                    }
                }
                return;
            }
            output.write(response);
            output.flush();
        }
    }

    private byte[] buildResponse(int transactionId, int unitId, int functionCode, int[] bank,
                                 int startAddress, int quantity) {
        int pduLength = 2 + quantity * 2;
        byte[] response = new byte[7 + pduLength];
        writeUnsignedShort(response, 0, transactionId);
        writeUnsignedShort(response, 2, 0);
        writeUnsignedShort(response, 4, pduLength + 1);
        response[6] = (byte) unitId;
        response[7] = (byte) functionCode;
        response[8] = (byte) (quantity * 2);
        synchronized (bank) {
            for (int i = 0; i < quantity; i++) {
                writeUnsignedShort(response, 9 + i * 2, bank[startAddress + i]);
            }
        }
        return response;
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

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(Math.max(0, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
