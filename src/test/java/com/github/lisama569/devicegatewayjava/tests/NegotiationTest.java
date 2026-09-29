package com.github.lisama569.devicegatewayjava.tests;

import com.github.lisama569.devicegatewayjava.error.GatewayErrorCode;
import com.github.lisama569.devicegatewayjava.negotiation.HelloPayload;
import com.github.lisama569.devicegatewayjava.protocol.ErrorFrames;
import com.github.lisama569.devicegatewayjava.protocol.FrameCodec;
import com.github.lisama569.devicegatewayjava.protocol.FrameConstants;
import com.github.lisama569.devicegatewayjava.protocol.MessageType;
import com.github.lisama569.devicegatewayjava.support.FrameAsserts;
import com.github.lisama569.devicegatewayjava.support.GatewayTestHarness;
import com.github.lisama569.devicegatewayjava.support.LoopbackClient;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 连接建立阶段能力协商：成功 / 版本无交集 / 能力不识别 / 越权使用 / 重复协商。 */
class NegotiationTest {

    private static final Logger log = LoggerFactory.getLogger(NegotiationTest.class);

    private static byte[] helloFrame(int min, int max, List<String> caps, long seq) {
        return FrameCodec.encode(MessageType.HELLO, (byte) 0, seq,
                HelloPayload.encodeHello(min, max, caps));
    }

    private static HelloPayload.HelloAck helloAndExpectAck(LoopbackClient c, int min, int max,
                                                           List<String> caps, long seq) {
        c.sendRaw(helloFrame(min, max, caps, seq));
        FrameAsserts.RawFrame f = FrameAsserts.readFrame(c);
        assertEquals(MessageType.HELLO_ACK.code() & 0xFF, f.type(), "expected HELLO_ACK");
        HelloPayload.HelloAck ack = HelloPayload.parseHelloAck(f.payload());
        assertNotNull(ack);
        log.info("[协商成功] 设备区间={}..{} caps={} -> 选定版本={} 授予能力={}",
                min, max, caps, ack.selectedVersion(), ack.grantedCapabilities());
        return ack;
    }

    @Test
    void negotiationSuccessThenConstraintsEnforced() {
        try (GatewayTestHarness h = new GatewayTestHarness(); LoopbackClient c = h.newClient()) {
            // 1) 协商成功：区间 [1,2] 与网关 [1,1] 交集选出 1，能力 priority 被授予
            HelloPayload.HelloAck ack = helloAndExpectAck(c, 1, 2, List.of("priority"), 1);
            assertEquals(1, ack.selectedVersion());
            assertEquals(List.of("priority"), ack.grantedCapabilities());

            // 2) 登录与已协商能力的使用都正常
            c.login("device-001", "secret-001", 2);
            assertEquals(MessageType.ACK.code() & 0xFF, FrameAsserts.readFrameType(c));
            c.sendRaw(LoopbackClient.frame(MessageType.DATA, FrameConstants.FLAG_PRIORITY, 1, "p1".getBytes()));
            assertEquals(MessageType.ACK.code() & 0xFF, FrameAsserts.readFrameType(c));

            // 3) 越权使用未协商扩展字段（reserved != 0 需要 header-extension）-> 明确拒绝
            c.sendRaw(FrameCodec.encode(MessageType.DATA, (byte) 0, (byte) 0x7F, 2, "x".getBytes()));
            ErrorFrames.Parsed err = FrameAsserts.readErrorFrame(c);
            log.info("[越权拒绝] reserved 扩展字段 -> code={} reason='{}'", err.code(), err.reason());
            assertEquals(GatewayErrorCode.CAPABILITY_NOT_NEGOTIATED.code(), err.code());

            // 4) 一次越界不拖垮连接：被拒的 seq=2 未落库，重发合法 seq=2 照常处理
            c.sendRaw(LoopbackClient.frame(MessageType.DATA, FrameConstants.FLAG_PRIORITY, 2, "p2".getBytes()));
            assertEquals(MessageType.ACK.code() & 0xFF, FrameAsserts.readFrameType(c));
            assertEquals(1, h.metrics.negotiationsSucceeded());
        }
    }

    @Test
    void versionRangeWithoutOverlapTerminatesConnection() {
        try (GatewayTestHarness h = new GatewayTestHarness(); LoopbackClient c = h.newClient()) {
            c.sendRaw(helloFrame(2, 3, List.of(), 1));
            ErrorFrames.Parsed err = FrameAsserts.readErrorFrame(c);
            log.info("[版本无交集] 设备区间=2..3 -> code={} reason='{}'", err.code(), err.reason());
            assertEquals(GatewayErrorCode.NEGOTIATION_VERSION_INCOMPATIBLE.code(), err.code());
            assertTrue(err.reason().contains("2..3") && err.reason().contains("1..1"));

            // 连接必须被结束，不能按默认版本继续当正常设备
            assertEof(c);
            assertEquals(1, h.metrics.negotiationsFailed());
        }
    }

    @Test
    void unrecognizedCapabilityTerminatesConnection() {
        try (GatewayTestHarness h = new GatewayTestHarness(); LoopbackClient c = h.newClient()) {
            c.sendRaw(helloFrame(1, 1, List.of("priority", "teleport"), 1));
            ErrorFrames.Parsed err = FrameAsserts.readErrorFrame(c);
            log.info("[能力不识别] caps=[priority,teleport] -> code={} reason='{}'", err.code(), err.reason());
            assertEquals(GatewayErrorCode.NEGOTIATION_CAPABILITY_UNSUPPORTED.code(), err.code());
            assertTrue(err.reason().contains("teleport"));
            assertEof(c);
        }
    }

    @Test
    void renegotiationAfterBusinessStartRejectedButConnectionStable() {
        try (GatewayTestHarness h = new GatewayTestHarness(); LoopbackClient c = h.newClient()) {
            // 已进入业务（已认证）后再协商 -> 稳定拒绝
            c.login("device-001", "secret-001", 1);
            assertEquals(MessageType.ACK.code() & 0xFF, FrameAsserts.readFrameType(c));
            c.sendRaw(helloFrame(1, 1, List.of("priority"), 2));
            ErrorFrames.Parsed err = FrameAsserts.readErrorFrame(c);
            log.info("[中途协商] 已认证后 HELLO -> code={} reason='{}'", err.code(), err.reason());
            assertEquals(GatewayErrorCode.NEGOTIATION_NOT_ALLOWED.code(), err.code());

            // 会话规则不变，后续业务照常
            c.sendRaw(LoopbackClient.frame(MessageType.DATA, 0, 1, "ok".getBytes()));
            assertEquals(MessageType.ACK.code() & 0xFF, FrameAsserts.readFrameType(c));
        }
    }

    @Test
    void doubleHelloRejectedAndUnnegotiatedSessionUsesDefaultConstraints() {
        try (GatewayTestHarness h = new GatewayTestHarness(); LoopbackClient c = h.newClient()) {
            helloAndExpectAck(c, 1, 1, List.of("priority"), 1);
            // 已协商过的连接再次 HELLO -> 拒绝，连接保持
            c.sendRaw(helloFrame(1, 1, List.of("priority", "header-extension"), 2));
            ErrorFrames.Parsed err = FrameAsserts.readErrorFrame(c);
            assertEquals(GatewayErrorCode.NEGOTIATION_NOT_ALLOWED.code(), err.code());

            // 已锁定的能力集合不变：header-extension 仍未被授予
            c.login("device-001", "secret-001", 3);
            assertEquals(MessageType.ACK.code() & 0xFF, FrameAsserts.readFrameType(c));
            c.sendRaw(FrameCodec.encode(MessageType.DATA, (byte) 0, (byte) 0x01, 1, "x".getBytes()));
            ErrorFrames.Parsed err2 = FrameAsserts.readErrorFrame(c);
            assertEquals(GatewayErrorCode.CAPABILITY_NOT_NEGOTIATED.code(), err2.code());
        }

        // 未协商的会话按默认约束（版本 1、空能力集）：FLAG_PRIORITY 被拒
        try (GatewayTestHarness h = new GatewayTestHarness(); LoopbackClient c = h.newClient()) {
            c.login("device-001", "secret-001", 1);
            assertEquals(MessageType.ACK.code() & 0xFF, FrameAsserts.readFrameType(c));
            c.sendRaw(LoopbackClient.frame(MessageType.DATA, FrameConstants.FLAG_PRIORITY, 1, "p".getBytes()));
            ErrorFrames.Parsed err = FrameAsserts.readErrorFrame(c);
            log.info("[默认约束] 未协商会话用 FLAG_PRIORITY -> code={} reason='{}'", err.code(), err.reason());
            assertEquals(GatewayErrorCode.CAPABILITY_NOT_NEGOTIATED.code(), err.code());
            // 拒绝后连接仍可用
            c.sendRaw(LoopbackClient.frame(MessageType.DATA, 0, 1, "ok".getBytes()));
            assertEquals(MessageType.ACK.code() & 0xFF, FrameAsserts.readFrameType(c));
        }
    }

    private static void assertEof(LoopbackClient c) {
        try {
            c.readN(1);
            fail("connection should have been terminated by gateway");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("EOF"), "expected EOF, got: " + e.getMessage());
        }
    }
}
