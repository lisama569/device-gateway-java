package com.github.lisama569.devicegatewayjava.tests;

import com.github.lisama569.devicegatewayjava.error.GatewayErrorCode;
import com.github.lisama569.devicegatewayjava.protocol.ErrorFrames;
import com.github.lisama569.devicegatewayjava.protocol.MessageType;
import com.github.lisama569.devicegatewayjava.support.FrameAsserts;
import com.github.lisama569.devicegatewayjava.support.GatewayTestHarness;
import com.github.lisama569.devicegatewayjava.support.LoopbackClient;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/** 上行序号：重复不生效、跳号乱序拒绝、窗口外回退拒绝（过期）、重连续号。 */
class SequenceTest {

    private static final Logger log = LoggerFactory.getLogger(SequenceTest.class);

    private LoopbackClient login(GatewayTestHarness h, String device, String secret) {
        LoopbackClient c = h.newClient();
        c.login(device, secret, 1);
        assertEquals(MessageType.ACK.code(), FrameAsserts.readFrameType(c));
        return c;
    }

    private LoopbackClient login(GatewayTestHarness h, String device) {
        return login(h, device, "secret-" + device.substring(device.indexOf('-') + 1));
    }

    private void data(LoopbackClient c, long seq, String body) {
        c.sendRaw(LoopbackClient.frame(MessageType.DATA, 0, seq, body.getBytes()));
    }

    @Test
    void duplicateDoesNotReapplyAndAckSequenceContinues() {
        try (GatewayTestHarness h = new GatewayTestHarness(); LoopbackClient c = login(h, "device-001")) {
            data(c, 1, "first");
            assertEquals(MessageType.ACK.code(), FrameAsserts.readFrameType(c));

            data(c, 1, "first-DUP");
            ErrorFrames.Parsed dup = FrameAsserts.readErrorFrame(c);
            log.info("[重复] seq=1 再达 -> code={} reason='{}'", dup.code(), dup.reason());
            assertEquals(GatewayErrorCode.DUPLICATE_MESSAGE.code(), dup.code());

            // 业务状态只生效一次，且保留首次负载
            assertEquals(1, h.states.get("device-001").appliedCount());
            assertEquals("first", new String(h.states.get("device-001").lastPayload()));

            data(c, 2, "second");
            assertEquals(MessageType.ACK.code(), FrameAsserts.readFrameType(c));
            assertEquals(2, h.states.get("device-001").appliedCount());
        }
    }

    @Test
    void gapAndRollbackOutOfOrderRejectedNotSilentlyDropped() {
        try (GatewayTestHarness h = new GatewayTestHarness(); LoopbackClient c = login(h, "device-001")) {
            data(c, 1, "a");
            FrameAsserts.readFrameType(c);

            // 跳号：直接发 3（期望 2）
            data(c, 3, "c");
            ErrorFrames.Parsed gap = FrameAsserts.readErrorFrame(c);
            log.info("[跳号] seq=3(期望2) -> code={} reason='{}'", gap.code(), gap.reason());
            assertEquals(GatewayErrorCode.SEQ_OUT_OF_ORDER.code(), gap.code());
            assertEquals(1, h.states.get("device-001").appliedCount(), "gap frame must not apply");

            // 正确的 2 仍然生效
            data(c, 2, "b");
            assertEquals(MessageType.ACK.code(), FrameAsserts.readFrameType(c));
            assertEquals(2, h.states.get("device-001").appliedCount());

            // 回退发 1：还在窗口内（window=8），判重复
            data(c, 1, "old");
            ErrorFrames.Parsed back = FrameAsserts.readErrorFrame(c);
            log.info("[窗口内回退] seq=1 -> code={} reason='{}'", back.code(), back.reason());
            assertEquals(GatewayErrorCode.DUPLICATE_MESSAGE.code(), back.code());
        }
    }

    @Test
    void rollbackBeyondWindowExpires() {
        try (GatewayTestHarness h = new GatewayTestHarness(); LoopbackClient c = login(h, "device-001")) {
            for (long s = 1; s <= 10; s++) {
                data(c, s, ("d" + s));
                FrameAsserts.readFrameType(c);
            }
            // seq=1 已滑出 window=8（lastApplied=10）
            data(c, 1, "ancient");
            ErrorFrames.Parsed expired = FrameAsserts.readErrorFrame(c);
            log.info("[窗口外回退] seq=1(lastApplied=10,window=8) -> code={} reason='{}'",
                    expired.code(), expired.reason());
            assertEquals(GatewayErrorCode.SEQ_OUT_OF_ORDER.code(), expired.code());
            assertTrue(expired.reason().contains("window"));
            assertEquals(10, h.states.get("device-001").appliedCount());
        }
    }

    @Test
    void reconnectReusesDeviceStateAndContinuesSequence() throws Exception {
        try (GatewayTestHarness h = new GatewayTestHarness()) {
            try (LoopbackClient c1 = login(h, "device-002")) {
                data(c1, 1, "on-conn-1");
                FrameAsserts.readFrameType(c1);
            } // 断开（未 LOGOUT）

            // 等待服务端读循环感知旧连接 EOF 并清理（轮询，避免时序抖动）
            long deadline = System.currentTimeMillis() + 2000;
            while (System.currentTimeMillis() < deadline
                    && h.sessions.get("device-002").map(s -> !s.closed()).orElse(false)) {
                Thread.sleep(10);
            }
            try (LoopbackClient c2 = login(h, "device-002")) {
                // 旧状态延续：seq=1 仍是重复，seq=2 才生效
                data(c2, 1, "dup-after-reconnect");
                ErrorFrames.Parsed dup = FrameAsserts.readErrorFrame(c2);
                assertEquals(GatewayErrorCode.DUPLICATE_MESSAGE.code(), dup.code());
                log.info("[重连] seq=1 跨连接重复判定: code={}（状态延续，无串号）", dup.code());

                data(c2, 2, "on-conn-2");
                assertEquals(MessageType.ACK.code(), FrameAsserts.readFrameType(c2));
                assertEquals(2, h.states.get("device-002").appliedCount());
            }
        }
    }
}
