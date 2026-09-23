package com.github.highcumontoa.modbustcpgatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.highcumontoa.modbustcpgatewayjava.mock.MockModbusServer;
import com.github.highcumontoa.modbustcpgatewayjava.model.FailReason;
import com.github.highcumontoa.modbustcpgatewayjava.protocol.HexUtils;
import com.github.highcumontoa.modbustcpgatewayjava.protocol.ModbusRequestException;
import com.github.highcumontoa.modbustcpgatewayjava.protocol.ModbusTcpClient;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 非法报文与失败归类测试：
 * Modbus 异常码、功能码不符、长度字段不符、protocolId 非法、单元号不匹配。
 * 任何非法响应都不得作为有效读数。
 */
class IllegalFrameTest {

    private static final Logger log = LoggerFactory.getLogger(IllegalFrameTest.class);

    private MockModbusServer server;
    private ModbusTcpClient client;

    /** 构造客户端发出的第一个请求事务号固定为 1。 */
    private void newClient(int responseTimeoutMs) {
        client = new ModbusTcpClient(TestConfigs.device(server.getPort(), responseTimeoutMs));
    }

    @BeforeEach
    void setUp() {
        server = new MockModbusServer(0);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        server.stop();
    }

    private void raw(String hex) {
        server.enqueueScript(List.of(
                new MockModbusServer.ScriptAction.RawSend(HexUtils.fromHex(hex))));
    }

    private ModbusRequestException readExpectingFailure() {
        newClient(800);
        return assertThrows(ModbusRequestException.class, () -> {
            client.connect();
            client.readHoldingRegisters(0, 1);
        });
    }

    @Test
    @DisplayName("Modbus 异常码 0x02 -> MODBUS_ILLEGAL_DATA_ADDRESS")
    void exceptionCode02() {
        server.enqueueExceptionOnNextRequest(0x02);
        ModbusRequestException ex = readExpectingFailure();
        log.info("异常码判定: reason={} msg={}", ex.reason(), ex.getMessage());
        assertEquals(FailReason.MODBUS_ILLEGAL_DATA_ADDRESS, ex.reason());
    }

    @Test
    @DisplayName("Modbus 异常码 0x04 -> MODBUS_SERVER_DEVICE_FAILURE")
    void exceptionCode04() {
        server.enqueueExceptionOnNextRequest(0x04);
        ModbusRequestException ex = readExpectingFailure();
        log.info("异常码判定: reason={} msg={}", ex.reason(), ex.getMessage());
        assertEquals(FailReason.MODBUS_SERVER_DEVICE_FAILURE, ex.reason());
    }

    @Test
    @DisplayName("未知 Modbus 异常码 0x55 -> MODBUS_EXCEPTION_OTHER")
    void exceptionCodeOther() {
        // tx=1 异常响应：00 01 00 00 00 03 01 83 55
        raw("000100000003018355");
        ModbusRequestException ex = readExpectingFailure();
        log.info("未知异常码判定: reason={} msg={}", ex.reason(), ex.getMessage());
        assertEquals(FailReason.MODBUS_EXCEPTION_OTHER, ex.reason());
    }

    @Test
    @DisplayName("protocolId 非 0 -> ILLEGAL_FRAME")
    void badProtocolId() {
        // 00 01 00 01 ...
        raw("0001000100050103020001");
        ModbusRequestException ex = readExpectingFailure();
        log.info("protocolId 判定: reason={} msg={}", ex.reason(), ex.getMessage());
        assertEquals(FailReason.ILLEGAL_FRAME, ex.reason());
        assertTrue(ex.getMessage().contains("protocolId"));
    }

    @Test
    @DisplayName("byteCount 与实际帧长不符 -> ILLEGAL_FRAME，原始报文入日志")
    void byteCountMismatch() {
        // 整帧自洽为 12 字节(length=6)，但 byteCount 声明 2 而数据区实有 3 字节
        raw("000100000006010302000102");
        ModbusRequestException ex = readExpectingFailure();
        log.info("长度不符判定: reason={} msg={}", ex.reason(), ex.getMessage());
        assertEquals(FailReason.ILLEGAL_FRAME, ex.reason());
        assertTrue(ex.getMessage().contains("byteCount"));
        assertTrue(ex.getMessage().contains("00 01"), "日志必须包含原始报文");
    }

    @Test
    @DisplayName("功能码不符（返回 0x04 输入寄存器）-> ILLEGAL_FRAME")
    void wrongFunctionCode() {
        // 00 01 00 00 00 05 01 04 02 00 01
        raw("0001000000050104020001");
        ModbusRequestException ex = readExpectingFailure();
        log.info("功能码判定: reason={} msg={}", ex.reason(), ex.getMessage());
        assertEquals(FailReason.ILLEGAL_FRAME, ex.reason());
    }

    @Test
    @DisplayName("单元号不匹配（请求 unit=1，响应 unit=7）-> TRANSACTION_MISMATCH")
    void wrongUnitId() {
        byte[] frame = MockModbusServer.readResponseFrame(1, 7, HexUtils.fromHex("0001"));
        server.enqueueScript(List.of(new MockModbusServer.ScriptAction.RawSend(frame)));
        ModbusRequestException ex = readExpectingFailure();
        log.info("单元号判定: reason={} msg={}", ex.reason(), ex.getMessage());
        assertEquals(FailReason.TRANSACTION_MISMATCH, ex.reason());
    }

    @Test
    @DisplayName("返回寄存器数与请求不符（请求 1 寄存器，返回 2 寄存器）-> ILLEGAL_FRAME")
    void registerCountMismatch() {
        // 请求 qty=1，但数据 4 字节：00 01 ... 01 03 04 00 01 00 02
        raw("00010000000701030400010002");
        ModbusRequestException ex = readExpectingFailure();
        log.info("寄存器数判定: reason={} msg={}", ex.reason(), ex.getMessage());
        assertEquals(FailReason.ILLEGAL_FRAME, ex.reason());
        assertTrue(ex.getMessage().contains("字节数"));
    }

    @Test
    @DisplayName("对端发送垃圾字节（非 MBAP）-> ILLEGAL_FRAME 且连接被丢弃")
    void garbageBytes() {
        raw("FFFFFFFFFFFF");
        ModbusRequestException ex = readExpectingFailure();
        log.info("垃圾字节判定: reason={} msg={}", ex.reason(), ex.getMessage());
        assertEquals(FailReason.ILLEGAL_FRAME, ex.reason());
        assertEquals(ModbusTcpClient.State.IDLE, client.state(),
                "非法帧后连接必须被关闭并回到 IDLE");
    }
}
