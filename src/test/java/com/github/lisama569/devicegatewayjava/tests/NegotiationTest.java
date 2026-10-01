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

/**
 * 连接建立阶段能力协商（协商前置）：
 * 成功 / 未协商先发登录或业务被明确拒绝（1010）/ 被拒后补齐协商可继续 /
 * 版本无交集 / 能力不识别 / 越权使用 / 协商成功后再协商稳定被拒且锁定不变。
 */
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
    void businessFrameBeforeNegotiationRejectedDistinctlyAndNotApplied() {
        try (GatewayTestHarness h = new GatewayTestHarness(); LoopbackClient c = h.newClient()) {
            // 场景一：未协商直接发业务 DATA -> 1010 协商前置，绝不能当成合法设备
            c.sendRaw(LoopbackClient.frame(MessageType.DATA, 0, 1, "skip-hello".getBytes()));
            ErrorFrames.Parsed dataErr = FrameAsserts.readErrorFrame(c);
            log.info("[未协商发DATA] -> code={} reason='{}'", dataErr.code(), dataErr.reason());
            assertEquals(GatewayErrorCode.NEGOTIATION_REQUIRED.code(), dataErr.code());

            // 与“没登录(2001)”“能力对不上(1009)”明确区分，不允许混类
            assertNotEquals(GatewayErrorCode.NOT_AUTHENTICATED.code(), dataErr.code());
            assertNotEquals(GatewayErrorCode.CAPABILITY_NOT_NEGOTIATED.code(), dataErr.code());

            // 未协商的帧绝不能触达业务状态
            c.negotiate(1, 1, List.of(), 1);
            c.login("device-001", "secret-001", 2);
            assertEquals(MessageType.ACK.code() & 0xFF, FrameAsserts.readFrameType(c));
            c.sendRaw(LoopbackClient.frame(MessageType.DATA, 0, 1, "first-real".getBytes()));
            assertEquals(MessageType.ACK.code() & 0xFF, FrameAsserts.readFrameType(c));
            assertEquals(1, h.states.get("device-001").appliedCount(),
                    "pre-negotiation DATA must never have applied");
        }
    }

    @Test
    void loginBeforeNegotiationRejectedDistinctlyWithoutAuthenticating() {
        try (GatewayTestHarness h = new GatewayTestHarness(); LoopbackClient c = h.newClient()) {
            // 未协商直接 LOGIN（即使凭证正确）-> 1010，而不是登录 ACK，也不与认证类错误混淆
            c.login("device-001", "secret-001", 1);
            ErrorFrames.Parsed err = FrameAsserts.readErrorFrame(c);
            log.info("[未协商发LOGIN] -> code={} reason='{}'", err.code(), err.reason());
            assertEquals(GatewayErrorCode.NEGOTIATION_REQUIRED.code(), err.code());
            assertNotEquals(GatewayErrorCode.NOT_AUTHENTICATED.code(), err.code());
            assertNotEquals(GatewayErrorCode.CREDENTIAL_INVALID.code(), err.code());

            // 会话没有被认证：补齐协商后再发 DATA 仍是“未登录”，证明此前 LOGIN 未生效
            c.negotiate(1, 1, List.of(), 1);
            c.sendRaw(LoopbackClient.frame(MessageType.DATA, 0, 1, "x".getBytes()));
            ErrorFrames.Parsed notAuth = FrameAsserts.readErrorFrame(c);
            assertEquals(GatewayErrorCode.NOT_AUTHENTICATED.code(), notAuth.code());
            assertEquals(0, h.metrics.snapshot().get("activeSessions"),
                    "pre-negotiation LOGIN must not register an authenticated session");
        }
    }

    @Test
    void rejectedConnectionCanRecoverByNegotiatingThenLoginThenBusiness() {
        try (GatewayTestHarness h = new GatewayTestHarness(); LoopbackClient c = h.newClient()) {
            // 场景二：同一条连接先因没协商被拒（DATA 与 LOGIN 各拒一次）
            c.sendRaw(LoopbackClient.frame(MessageType.DATA, 0, 1, "early".getBytes()));
            assertEquals(GatewayErrorCode.NEGOTIATION_REQUIRED.code(),
                    FrameAsserts.readErrorFrame(c).code());
            c.login("device-001", "secret-001", 1);
            assertEquals(GatewayErrorCode.NEGOTIATION_REQUIRED.code(),
                    FrameAsserts.readErrorFrame(c).code());

            // 老老实实在同一条连接上：先协商 -> 再登录 -> 再业务，全程走通
            HelloPayload.HelloAck ack = c.negotiate(1, 1, List.of("priority"), 2);
            assertEquals(1, ack.selectedVersion());
            c.login("device-001", "secret-001", 3);
            assertEquals(MessageType.ACK.code() & 0xFF, FrameAsserts.readFrameType(c));
            c.sendRaw(LoopbackClient.frame(MessageType.DATA, FrameConstants.FLAG_PRIORITY, 1, "ok".getBytes()));
            assertEquals(MessageType.ACK.code() & 0xFF, FrameAsserts.readFrameType(c));

            // 被拒没有留下“已协商”的残留状态：恰好一次成功协商
            assertEquals(1, h.metrics.negotiationsSucceeded());
            assertEquals(1, h.states.get("device-001").appliedCount());
            log.info("[拒绝后恢复] 同连接被拒后补齐协商->登录->业务正常，无残留状态");
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
            c.negotiate(1, 1, List.of(), 1);
            // 已进入业务（已认证）后再协商 -> 稳定拒绝
            c.login("device-001", "secret-001", 2);
            assertEquals(MessageType.ACK.code() & 0xFF, FrameAsserts.readFrameType(c));
            c.sendRaw(helloFrame(1, 1, List.of("priority"), 3));
            ErrorFrames.Parsed err = FrameAsserts.readErrorFrame(c);
            log.info("[中途协商] 已认证后 HELLO -> code={} reason='{}'", err.code(), err.reason());
            assertEquals(GatewayErrorCode.NEGOTIATION_NOT_ALLOWED.code(), err.code());

            // 会话规则不变，后续业务照常，且没有产生第二次成功协商
            c.sendRaw(LoopbackClient.frame(MessageType.DATA, 0, 1, "ok".getBytes()));
            assertEquals(MessageType.ACK.code() & 0xFF, FrameAsserts.readFrameType(c));
            assertEquals(1, h.metrics.negotiationsSucceeded());
        }
    }

    @Test
    void secondHelloAfterSuccessfulNegotiationRepeatedlyRejectedAndProfileLocked() {
        try (GatewayTestHarness h = new GatewayTestHarness(); LoopbackClient c = h.newClient()) {
            helloAndExpectAck(c, 1, 1, List.of("priority"), 1);

            // 场景三：协商成功后再发 HELLO -> 每次都稳定 2008，连接保持
            for (int i = 0; i < 3; i++) {
                c.sendRaw(helloFrame(1, 1, List.of("priority", "header-extension"), 10 + i));
                ErrorFrames.Parsed err = FrameAsserts.readErrorFrame(c);
                assertEquals(GatewayErrorCode.NEGOTIATION_NOT_ALLOWED.code(), err.code(),
                        "re-negotiation attempt " + i + " must be rejected");
            }
            log.info("[重复协商] 协商成功后连续 3 次 HELLO 全部 2008，连接保持");

            // 已锁定的版本与能力集合不可更改：header-extension 始终未被授予
            c.login("device-001", "secret-001", 3);
            assertEquals(MessageType.ACK.code() & 0xFF, FrameAsserts.readFrameType(c));
            c.sendRaw(FrameCodec.encode(MessageType.DATA, (byte) 0, (byte) 0x01, 1, "x".getBytes()));
            ErrorFrames.Parsed err2 = FrameAsserts.readErrorFrame(c);
            assertEquals(GatewayErrorCode.CAPABILITY_NOT_NEGOTIATED.code(), err2.code());

            // 原本授予的 priority 仍然有效，连接在多次拒绝后照常工作
            c.sendRaw(LoopbackClient.frame(MessageType.DATA, FrameConstants.FLAG_PRIORITY, 1, "ok".getBytes()));
            assertEquals(MessageType.ACK.code() & 0xFF, FrameAsserts.readFrameType(c));
            assertEquals(1, h.metrics.negotiationsSucceeded());
        }
    }

    @Test
    void rejectingOneConnectionDoesNotAffectConcurrentConnections() {
        try (GatewayTestHarness h = new GatewayTestHarness();
             LoopbackClient bad = h.newClient();
             LoopbackClient good = h.newClient()) {

            // good 先正常协商并进入业务
            good.negotiate(1, 1, List.of("priority"), 1);
            good.login("device-002", "secret-002", 2);
            assertEquals(MessageType.ACK.code() & 0xFF, FrameAsserts.readFrameType(good));

            // bad 不协商直接发 LOGIN 与 DATA，均被明确拒绝（1010）
            bad.login("device-001", "secret-001", 1);
            assertEquals(GatewayErrorCode.NEGOTIATION_REQUIRED.code(),
                    FrameAsserts.readErrorFrame(bad).code());
            bad.sendRaw(LoopbackClient.frame(MessageType.DATA, 0, 1, "x".getBytes()));
            assertEquals(GatewayErrorCode.NEGOTIATION_REQUIRED.code(),
                    FrameAsserts.readErrorFrame(bad).code());

            // 同时在线的 good 完全不受影响，业务照常
            good.sendRaw(LoopbackClient.frame(MessageType.DATA, FrameConstants.FLAG_PRIORITY, 1, "g1".getBytes()));
            assertEquals(MessageType.ACK.code() & 0xFF, FrameAsserts.readFrameType(good));
            assertEquals(1, h.states.get("device-002").appliedCount());

            // 被拒连接被关闭后，新连接也能独立正常协商接入（拒绝不产生全局残留）
            // bad 仍可在本连接补救：协商 -> 登录 -> 业务
            bad.negotiate(1, 1, List.of(), 2);
            bad.login("device-001", "secret-001", 3);
            assertEquals(MessageType.ACK.code() & 0xFF, FrameAsserts.readFrameType(bad));
            bad.sendRaw(LoopbackClient.frame(MessageType.DATA, 0, 1, "b1".getBytes()));
            assertEquals(MessageType.ACK.code() & 0xFF, FrameAsserts.readFrameType(bad));
            assertEquals(1, h.states.get("device-001").appliedCount());
            log.info("[连接隔离] 一条连接因未协商被拒，不影响并发连接，也不阻碍其本连接补救");
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
