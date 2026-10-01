package com.github.lisama569.devicegatewayjava.tests;

import com.github.lisama569.devicegatewayjava.dispatch.DownlinkService;
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

import static org.junit.jupiter.api.Assertions.*;

/** 下行可靠投递：确认丢失->超时重传（有上限）、重复确认、封顶终止会话。全部手动时钟驱动。 */
class ReliabilityTest {

    private static final Logger log = LoggerFactory.getLogger(ReliabilityTest.class);

    private GatewayTestHarness harnessWith(ManualTaskScheduler clock) {
        return new GatewayTestHarness(GatewayTestHarness.defaultProps(), clock,
                java.time.Clock.fixed(java.time.Instant.parse("2026-09-01T00:00:00Z"),
                        java.time.ZoneOffset.UTC));
    }

    private LoopbackClient login(GatewayTestHarness h, String device, String secret) {
        LoopbackClient c = h.newClient();
        c.negotiate();
        c.login(device, secret, 1);
        FrameAsserts.readFrameType(c); // ACK
        return c;
    }

    /** 读取一个帧，返回类型；区分 DOWNLINK。 */
    private int recvType(LoopbackClient c) {
        return FrameAsserts.readFrame(c).type();
    }

    @Test
    void acknowledgedDownlinkStopsRetries() {
        ManualTaskScheduler clock = new ManualTaskScheduler();
        try (GatewayTestHarness h = harnessWith(clock);
             LoopbackClient c = login(h, "device-001", "secret-001")) {
            DownlinkService.Result r = h.downlink.sendReliable("device-001", "cmd".getBytes());
            assertTrue(r.accepted(), r.detail());
            assertEquals(MessageType.DOWNLINK.code(), recvType(c), "first copy delivered");

            // 设备确认
            c.sendRaw(LoopbackClient.frame(MessageType.ACK, 0, 1, new byte[]{0, 0, 0, 1}));
            clock.advance(5000); // 远超超时，确认后不应再重传
            log.info("[ACK正常] downlink seq=1 已确认，推进 5000ms 无重传，pending={}",
                    h.reliable.pendingCount("c1"));
            assertEquals(0, h.reliable.pendingCount("c1"));
        }
    }

    @Test
    void lostAckCausesRetriesUpToLimitThenTerminates() {
        ManualTaskScheduler clock = new ManualTaskScheduler();
        try (GatewayTestHarness h = harnessWith(clock);
             LoopbackClient c = login(h, "device-001", "secret-001")) {
            h.downlink.sendReliable("device-001", "cmd".getBytes());

            int copies = 1;
            assertEquals(MessageType.DOWNLINK.code(), recvType(c)); // 第 1 份

            // maxRetries=2 -> 超时后应有第 2、第 3 份
            clock.advance(1000);
            assertEquals(MessageType.DOWNLINK.code(), recvType(c));
            copies++;
            clock.advance(1000);
            assertEquals(MessageType.DOWNLINK.code(), recvType(c));
            copies++;

            log.info("[重传上限] ACK 全程丢失，共收到 {} 份 (=1+maxRetries=2)", copies);
            assertEquals(3, copies);

            // 再推进一个超时窗口：不再发送（attempts>maxRetries 时终止而非再排定时器）
            clock.advance(1000);
            assertEquals(1, h.metrics.snapshot().get("sessionsTerminatedByRetry"),
                    "session must be terminated with RETRY_EXHAUSTED");

            // 连接应被服务端关闭 -> 读 EOF
            assertThrows(IllegalStateException.class, () -> c.readN(1));
            log.info("[重传上限] 会话已按 code={} 终止且连接关闭",
                    GatewayErrorCode.RETRY_EXHAUSTED.code());
        }
    }

    @Test
    void duplicateAckIsRejectedDeterministically() {
        ManualTaskScheduler clock = new ManualTaskScheduler();
        try (GatewayTestHarness h = harnessWith(clock);
             LoopbackClient c = login(h, "device-001", "secret-001")) {
            h.downlink.sendReliable("device-001", "x".getBytes());
            recvType(c);

            c.sendRaw(LoopbackClient.frame(MessageType.ACK, 0, 1, new byte[]{0, 0, 0, 1}));
            c.sendRaw(LoopbackClient.frame(MessageType.ACK, 0, 1, new byte[]{0, 0, 0, 1}));

            // 第一份 ACK 生效（无响应帧），第二份重复 -> 错误帧
            ErrorFrames.Parsed err = FrameAsserts.readErrorFrame(c);
            log.info("[重复ACK] seq=1 第二次确认 -> code={} reason='{}'", err.code(), err.reason());
            assertEquals(GatewayErrorCode.DUPLICATE_MESSAGE.code(), err.code());
        }
    }

    @Test
    void ackForUnknownSeqRejected() {
        ManualTaskScheduler clock = new ManualTaskScheduler();
        try (GatewayTestHarness h = harnessWith(clock);
             LoopbackClient c = login(h, "device-001", "secret-001")) {
            c.sendRaw(LoopbackClient.frame(MessageType.ACK, 0, 999, new byte[]{0, 0, 3, (byte) 231}));
            ErrorFrames.Parsed err = FrameAsserts.readErrorFrame(c);
            log.info("[未知ACK] seq=999 -> code={} reason='{}'", err.code(), err.reason());
            assertEquals(GatewayErrorCode.SEQ_OUT_OF_ORDER.code(), err.code());
        }
    }
}
