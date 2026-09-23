package com.github.highcumontoa.modbustcpgatewayjava.mock;

import com.github.highcumontoa.modbustcpgatewayjava.protocol.ModbusFrames;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地可复现的 Modbus TCP 数据源（替代真实设备）。
 *
 * <p>两条工作模式：
 * <ol>
 *   <li><b>寄存器映像模式（默认）</b>：按收到的 FC=0x03 请求从寄存器映像构造标准响应；</li>
 *   <li><b>脚本模式</b>：对排队接入的下一条连接，严格按 {@link ScriptAction} 序列执行——
 *       可以分片发送、发送延迟、发送原始非法字节、发送乱序事务号帧、
 *       中途断连等，用于复现异常网络条件。</li>
 * </ol>
 *
 * <p>所有收到的请求帧与发送的字节都会留痕（{@link #receivedRequests()}），
 * 测试日志可打印原始报文。
 */
public class MockModbusServer {

    /** 连接行为脚本的动作。 */
    public sealed interface ScriptAction permits ScriptAction.Send, ScriptAction.RawSend,
            ScriptAction.Delay, ScriptAction.Close, DynamicException, DelayThenEcho,
            EchoThenClose, ReadOneThenClose {
        /** 按 Modbus 帧一次性发送 */
        record Send(byte[] frame) implements ScriptAction {
        }

        /** 发送任意原始字节（可用于非法帧、精细分片） */
        record RawSend(byte[] bytes) implements ScriptAction {
        }

        /** 延迟若干毫秒（用于制造客户端侧超时 / 不完整帧） */
        record Delay(long millis) implements ScriptAction {
        }

        /** 直接关闭连接 */
        record Close() implements ScriptAction {
        }
    }

    private final int requestedPort;
    private final ServerSocketHolder holder = new ServerSocketHolder();

    // unitId -> (address -> register value)
    private final Map<Integer, Map<Integer, Integer>> registerImage = new ConcurrentHashMap<>();

    // 每条新接入连接依次领取一段脚本
    private final ConcurrentLinkedQueue<List<ScriptAction>> scriptQueue = new ConcurrentLinkedQueue<>();

    private final List<byte[]> receivedRequests = new CopyOnWriteArrayList<>();
    private final AtomicInteger accepted = new AtomicInteger();
    private final List<Thread> handlerThreads = new CopyOnWriteArrayList<>();

    private volatile int boundPort;

    public MockModbusServer(int port) {
        this.requestedPort = port;
    }

    public void start() {
        if (holder.running) {
            return;
        }
        try {
            holder.serverSocket = new ServerSocket();
            holder.serverSocket.setReuseAddress(true);
            holder.serverSocket.bind(new InetSocketAddress("127.0.0.1", requestedPort));
            this.boundPort = holder.serverSocket.getLocalPort();
            holder.running = true;
            Thread acceptor = new Thread(this::acceptLoop, "mock-modbus-acceptor");
            acceptor.setDaemon(true);
            acceptor.start();
        } catch (IOException e) {
            throw new IllegalStateException("Mock 服务启动失败 port=" + requestedPort, e);
        }
    }

    public void stop() {
        holder.running = false;
        try {
            holder.serverSocket.close();
        } catch (IOException ignored) {
            // 停止路径
        }
        for (Thread t : handlerThreads) {
            t.interrupt();
        }
    }

    public int getPort() {
        return boundPort;
    }

    // ---------- 寄存器映像 ----------

    public void setRegister(int unitId, int address, int value) {
        registerImage.computeIfAbsent(unitId, k -> new ConcurrentHashMap<>())
                .put(address, value & 0xFFFF);
    }

    public void setRegisters(int unitId, int startAddress, int[] values) {
        for (int i = 0; i < values.length; i++) {
            setRegister(unitId, startAddress + i, values[i]);
        }
    }

    // ---------- 脚本 ----------

    public void enqueueScript(List<ScriptAction> actions) {
        scriptQueue.add(List.copyOf(actions));
    }

    /** 对下一条连接：收到第一个 FC=0x03 请求后返回给定 Modbus 异常码。 */
    public void enqueueExceptionOnNextRequest(int exceptionCode) {
        scriptQueue.add(List.of(new DynamicException(exceptionCode)));
    }

    /** 对下一条连接：接受连接后什么都不做（静默），用于触发客户端读超时。 */
    public void enqueueSilentThenHang() {
        scriptQueue.add(List.of(new ScriptAction.Delay(Long.MAX_VALUE / 4)));
    }

    /** 对下一条连接：先延迟再正常应答（用于触发不完整帧/超时边界测试由分片脚本覆盖）。 */
    public void enqueueDelayThenEcho(long delayMillis) {
        scriptQueue.add(List.of(new DelayThenEcho(delayMillis)));
    }

    /** 对下一条连接：正常应答第一个请求后立即断开（模拟链路中断）。 */
    public void enqueueEchoOnceThenClose() {
        scriptQueue.add(List.of(new EchoThenClose()));
    }

    /** 对下一条连接：先读取并丢弃一个请求，然后断开（不回任何响应，模拟静默断连前半请求）。 */
    public void enqueueReadOneThenClose() {
        scriptQueue.add(List.of(new ReadOneThenClose()));
    }

    public List<byte[]> receivedRequests() {
        return List.copyOf(receivedRequests);
    }

    public int acceptedConnectionCount() {
        return accepted.get();
    }

    // ---------- 内部 ----------

    private void acceptLoop() {
        while (holder.running) {
            try {
                Socket socket = holder.serverSocket.accept();
                accepted.incrementAndGet();
                List<ScriptAction> script = scriptQueue.poll();
                Thread t = new Thread(() -> handle(socket, script),
                        "mock-modbus-conn-" + accepted.get());
                t.setDaemon(true);
                handlerThreads.add(t);
                t.start();
            } catch (IOException e) {
                if (holder.running) {
                    // accept 异常，退避后继续
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    private void handle(Socket socket, List<ScriptAction> script) {
        try (socket) {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(200);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            byte[] rxBuf = new byte[512];

            if (script != null) {
                runScript(socket, in, out, rxBuf, script);
                return;
            }

            // 默认寄存器映像应答
            while (holder.running && !socket.isClosed()) {
                byte[] request = readFrame(in, rxBuf);
                if (request == null) {
                    return;
                }
                receivedRequests.add(request);
                byte[] response = buildEchoResponse(request);
                if (response != null) {
                    out.write(response);
                    out.flush();
                }
            }
        } catch (Exception e) {
            // 连接级异常：关闭该连接即可，服务继续
        }
    }

    private void runScript(Socket socket, InputStream in, OutputStream out, byte[] rxBuf,
                           List<ScriptAction> script) throws Exception {
        // 动态动作（需要看到请求才能构造响应）
        for (ScriptAction action : script) {
            switch (action) {
                case ScriptAction.Send send -> {
                    out.write(send.frame());
                    out.flush();
                }
                case ScriptAction.RawSend raw -> {
                    out.write(raw.bytes());
                    out.flush();
                }
                case ScriptAction.Delay delay -> {
                    long ms = delay.millis();
                    if (ms > 60_000) {
                        // 静默挂起：周期性睡眠直到连接被客户端关闭
                        while (holder.running && !socket.isClosed()) {
                            Thread.sleep(100);
                        }
                        return;
                    }
                    Thread.sleep(ms);
                }
                case ScriptAction.Close ignore -> {
                    socket.close();
                    return;
                }
                case DynamicException dyn -> {
                    byte[] request = readFrame(in, rxBuf);
                    if (request == null) {
                        return;
                    }
                    receivedRequests.add(request);
                    byte[] response = buildExceptionResponse(request, dyn.exceptionCode());
                    out.write(response);
                    out.flush();
                }
                case DelayThenEcho delayThenEcho -> {
                    byte[] request = readFrame(in, rxBuf);
                    if (request == null) {
                        return;
                    }
                    receivedRequests.add(request);
                    Thread.sleep(delayThenEcho.delayMillis());
                    byte[] response = buildEchoResponse(request);
                    if (response != null) {
                        out.write(response);
                        out.flush();
                    }
                }
                case EchoThenClose ignore -> {
                    byte[] request = readFrame(in, rxBuf);
                    if (request == null) {
                        return;
                    }
                    receivedRequests.add(request);
                    byte[] response = buildEchoResponse(request);
                    out.write(response);
                    out.flush();
                    socket.close();
                    return;
                }
                case ReadOneThenClose ignore -> {
                    byte[] request = readFrame(in, rxBuf);
                    if (request != null) {
                        receivedRequests.add(request);
                    }
                    socket.close();
                    return;
                }
            }
        }
        // 脚本跑完后保持连接，继续做默认应答
        while (holder.running && !socket.isClosed()) {
            byte[] request = readFrame(in, rxBuf);
            if (request == null) {
                return;
            }
            receivedRequests.add(request);
            byte[] response = buildEchoResponse(request);
            if (response != null) {
                out.write(response);
                out.flush();
            }
        }
    }

    /** 从流中读取一帧（处理粘包，仅服务端测试用途）；EOF 返回 null，空闲超时继续等待。 */
    private byte[] readFrame(InputStream in, byte[] rxBuf) throws IOException {
        // 简单实现：循环读到至少一个完整 MBAP 帧
        java.io.ByteArrayOutputStream accum = new java.io.ByteArrayOutputStream();
        while (holder.running && !Thread.currentThread().isInterrupted()) {
            int n;
            try {
                n = in.read(rxBuf);
            } catch (java.net.SocketTimeoutException ste) {
                // soTimeout 仅用于周期性检查运行标志，空闲不代表连接结束
                continue;
            }
            if (n < 0) {
                return null;
            }
            accum.write(rxBuf, 0, n);
            byte[] all = accum.toByteArray();
            if (all.length >= 6) {
                int lengthField = ((all[4] & 0xFF) << 8) | (all[5] & 0xFF);
                int total = 6 + lengthField;
                if (all.length >= total) {
                    byte[] frame = new byte[total];
                    System.arraycopy(all, 0, frame, 0, total);
                    return frame;
                }
            }
        }
        return null;
    }

    private byte[] buildEchoResponse(byte[] request) {
        if (request.length < 12) {
            return null;
        }
        int unitId = request[6] & 0xFF;
        int fc = request[7] & 0xFF;
        int startAddr = ((request[8] & 0xFF) << 8) | (request[9] & 0xFF);
        int qty = ((request[10] & 0xFF) << 8) | (request[11] & 0xFF);
        if (fc != ModbusFrames.FUNCTION_READ_HOLDING_REGISTERS || qty < 1 || qty > 125) {
            return buildExceptionResponse(request, 0x01);
        }
        Map<Integer, Integer> image = registerImage.getOrDefault(unitId, Map.of());
        byte[] data = new byte[qty * 2];
        for (int i = 0; i < qty; i++) {
            int value = image.getOrDefault(startAddr + i, 0);
            data[i * 2] = (byte) (value >>> 8);
            data[i * 2 + 1] = (byte) value;
        }
        int lengthField = 3 + data.length; // unitId + fc + byteCount + data
        byte[] resp = new byte[6 + lengthField];
        resp[0] = request[0];
        resp[1] = request[1];
        resp[2] = 0;
        resp[3] = 0;
        resp[4] = (byte) (lengthField >>> 8);
        resp[5] = (byte) lengthField;
        resp[6] = (byte) unitId;
        resp[7] = (byte) fc;
        resp[8] = (byte) data.length;
        System.arraycopy(data, 0, resp, 9, data.length);
        return resp;
    }

    private byte[] buildExceptionResponse(byte[] request, int exceptionCode) {
        return new byte[] {
                request[0], request[1], 0, 0, 0, 3,
                request[6],
                (byte) 0x83,
                (byte) exceptionCode
        };
    }

    /** 动态脚本动作：收到请求后回 Modbus 异常码。 */
    private record DynamicException(int exceptionCode) implements ScriptAction {
    }

    /** 动态脚本动作：延迟后回显寄存器映像。 */
    private record DelayThenEcho(long delayMillis) implements ScriptAction {
    }

    /** 动态脚本动作：正常应答一次后关闭连接。 */
    private record EchoThenClose() implements ScriptAction {
    }

    /** 动态脚本动作：读取一个请求（不响应）后关闭连接。 */
    private record ReadOneThenClose() implements ScriptAction {
    }

    private static final class ServerSocketHolder {
        volatile ServerSocket serverSocket;
        volatile boolean running;
    }

    // ---------- 静态构造助手：便于测试拼帧 ----------

    /** 构造一帧 FC=0x03 正常响应（可覆盖事务号）。 */
    public static byte[] readResponseFrame(int transactionId, int unitId, byte[] data) {
        int lengthField = 3 + data.length;
        byte[] frame = new byte[6 + lengthField];
        frame[0] = (byte) (transactionId >>> 8);
        frame[1] = (byte) transactionId;
        frame[4] = (byte) (lengthField >>> 8);
        frame[5] = (byte) lengthField;
        frame[6] = (byte) unitId;
        frame[7] = (byte) ModbusFrames.FUNCTION_READ_HOLDING_REGISTERS;
        frame[8] = (byte) data.length;
        System.arraycopy(data, 0, frame, 9, data.length);
        return frame;
    }

    /** 把帧切成逐字节/指定大小的分片动作。 */
    public static List<ScriptAction> fragmentedSend(byte[] frame, int fragmentSize,
                                                    long gapMillis) {
        List<ScriptAction> actions = new ArrayList<>();
        for (int off = 0; off < frame.length; off += fragmentSize) {
            int end = Math.min(off + fragmentSize, frame.length);
            byte[] piece = new byte[end - off];
            System.arraycopy(frame, off, piece, 0, piece.length);
            actions.add(new ScriptAction.RawSend(piece));
            if (gapMillis > 0) {
                actions.add(new ScriptAction.Delay(gapMillis));
            }
        }
        return actions;
    }
}
