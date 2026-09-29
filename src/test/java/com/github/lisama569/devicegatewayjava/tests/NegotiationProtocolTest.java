package com.github.lisama569.devicegatewayjava.tests;

import com.github.lisama569.devicegatewayjava.error.GatewayErrorCode;
import com.github.lisama569.devicegatewayjava.negotiation.NegotiationFrames;
import com.github.lisama569.devicegatewayjava.negotiation.NegotiationOutcome;
import com.github.lisama569.devicegatewayjava.protocol.ErrorFrames;
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
 * 能力协商全流程（仅 127.0.0.1，时序确定）：
 * 谈成 / 版本无交集 / 能力不识别 / 协商后越权使用能力被拒且连接不中断 /
 * 定稿后重协商被拒 / 未协商业务帧被拒但仍可回头协商。
 */
class NegotiationProtocolTest {

    private static final Logger log = LoggerFactory.getLogger(NegotiationProtocolTest.class);

    @Test
    void negotiationSucceedsThenLoginAndDataFlow() {
        try (GatewayTestHarness h = new GatewayTestHarness(); LoopbackClient c = h.newClient()) {
            FrameAsserts.RawFrame ack = c.negotiateExpectAck(0, 1, 1, 1, 2);
            assertEquals(NegotiationOutcome.Status.AGREED,
                    NegotiationFrames.parseAckStatus(ack.payload()));
            assertEquals(1, NegotiationFrames.parseAckSelectedVersion(ack.payload()));
            assertEquals(List.of((byte) 1, (byte) 2),
                    NegotiationFrames.parseAckCapabilities(ack.payload()));
            log.info("[协商成功] status=AGREED version=1 caps=[1,2]，指标 succeeded={}",
                    h.metrics.negotiationsSucceeded());
            assertEquals(1, h.metrics.negotiationsSucceeded());

            // 协商后正常登录 + DATA
            c.sendRaw(LoopbackClient.frame(MessageType.LOGIN, 0, 1,
                    LoopbackClient.loginPayload("device-001", "secret-001")));
            assertEquals(MessageType.ACK.code(), FrameAsserts.readFrameType(c));
            c.sendRaw(LoopbackClient.frame(MessageType.DATA, 0, 1, "ok".getBytes()));
            assertEquals(MessageType.ACK.code(), FrameAsserts.readFrameType(c));
            assertEquals(1, h.states.get("device-001").appliedCount());

            // 谈定能力（COMPRESSION=0x10）可用
            c.sendRaw(LoopbackClient.frame(MessageType.DATA, 0x10, 2, "zip".getBytes()));
            assertEquals(MessageType.ACK.code(), FrameAsserts.readFrameType(c));
        }
    }

    @Test
    void noVersionIntersectionClosesConnectionWithDistinctCode() {
        try (GatewayTestHarness h = new GatewayTestHarness(); LoopbackClient c = h.newClient()) {
            c.sendNegotiate(0, 2, 3); // 网关只支持 1
            FrameAsserts.RawFrame ack = FrameAsserts.readFrame(c);
            assertEquals(MessageType.NEGOTIATE_ACK.code(), ack.type());
            assertEquals(NegotiationOutcome.Status.VERSION_NO_INTERSECTION,
                    NegotiationFrames.parseAckStatus(ack.payload()));
            ErrorFrames.Parsed err = FrameAsserts.readErrorFrame(c);
            log.info("[版本无交集] ackStatus=VERSION_NO_INTERSECTION errorCode={} reason='{}'",
                    err.code(), err.reason());
            assertEquals(GatewayErrorCode.VERSION_MISMATCH.code(), err.code());
            assertEquals(1, h.metrics.negotiationsVersionFailed());

            // 连接随后被关闭（EOF），且设备从未注册成会话
            assertConnectionClosed(c);
            assertEquals(0, h.sessions.activeCount());
        }
    }

    @Test
    void unknownCapabilityClosesConnectionWithDistinctCode() {
        try (GatewayTestHarness h = new GatewayTestHarness(); LoopbackClient c = h.newClient()) {
            c.sendNegotiate(0, 1, 1, 99); // 99 不可识别
            FrameAsserts.RawFrame ack = FrameAsserts.readFrame(c);
            assertEquals(MessageType.NEGOTIATE_ACK.code(), ack.type());
            assertEquals(NegotiationOutcome.Status.UNSUPPORTED_CAPABILITY,
                    NegotiationFrames.parseAckStatus(ack.payload()));
            ErrorFrames.Parsed err = FrameAsserts.readErrorFrame(c);
            log.info("[能力不识别] ackStatus=UNSUPPORTED_CAPABILITY errorCode={} reason='{}'",
                    err.code(), err.reason());
            assertEquals(GatewayErrorCode.UNSUPPORTED_CAPABILITY.code(), err.code());
            assertTrue(err.reason().contains("99"), "判定依据应列出未知 code");
            assertEquals(1, h.metrics.negotiationsCapabilityFailed());
            assertConnectionClosed(c);
        }
    }

    @Test
    void capabilityViolationRejectedPerFrameButConnectionSurvives() {
        try (GatewayTestHarness h = new GatewayTestHarness(); LoopbackClient c = h.newClient()) {
            c.login("device-001", "secret-001", 1); // 默认协商 caps=[1,2]
            assertEquals(MessageType.ACK.code(), FrameAsserts.readFrameType(c));

            // 越权：使用未谈定的 FLAGS 位 0x40 -> 2009
            c.sendRaw(LoopbackClient.frame(MessageType.DATA, 0x40, 1, "x".getBytes()));
            ErrorFrames.Parsed err = FrameAsserts.readErrorFrame(c);
            log.info("[越权能力] flags=0x40 -> code={} reason='{}'", err.code(), err.reason());
            assertEquals(GatewayErrorCode.CAPABILITY_VIOLATION.code(), err.code());
            assertEquals(0, h.states.get("device-001").appliedCount(), "越权帧不得生效");

            // 连接活着：越权帧的序号未被消费，seq=1 的合法重发正常受理
            c.sendRaw(LoopbackClient.frame(MessageType.DATA, 0, 1, "y".getBytes()));
            assertEquals(MessageType.ACK.code(), FrameAsserts.readFrameType(c),
                    "同连接后续合法报文必须继续正常");
            assertEquals(1, h.states.get("device-001").appliedCount());

            // 未协商的扩展字段（RESERVED != 0）在解码层即按 1006 拒绝，连接同样保留
            byte[] reserved = LoopbackClient.frame(MessageType.DATA, 0, 2, "z".getBytes());
            reserved[5] = 0x01;
            int crcOff = reserved.length - 2;
            int crc = com.github.lisama569.devicegatewayjava.protocol.Crc16.checksum(reserved, 0, crcOff);
            reserved[crcOff] = (byte) (crc >>> 8);
            reserved[crcOff + 1] = (byte) crc;
            c.sendRaw(reserved);
            ErrorFrames.Parsed err2 = FrameAsserts.readErrorFrame(c);
            log.info("[保留位扩展] reserved=0x01 -> code={}", err2.code());
            assertEquals(GatewayErrorCode.MALFORMED_PAYLOAD.code(), err2.code());
            c.sendRaw(LoopbackClient.frame(MessageType.DATA, 0, 2, "w".getBytes()));
            assertEquals(MessageType.ACK.code(), FrameAsserts.readFrameType(c));
        }
    }

    @Test
    void renegotiationAfterSettledIsRejectedStably() {
        try (GatewayTestHarness h = new GatewayTestHarness(); LoopbackClient c = h.newClient()) {
            c.login("device-001", "secret-001", 1);
            assertEquals(MessageType.ACK.code(), FrameAsserts.readFrameType(c));

            // 业务阶段再次发起协商 -> 2010，规则不变
            for (int attempt = 1; attempt <= 2; attempt++) {
                c.sendNegotiate(100 + attempt, 1, 1, 1);
                ErrorFrames.Parsed err = FrameAsserts.readErrorFrame(c);
                log.info("[中途协商] 第{}次 -> code={}", attempt, err.code());
                assertEquals(GatewayErrorCode.NEGOTIATION_SETTLED.code(), err.code());
            }
            // 原规则仍在：DATA 严格递增照常
            c.sendRaw(LoopbackClient.frame(MessageType.DATA, 0, 1, "still-v1".getBytes()));
            assertEquals(MessageType.ACK.code(), FrameAsserts.readFrameType(c));
            assertEquals(1, h.states.get("device-001").appliedCount());
        }
    }

    @Test
    void businessBeforeNegotiationRejectedButNegotiationStillPossible() {
        try (GatewayTestHarness h = new GatewayTestHarness(); LoopbackClient c = h.newClient()) {
            c.sendRaw(LoopbackClient.frame(MessageType.DATA, 0, 1, "early".getBytes()));
            ErrorFrames.Parsed err = FrameAsserts.readErrorFrame(c);
            assertEquals(GatewayErrorCode.NEGOTIATION_REQUIRED.code(), err.code());
            log.info("[先业务后协商] 提前 DATA -> 2008，连接保留，随后允许回头协商");

            // 回头完成协商、登录、数据，全链路可用
            c.negotiateExpectAck(0, 1, 1);
            c.sendRaw(LoopbackClient.frame(MessageType.LOGIN, 0, 1,
                    LoopbackClient.loginPayload("device-001", "secret-001")));
            assertEquals(MessageType.ACK.code(), FrameAsserts.readFrameType(c));
            c.sendRaw(LoopbackClient.frame(MessageType.DATA, 0, 1, "late".getBytes()));
            assertEquals(MessageType.ACK.code(), FrameAsserts.readFrameType(c));
        }
    }

    @Test
    void malformedNegotiationClosesConnection() {
        try (GatewayTestHarness h = new GatewayTestHarness(); LoopbackClient c = h.newClient()) {
            byte[] bad = LoopbackClient.frame(MessageType.NEGOTIATE, 0, 0, new byte[]{1});
            c.sendRaw(bad);
            ErrorFrames.Parsed err = FrameAsserts.readErrorFrame(c);
            log.info("[协商畸形] -> code={}", err.code());
            assertEquals(GatewayErrorCode.MALFORMED_PAYLOAD.code(), err.code());
            assertConnectionClosed(c);
        }
    }

    private static void assertConnectionClosed(LoopbackClient c) {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> c.readN(1),
                "failed negotiation must close the connection");
        assertTrue(e.getMessage().contains("EOF"), e.getMessage());
    }
}
