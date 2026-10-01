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
 * 连接建立阶段能力协商：
 * 成功 / 版本无交集 / 能力不识别 / 越权使用 / 重复协商；
 * 以及协商成为必经一关后的三类行为：
 * 未协商即登录或发业务被明确拒绝(1010)、被拒后补协商仍能继续、协商成功后再协商稳定拒绝。
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

    /** 未协商直接 LOGIN / DATA 都必须被 1010 拒绝：归类专属，不与未登录/版本/能力错误混淆，连接保持。 */
    @Test
    void loginAndBusinessBeforeNegotiationRejectedWithDistinctCode() {
        try (GatewayTestHarness h = new GatewayTestHarness(); LoopbackClient c = h.newClient()) {
            // 1) 未协商直接 LOGIN（凭证本身合法）-> 不进入认证环节，专属错误码 1010
            c.login("device-001", "secret-001", 1);
            ErrorFrames.Parsed loginErr = FrameAsserts.readErrorFrame(c);
            log.info("[未协商LOGIN] -> code={} reason='{}'", loginErr.code(), loginErr.reason());
            assertEquals(GatewayErrorCode.NEGOTIATION_REQUIRED.code(), loginErr.code());
            assertNotEquals(GatewayErrorCode.NOT_AUTHENTICATED.code(), loginErr.code());
            assertNotEquals(GatewayErrorCode.CREDENTIAL_INVALID.code(), loginErr.code());

            // 2) 未协商直接发业务 DATA -> 同样 1010（而不是未登录 2001，也不是版本/能力 1002/1009）
            c.sendRaw(LoopbackClient.frame(MessageType.DATA, 0, 1, "biz".getBytes()));
            ErrorFrames.Parsed dataErr = FrameAsserts.readErrorFrame(c);
            log.info("[未协商DATA] -> code={} reason='{}'", dataErr.code(), dataErr.reason());
            assertEquals(GatewayErrorCode.NEGOTIATION_REQUIRED.code(), dataErr.code());
            assertNotEquals(GatewayErrorCode.NOT_AUTHENTICATED.code(), dataErr.code());
            assertNotEquals(GatewayErrorCode.VERSION_MISMATCH.code(), dataErr.code());
            assertNotEquals(GatewayErrorCode.CAPABILITY_NOT_NEGOTIATED.code(), dataErr.code());

            // 3) 被拒帧不留业务残留：没有任何设备状态被创建/写入
            assertEquals(0, h.states.trackedDevices());

            // 4) 拒绝不结束连接：还能继续读到后续响应（下一条用例验证补协商可恢复）
            assertFalse(c.socket().isClosed());
            assertEquals(0, h.metrics.negotiationsSucceeded());
        }
    }

    /** 同一条连接被 1010 拒绝后，老老实实 HELLO -> LOGIN -> DATA 必须完整走通，无残留状态。 */
    @Test
    void rejectedConnectionRecoversAfterNegotiation() {
        try (GatewayTestHarness h = new GatewayTestHarness(); LoopbackClient c = h.newClient()) {
            // 1) 先越序：LOGIN、DATA 都被 1010 拒掉
            c.login("device-001", "secret-001", 1);
            assertEquals(GatewayErrorCode.NEGOTIATION_REQUIRED.code(),
                    FrameAsserts.readErrorFrame(c).code());
            c.sendRaw(LoopbackClient.frame(MessageType.DATA, 0, 1, "early".getBytes()));
            assertEquals(GatewayErrorCode.NEGOTIATION_REQUIRED.code(),
                    FrameAsserts.readErrorFrame(c).code());

            // 2) 同一条连接补协商 -> 正常 HELLO_ACK（证明没被误判成已协商，也没残留）
            HelloPayload.HelloAck ack = helloAndExpectAck(c, 1, 1, List.of("priority"), 2);
            assertEquals(1, ack.selectedVersion());

            // 3) 协商后登录 -> ACK，设备身份此刻才建立
            c.login("device-001", "secret-001", 3);
            assertEquals(MessageType.ACK.code() & 0xFF, FrameAsserts.readFrameType(c));

            // 4) 业务序号从 1 起正常生效：早先被拒的 DATA seq=1 从未落库
            c.sendRaw(LoopbackClient.frame(MessageType.DATA, FrameConstants.FLAG_PRIORITY, 1, "first".getBytes()));
            assertEquals(MessageType.ACK.code() & 0xFF, FrameAsserts.readFrameType(c));
            assertEquals(1, h.states.get("device-001").appliedCount());
            assertEquals("first", new String(h.states.get("device-001").lastPayload()));
            c.sendRaw(LoopbackClient.frame(MessageType.DATA, FrameConstants.FLAG_PRIORITY, 2, "second".getBytes()));
            assertEquals(MessageType.ACK.code() & 0xFF, FrameAsserts.readFrameType(c));
            assertEquals(2, h.states.get("device-001").appliedCount());
            log.info("[被拒后恢复] 同连接 越序拒绝 -> 协商 -> 登录 -> 业务 完整走通");
        }
    }

    /** 协商成功后（登录前）再发 HELLO 稳定拒绝，已锁定版本与能力不被改写。 */
    @Test
    void renegotiationAfterSuccessRejectedAndProfileLocked() {
        try (GatewayTestHarness h = new GatewayTestHarness(); LoopbackClient c = h.newClient()) {
            helloAndExpectAck(c, 1, 1, List.of("priority"), 1);

            // 再次 HELLO，且试图把版本区间与能力集换掉 -> 2008，连接保持
            c.sendRaw(helloFrame(1, 2, List.of("priority", "header-extension"), 2));
            ErrorFrames.Parsed err = FrameAsserts.readErrorFrame(c);
            log.info("[协商后再协商-登录前] -> code={} reason='{}'", err.code(), err.reason());
            assertEquals(GatewayErrorCode.NEGOTIATION_NOT_ALLOWED.code(), err.code());

            // 已锁定的规则不变：header-extension 仍未授予，reserved 非零仍被 1009 拒
            c.login("device-001", "secret-001", 3);
            assertEquals(MessageType.ACK.code() & 0xFF, FrameAsserts.readFrameType(c));
            c.sendRaw(FrameCodec.encode(MessageType.DATA, (byte) 0, (byte) 0x01, 1, "x".getBytes()));
            ErrorFrames.Parsed err2 = FrameAsserts.readErrorFrame(c);
            assertEquals(GatewayErrorCode.CAPABILITY_NOT_NEGOTIATED.code(), err2.code());

            // 已授予的 priority 照常可用，连接业务不受影响
            c.sendRaw(LoopbackClient.frame(MessageType.DATA, FrameConstants.FLAG_PRIORITY, 1, "ok".getBytes()));
            assertEquals(MessageType.ACK.code() & 0xFF, FrameAsserts.readFrameType(c));
            assertEquals(1, h.metrics.negotiationsSucceeded(), "no second negotiation may succeed");
        }
    }

    /** 已进入业务（已认证）后再发 HELLO 稳定拒绝，规则不变、业务继续；且不牵连并发的其他连接。 */
    @Test
    void renegotiationAfterBusinessStartRejectedButConnectionStable() {
        try (GatewayTestHarness h = new GatewayTestHarness();
             LoopbackClient c = h.newClient();
             LoopbackClient bystander = h.newClient()) {
            c.negotiate(1);
            c.login("device-001", "secret-001", 1);
            assertEquals(MessageType.ACK.code() & 0xFF, FrameAsserts.readFrameType(c));

            // 并发的另一条连接走完自己的协商与登录，与本连接互不干扰
            bystander.negotiate(1);
            bystander.login("device-002", "secret-002", 1);
            assertEquals(MessageType.ACK.code() & 0xFF, FrameAsserts.readFrameType(bystander));

            // 已认证后再协商 -> 稳定拒绝
            c.sendRaw(helloFrame(1, 1, List.of("priority"), 2));
            ErrorFrames.Parsed err = FrameAsserts.readErrorFrame(c);
            log.info("[中途协商] 已认证后 HELLO -> code={} reason='{}'", err.code(), err.reason());
            assertEquals(GatewayErrorCode.NEGOTIATION_NOT_ALLOWED.code(), err.code());

            // 本连接会话规则不变、业务照常
            c.sendRaw(LoopbackClient.frame(MessageType.DATA, 0, 1, "ok".getBytes()));
            assertEquals(MessageType.ACK.code() & 0xFF, FrameAsserts.readFrameType(c));

            // 旁观连接的业务完全不受影响
            bystander.sendRaw(LoopbackClient.frame(MessageType.DATA, 0, 1, "fine".getBytes()));
            assertEquals(MessageType.ACK.code() & 0xFF, FrameAsserts.readFrameType(bystander));
            assertEquals(1, h.states.get("device-001").appliedCount());
            assertEquals(1, h.states.get("device-002").appliedCount());
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
