package com.github.lisama569.devicegatewayjava.tests;

import com.github.lisama569.devicegatewayjava.config.GatewayProperties;
import com.github.lisama569.devicegatewayjava.error.GatewayErrorCode;
import com.github.lisama569.devicegatewayjava.protocol.ErrorFrames;
import com.github.lisama569.devicegatewayjava.protocol.FrameConstants;
import com.github.lisama569.devicegatewayjava.protocol.MessageType;
import com.github.lisama569.devicegatewayjava.support.FrameAsserts;
import com.github.lisama569.devicegatewayjava.support.GatewayTestHarness;
import com.github.lisama569.devicegatewayjava.support.LoopbackClient;
import com.github.lisama569.devicegatewayjava.support.ManualTaskScheduler;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

/** 每连接接收残留硬上限：半包占用超限 -> 可区分的资源耗尽结论 + 结束该连接，其他连接不受影响。 */
class ReceiveBufferLimitTest {

    private static final Logger log = LoggerFactory.getLogger(ReceiveBufferLimitTest.class);

    /** 残留上限 200 字节（远小于单帧上限 512，专门用于挤压半包残留）。 */
    private static GatewayTestHarness harnessWithSmallReceiveBuffer() {
        GatewayProperties p = GatewayTestHarness.defaultProps();
        p.setMaxFrameLength(512);
        p.setMaxReceiveBufferBytes(200);
        return new GatewayTestHarness(p, new ManualTaskScheduler(),
                Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC));
    }

    /** 手工拼一个声明 400 字节 payload 的帧头（合法声明，但永不补全 -> 半包残留）。 */
    private static byte[] headerDeclaring(int payloadLen) {
        byte[] h = new byte[FrameConstants.HEADER_LEN];
        h[0] = (byte) (FrameConstants.MAGIC >>> 8);
        h[1] = (byte) FrameConstants.MAGIC;
        h[2] = 1; // version
        h[3] = MessageType.DATA.code();
        h[10] = (byte) (payloadLen >>> 24);
        h[11] = (byte) (payloadLen >>> 16);
        h[12] = (byte) (payloadLen >>> 8);
        h[13] = (byte) payloadLen;
        return h;
    }

    @Test
    void incompleteFrameResidualExceedingLimitTerminatesOnlyThatConnection() {
        try (GatewayTestHarness h = harnessWithSmallReceiveBuffer();
             LoopbackClient greedy = h.newClient();
             LoopbackClient bystander = h.newClient()) {

            // 设备只发一半：声明 400 字节，实际 drip 250 字节后挂住（残留 14+250=264 > 200）
            greedy.sendRaw(headerDeclaring(400));
            greedy.sendRaw(new byte[250]);

            // 得到可区分的资源耗尽结论，随后连接被结束
            ErrorFrames.Parsed err = FrameAsserts.readErrorFrame(greedy);
            log.info("[接收超限] 残留 264 > 上限 200 -> code={} reason='{}'", err.code(), err.reason());
            assertEquals(GatewayErrorCode.RECEIVE_BUFFER_FULL.code(), err.code());
            assertTrue(err.reason().contains("200"), "reason should state the limit: " + err.reason());
            try {
                greedy.readN(1);
                fail("overflowing connection should have been terminated");
            } catch (IllegalStateException e) {
                assertTrue(e.getMessage().contains("EOF"));
            }
            assertEquals(1, h.metrics.receiveBufferOverflows());
            assertEquals(1, h.metrics.errorCount(GatewayErrorCode.RECEIVE_BUFFER_FULL.code()));

            // 其他连接完全不受影响
            bystander.login("device-001", "secret-001", 1);
            assertEquals(MessageType.ACK.code() & 0xFF, FrameAsserts.readFrameType(bystander));
            bystander.sendRaw(LoopbackClient.frame(MessageType.DATA, 0, 1, "fine".getBytes()));
            assertEquals(MessageType.ACK.code() & 0xFF, FrameAsserts.readFrameType(bystander));
            log.info("[隔离性] 旁观连接登录+数据帧正常，overflows={}", h.metrics.receiveBufferOverflows());
        }
    }

    @Test
    void residualBelowLimitKeepsConnectionAlive() {
        try (GatewayTestHarness h = harnessWithSmallReceiveBuffer(); LoopbackClient c = h.newClient()) {
            // 登录帧分两片发送，中间残留低于上限 -> 正常拼帧、正常认证
            byte[] login = LoopbackClient.frame(MessageType.LOGIN, 0, 1,
                    LoopbackClient.loginPayload("device-001", "secret-001"));
            assertTrue(login.length < 200);
            c.sendRaw(login, 0, 10); // 半包挂起（残留 10 字节 < 200）
            c.sendRaw(login, 10, login.length - 10);
            assertEquals(MessageType.ACK.code() & 0xFF, FrameAsserts.readFrameType(c));
            assertEquals(0, h.metrics.receiveBufferOverflows());
            log.info("[限内残留] 半包 {} 字节低于上限，拼帧后正常登录", 10);
        }
    }
}
