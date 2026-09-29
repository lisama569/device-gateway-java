package com.github.lisama569.devicegatewayjava.tests;

import com.github.lisama569.devicegatewayjava.config.GatewayProperties;
import com.github.lisama569.devicegatewayjava.error.GatewayErrorCode;
import com.github.lisama569.devicegatewayjava.protocol.ErrorFrames;
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

/** 半包长挂连接：残留超按连接硬上限 -> 4003 + 仅结束本连接；指标/管理面可见、其他连接不受影响。 */
class ReceiveBufferOverrunIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ReceiveBufferOverrunIntegrationTest.class);

    private static GatewayTestHarness harness() {
        GatewayProperties p = GatewayTestHarness.defaultProps();
        p.setMaxFrameLength(512);
        p.setMaxReceiveResidualBytes(64); // 残留上限故意小于合法最长帧，专门触发容量保护
        return new GatewayTestHarness(p, new ManualTaskScheduler(),
                Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC));
    }

    /** 合法 MAGIC + v1 + DATA 头，声明 payload=256（<= 单帧上限 512，声明本身合法）。 */
    private static byte[] halfFrameHeader() {
        return new byte[]{(byte) 0xA5, 0x5A, 1, 0x10, 0, 0, 0, 0, 0, 0, 0, 0, 0x01, 0x00};
    }

    @Test
    void halfOpenConnectionOverResidualCapGets4003AndTerminated() {
        try (GatewayTestHarness h = harness()) {
            // 一条正常连接（证明保护只影响越界连接）
            try (LoopbackClient good = h.newClient()) {
                good.login("device-001", "secret-001", 1);
                assertEquals(MessageType.ACK.code(), FrameAsserts.readFrameType(good));

                LoopbackClient bad = h.newClient();
                bad.sendRaw(halfFrameHeader());
                bad.sendRaw(new byte[60]); // 残留 14+60=74 > 64

                ErrorFrames.Parsed err = FrameAsserts.readErrorFrame(bad);
                log.info("[残留超限-回环] 半包残留=74 上限=64 -> code={} reason='{}'",
                        err.code(), err.reason());
                assertEquals(GatewayErrorCode.RECEIVE_BUFFER_FULL.code(), err.code());
                assertTrue(err.reason().contains("74") && err.reason().contains("64"),
                        "判定依据应含残留数与上限: " + err.reason());

                IllegalStateException eof = assertThrows(IllegalStateException.class, () -> bad.readN(1));
                assertTrue(eof.getMessage().contains("EOF"));
                bad.close();

                assertEquals(1, h.metrics.receiveBufferTerminations());
                assertTrue(h.metrics.errorCount(GatewayErrorCode.RECEIVE_BUFFER_FULL.code()) >= 1);
                assertEquals(64L, h.metrics.snapshot().get("receiveResidualLimitBytes"));
                assertEquals(1L, h.metrics.snapshot().get("receiveBufferTerminations"));

                // 既存连接在越界连接被结束后仍正常
                good.sendRaw(LoopbackClient.frame(MessageType.DATA, 0, 1, "still-ok".getBytes()));
                assertEquals(MessageType.ACK.code(), FrameAsserts.readFrameType(good));
                log.info("[残留超限-指标] terminations={} errorCount={}",
                        h.metrics.receiveBufferTerminations(),
                        h.metrics.errorCount(GatewayErrorCode.RECEIVE_BUFFER_FULL.code()));
            }
        }
    }

    @Test
    void otherAndNewConnectionsUnaffectedAfterTermination() {
        try (GatewayTestHarness h = harness(); LoopbackClient good = h.newClient()) {
            good.login("device-002", "secret-002", 1);
            assertEquals(MessageType.ACK.code(), FrameAsserts.readFrameType(good));

            LoopbackClient bad = h.newClient();
            bad.sendRaw(halfFrameHeader());
            bad.sendRaw(new byte[60]);
            assertEquals(GatewayErrorCode.RECEIVE_BUFFER_FULL.code(),
                    FrameAsserts.readErrorFrame(bad).code());
            assertThrows(IllegalStateException.class, () -> bad.readN(1));
            bad.close();

            // 既存连接继续
            good.sendRaw(LoopbackClient.frame(MessageType.DATA, 0, 1, "alive".getBytes()));
            assertEquals(MessageType.ACK.code(), FrameAsserts.readFrameType(good));
            // 新连接也能正常接入
            try (LoopbackClient later = h.newClient()) {
                later.login("device-001", "secret-001", 1);
                assertEquals(MessageType.ACK.code(), FrameAsserts.readFrameType(later));
                log.info("[残留超限-隔离] 越界连接结束后，既存连接与新连接均正常");
            }
        }
    }
}
