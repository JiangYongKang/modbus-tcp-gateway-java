package com.github.highcumontoa.modbustcpgatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.highcumontoa.modbustcpgatewayjava.mock.MockModbusServer;
import com.github.highcumontoa.modbustcpgatewayjava.protocol.HexUtils;
import com.github.highcumontoa.modbustcpgatewayjava.protocol.ModbusRequestException;
import com.github.highcumontoa.modbustcpgatewayjava.protocol.ModbusTcpClient;
import com.github.highcumontoa.modbustcpgatewayjava.model.FailReason;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 流式分片、粘包、乱序响应处理测试。
 * 每个用例通过 Mock 数据源脚本精确控制对端发出的原始字节，
 * 日志打印原始报文与判定依据。
 */
class FrameAssemblyTest {

    private static final Logger log = LoggerFactory.getLogger(FrameAssemblyTest.class);

    private MockModbusServer server;
    private ModbusTcpClient client;

    @BeforeEach
    void setUp() {
        server = new MockModbusServer(0);
        server.start();
        client = new ModbusTcpClient(TestConfigs.device(server.getPort(), 1500));
    }

    @AfterEach
    void tearDown() {
        client.close();
        server.stop();
    }

    @Test
    @DisplayName("逐字节分片到达（30ms/片）仍能重组完整帧")
    void byteByByteFragments() throws Exception {
        byte[] data = HexUtils.fromHex("000A0014"); // 2 个寄存器
        byte[] frame = MockModbusServer.readResponseFrame(1, 1, data);
        server.enqueueScript(MockModbusServer.fragmentedSend(frame, 1, 30));

        client.connect();
        byte[] got = client.readHoldingRegisters(0, 2);
        log.info("分片场景: 期望={} 实际={}", HexUtils.toHex(data), HexUtils.toHex(got));
        assertArrayEquals(data, got);
    }

    @Test
    @DisplayName("粘包：一次到达 2 帧，连续两次读取各取其一，不重复不错位")
    void stickyPacketsTwoFramesInOneChunk() throws Exception {
        byte[] d1 = HexUtils.fromHex("0001");
        byte[] d2 = HexUtils.fromHex("0002");
        byte[] f1 = MockModbusServer.readResponseFrame(1, 1, d1);
        byte[] f2 = MockModbusServer.readResponseFrame(2, 1, d2);
        byte[] concat = new byte[f1.length + f2.length];
        System.arraycopy(f1, 0, concat, 0, f1.length);
        System.arraycopy(f2, 0, concat, f1.length, f2.length);

        // 第一段脚本：连接建立后立即一次性发送两帧
        server.enqueueScript(List.of(
                new MockModbusServer.ScriptAction.RawSend(concat)));
        // 第二段脚本（第二个连接）：本用例只需一连接两请求，故第二请求的帧已在同缓冲
        client.connect();
        byte[] g1 = client.readHoldingRegisters(0, 1);
        byte[] g2 = client.readHoldingRegisters(1, 1);
        log.info("粘包场景: 帧1={} 帧2={}", HexUtils.toHex(g1), HexUtils.toHex(g2));
        assertArrayEquals(d1, g1);
        assertArrayEquals(d2, g2);
    }

    @Test
    @DisplayName("乱序：响应事务号不匹配必须失败并归类 TRANSACTION_MISMATCH")
    void wrongTransactionId() {
        byte[] frame = MockModbusServer.readResponseFrame(99, 1, HexUtils.fromHex("0001"));
        server.enqueueScript(MockModbusServer.fragmentedSend(frame, frame.length, 0));

        ModbusRequestException ex = assertThrows(ModbusRequestException.class, () -> {
            client.connect();
            client.readHoldingRegisters(0, 1);
        });
        log.info("乱序判定: reason={} msg={}", ex.reason(), ex.getMessage());
        assertEquals(FailReason.TRANSACTION_MISMATCH, ex.reason());
        assertTrue(ex.getMessage().contains("事务号"));
    }

    @Test
    @DisplayName("半包未在超时窗口内收齐：归类 INCOMPLETE_FRAME，且不当作有效读数")
    void incompleteWithinTimeout() {
        byte[] data = HexUtils.fromHex("000A0014");
        byte[] frame = MockModbusServer.readResponseFrame(1, 1, data);
        // 只发送前 6 字节（MBAP 头），然后静默；客户端 timeout=400
        client = new ModbusTcpClient(TestConfigs.device(server.getPort(), 400));
        byte[] head = new byte[6];
        System.arraycopy(frame, 0, head, 0, 6);
        server.enqueueScript(List.of(
                new MockModbusServer.ScriptAction.RawSend(head),
                new MockModbusServer.ScriptAction.Delay(Long.MAX_VALUE / 4)));

        ModbusRequestException ex = assertThrows(ModbusRequestException.class, () -> {
            client.connect();
            client.readHoldingRegisters(0, 2);
        });
        log.info("不完整帧判定: reason={} msg={}", ex.reason(), ex.getMessage());
        assertEquals(FailReason.INCOMPLETE_FRAME, ex.reason());
        assertTrue(ex.getMessage().contains("残缺响应=00 01 00 00 00 07"));
    }

    @Test
    @DisplayName("正常响应后连接仍可继续接收下一请求（回归：不粘死、不漏帧）")
    void normalSequentialRequests() throws Exception {
        client.connect();
        server.setRegister(1, 0, 0x1234);
        server.setRegister(1, 10, 0xABCD);
        byte[] g1 = client.readHoldingRegisters(0, 1);
        byte[] g2 = client.readHoldingRegisters(10, 1);
        log.info("顺序读取: 0={} 10={}", HexUtils.toHex(g1), HexUtils.toHex(g2));
        assertArrayEquals(HexUtils.fromHex("1234"), g1);
        assertArrayEquals(HexUtils.fromHex("ABCD"), g2);
        assertEquals(2, server.receivedRequests().size());
    }
}
