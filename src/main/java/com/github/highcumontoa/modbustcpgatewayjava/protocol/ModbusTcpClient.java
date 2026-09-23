package com.github.highcumontoa.modbustcpgatewayjava.protocol;

import com.github.highcumontoa.modbustcpgatewayjava.config.DeviceConfig;
import com.github.highcumontoa.modbustcpgatewayjava.model.FailReason;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单设备 Modbus TCP 客户端。
 *
 * <p>保证：
 * <ul>
 *   <li>在 TCP 流上处理半包与粘包（{@link FramingBuffer}）；</li>
 *   <li>按 MBAP 事务号匹配响应，乱序 / 串号响应直接判失败，绝不张冠李戴；</li>
 *   <li>任何失败（超时、不完整、非法帧、异常码、断连）都会关闭底层连接，
 *       陈旧字节不可能污染下一次请求；下一次调用必须重新 connect()；</li>
 *   <li>失败异常的 {@link FailReason} 可区分，并携带原始报文十六进制。</li>
 * </ul>
 */
public class ModbusTcpClient {

    public enum State {
        IDLE, CONNECTING, CONNECTED, CLOSED
    }

    private final DeviceConfig config;
    private final AtomicInteger transactionSeq = new AtomicInteger(0);

    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private FramingBuffer rxBuf;
    private volatile State state = State.IDLE;

    public ModbusTcpClient(DeviceConfig config) {
        this.config = config;
    }

    public State state() {
        return state;
    }

    public boolean isConnected() {
        return state == State.CONNECTED && socket != null && !socket.isClosed();
    }

    public DeviceConfig config() {
        return config;
    }

    /**
     * 建立连接。已连接时直接返回。
     *
     * @throws ModbusRequestException 原因为 CONNECT_FAILED
     */
    public synchronized void connect() throws ModbusRequestException {
        if (isConnected()) {
            return;
        }
        state = State.CONNECTING;
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(config.host(), config.port()),
                    config.connectTimeoutMs());
            s.setSoTimeout(config.responseTimeoutMs());
            s.setTcpNoDelay(true);
            this.socket = s;
            this.in = s.getInputStream();
            this.out = s.getOutputStream();
            this.rxBuf = new FramingBuffer();
            this.state = State.CONNECTED;
        } catch (IOException e) {
            closeQuietly(s);
            this.socket = null;
            this.state = State.IDLE;
            throw new ModbusRequestException(FailReason.CONNECT_FAILED,
                    "连接 " + config.host() + ":" + config.port() + " 失败: " + e.getMessage(), e);
        }
    }

    public synchronized void close() {
        state = State.CLOSED;
        closeQuietly(socket);
        socket = null;
        in = null;
        out = null;
        rxBuf = null;
    }

    /**
     * 读取保持寄存器（FC=0x03），返回数据区原始字节（quantity*2，线序）。
     */
    public synchronized byte[] readHoldingRegisters(int address, int quantity)
            throws ModbusRequestException {
        if (address < 0 || address > 0xFFFF) {
            throw new ModbusRequestException(FailReason.CONFIG_ERROR,
                    "非法寄存器地址: " + address);
        }
        if (quantity < 1 || quantity > ModbusFrames.MAX_REGISTERS) {
            throw new ModbusRequestException(FailReason.CONFIG_ERROR,
                    "非法寄存器数量: " + quantity);
        }
        if (!isConnected()) {
            throw new ModbusRequestException(FailReason.CONNECT_FAILED,
                    "连接未建立，无法读取 unitId=" + config.unitId()
                            + " addr=" + address + " qty=" + quantity);
        }

        int tx = transactionSeq.updateAndGet(v -> (v + 1) & 0xFFFF);
        byte[] request = ModbusFrames.buildReadHoldingRegisters(tx, config.unitId(), address, quantity);

        try {
            out.write(request);
            out.flush();
        } catch (IOException e) {
            failAndClose(FailReason.CONNECTION_LOST,
                    "发送请求时连接中断: " + e.getMessage()
                            + "，请求帧=" + HexUtils.toHex(request), e);
        }

        byte[] chunk = new byte[512];
        while (true) {
            byte[] frame;
            try {
                frame = rxBuf.pollFrame();
            } catch (ModbusRequestException bad) {
                // 帧头自相矛盾：丢弃连接
                failAndClose(bad.reason(), bad.getMessage()
                        + "，请求事务=" + tx, null);
                return unreachable();
            }
            if (frame != null) {
                return validateFrame(frame, tx, address, quantity, request);
            }
            int n;
            try {
                n = in.read(chunk);
            } catch (SocketTimeoutException e) {
                FailReason reason = rxBuf.size() > 0
                        ? FailReason.INCOMPLETE_FRAME
                        : FailReason.TIMEOUT;
                failAndClose(reason,
                        "等待响应超时（" + config.responseTimeoutMs() + "ms），已收 "
                                + rxBuf.size() + " 字节；请求="
                                + HexUtils.toHex(request)
                                + (rxBuf.size() > 0
                                        ? "，残缺响应=" + HexUtils.toHex(drainPartial()) : ""),
                        e);
                return unreachable();
            } catch (IOException e) {
                failAndClose(FailReason.CONNECTION_LOST,
                        "读取响应时连接中断: " + e.getMessage()
                                + "，请求=" + HexUtils.toHex(request), e);
                return unreachable();
            }
            if (n < 0) {
                failAndClose(FailReason.CONNECTION_LOST,
                        "对端在响应前关闭连接（EOF）；请求=" + HexUtils.toHex(request)
                                + (rxBuf.size() > 0
                                        ? "，残缺响应=" + HexUtils.toHex(drainPartial()) : ""),
                        null);
                return unreachable();
            }
            rxBuf.append(chunk, n);
        }
    }

    /** 校验响应帧并提取数据区；任何不匹配都失败并关闭连接。 */
    private byte[] validateFrame(byte[] frame, int expectTx, int address, int quantity,
                                 byte[] request) throws ModbusRequestException {
        int tx = ModbusFrames.transactionId(frame);
        int unit = ModbusFrames.unitId(frame);
        int fc = ModbusFrames.functionCode(frame);
        String raw = HexUtils.toHex(frame);

        if (tx != expectTx) {
            failAndClose(FailReason.TRANSACTION_MISMATCH,
                    "响应事务号 " + tx + " 与请求事务号 " + expectTx + " 不匹配（乱序/串号），"
                            + "响应=" + raw + "，请求=" + HexUtils.toHex(request), null);
        }
        if (unit != (config.unitId() & 0xFF)) {
            failAndClose(FailReason.TRANSACTION_MISMATCH,
                    "响应单元号 " + unit + " 与请求单元号 " + config.unitId()
                            + " 不匹配，响应=" + raw, null);
        }

        if (ModbusFrames.isExceptionFunction(fc)) {
            if (fc != (0x80 | ModbusFrames.FUNCTION_READ_HOLDING_REGISTERS)) {
                failAndClose(FailReason.ILLEGAL_FRAME,
                        "异常响应功能码 " + fc + " 与请求 0x03 不匹配，响应=" + raw, null);
            }
            if (frame.length != 9 || ModbusFrames.lengthField(frame) != 3) {
                failAndClose(FailReason.ILLEGAL_FRAME,
                        "异常响应长度非法（应为 9 字节），响应=" + raw, null);
            }
            int code = frame[8] & 0xFF;
            failAndClose(ModbusFrames.mapExceptionCode(code),
                    "设备返回 Modbus 异常码 0x" + String.format("%02X", code)
                            + "（unitId=" + unit + ", addr=" + address
                            + ", qty=" + quantity + "），响应=" + raw, null);
        }

        if (fc != ModbusFrames.FUNCTION_READ_HOLDING_REGISTERS) {
            failAndClose(FailReason.ILLEGAL_FRAME,
                    "不支持的响应功能码 0x" + String.format("%02X", fc)
                            + "，期望 0x03，响应=" + raw, null);
        }
        if (frame.length < 9) {
            failAndClose(FailReason.ILLEGAL_FRAME,
                    "正常响应缺少字节计数域，响应=" + raw, null);
        }
        int byteCount = ModbusFrames.byteCount(frame);
        int expectedTotal = 9 + byteCount;
        if (frame.length != expectedTotal) {
            failAndClose(FailReason.ILLEGAL_FRAME,
                    "byteCount(" + byteCount + ") 与实际帧长(" + frame.length
                            + ")不符，响应=" + raw, null);
        }
        if (byteCount != quantity * 2) {
            failAndClose(FailReason.ILLEGAL_FRAME,
                    "返回字节数 " + byteCount + " 与请求寄存器数 " + quantity
                            + "（应 " + quantity * 2 + " 字节）不符，响应=" + raw, null);
        }
        if ((byteCount & 1) != 0) {
            failAndClose(FailReason.ILLEGAL_FRAME,
                    "数据区长度不是偶数字节: " + byteCount + "，响应=" + raw, null);
        }
        byte[] data = new byte[byteCount];
        System.arraycopy(frame, 9, data, 0, byteCount);
        return data;
    }

    private byte[] drainPartial() {
        return rxBuf == null ? new byte[0] : rxBuf.copyBytes();
    }

    private void failAndClose(FailReason reason, String message, Throwable cause)
            throws ModbusRequestException {
        close();
        if (state == State.CONNECTING) {
            state = State.IDLE;
        } else if (state == State.CLOSED) {
            state = State.IDLE; // 失败后允许重新 connect
        }
        if (cause != null) {
            throw new ModbusRequestException(reason, message, cause);
        }
        throw new ModbusRequestException(reason, message);
    }

    private byte[] unreachable() throws ModbusRequestException {
        throw new AssertionError("failAndClose 必须抛出异常");
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // 关闭路径无需再抛异常
            }
        }
    }
}
