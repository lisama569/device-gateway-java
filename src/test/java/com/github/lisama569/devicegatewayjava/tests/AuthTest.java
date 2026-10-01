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

/** 接入身份校验：4 类拒绝原因可区分；拒绝会话不触达业务状态；凭证不回显。 */
class AuthTest {

    private static final Logger log = LoggerFactory.getLogger(AuthTest.class);

    private static ErrorFrames.Parsed loginAndExpectError(GatewayTestHarness h,
                                                          String deviceId, String secret, long seq) {
        try (LoopbackClient c = h.newClient()) {
            c.negotiate(); // 协商前置：先合法过协商，再验证认证结论
            c.login(deviceId, secret, seq);
            ErrorFrames.Parsed err = FrameAsserts.readErrorFrame(c);
            log.info("[认证] deviceId='{}' secret='{}' seq={} -> code={} reason='{}'",
                    deviceId, secret, seq, err.code(), err.reason());
            return err;
        }
    }

    @Test
    void missingInvalidExpiredAreDistinguishable() {
        try (GatewayTestHarness h = new GatewayTestHarness()) {
            // 1) 凭证缺失：空 secret
            ErrorFrames.Parsed missing = loginAndExpectError(h, "device-001", "", 1);
            assertEquals(GatewayErrorCode.CREDENTIAL_MISSING.code(), missing.code());

            // 2) 凭证无效：错误 secret
            ErrorFrames.Parsed invalid = loginAndExpectError(h, "device-001", "wrong-secret", 1);
            assertEquals(GatewayErrorCode.CREDENTIAL_INVALID.code(), invalid.code());
            FrameAsserts.assertReasonNoSecret(invalid, "secret-001");
            FrameAsserts.assertReasonNoSecret(invalid, "wrong-secret");

            // 3) 设备不存在也归为无效（不泄露设备是否注册）
            ErrorFrames.Parsed unknown = loginAndExpectError(h, "ghost", "whatever", 1);
            assertEquals(GatewayErrorCode.CREDENTIAL_INVALID.code(), unknown.code());

            // 4) 已失效
            ErrorFrames.Parsed expired = loginAndExpectError(h, "device-expired", "old-secret", 1);
            assertEquals(GatewayErrorCode.CREDENTIAL_EXPIRED.code(), expired.code());
            FrameAsserts.assertReasonNoSecret(expired, "old-secret");

            log.info("[认证] 四类拒绝 code 分别为 missing={}, invalid={}, expired={}",
                    missing.code(), invalid.code(), expired.code());
        }
    }

    @Test
    void duplicateLoginSameConnectionRejected() {
        try (GatewayTestHarness h = new GatewayTestHarness(); LoopbackClient c = h.newClient()) {
            c.negotiate();
            c.login("device-001", "secret-001", 1);
            int t1 = FrameAsserts.readFrameType(c);
            assertEquals(MessageType.ACK.code(), t1, "first login acked");

            c.login("device-001", "secret-001", 2);
            ErrorFrames.Parsed err = FrameAsserts.readErrorFrame(c);
            log.info("[重复登录-同连接] seq=2 -> code={} reason='{}'", err.code(), err.reason());
            assertEquals(GatewayErrorCode.ALREADY_AUTHENTICATED.code(), err.code());
        }
    }

    @Test
    void duplicateLoginNewConnectionKicksOld() {
        try (GatewayTestHarness h = new GatewayTestHarness();
             LoopbackClient c1 = h.newClient();
             LoopbackClient c2 = h.newClient()) {
            c1.negotiate();
            c1.login("device-001", "secret-001", 1);
            assertEquals(MessageType.ACK.code(), FrameAsserts.readFrameType(c1));

            c2.negotiate();
            c2.login("device-001", "secret-001", 1);
            assertEquals(MessageType.ACK.code(), FrameAsserts.readFrameType(c2));

            // 旧连接应被关闭（读 EOF）
            byte[] hdr = c1.readN(1); // 旧连接可能先收到 SESSION_CONFLICT? 设计为直接关闭
            // 设计：旧会话被踢直接关 socket -> readN 抛 EOF 异常
            fail("old connection should have been closed, got byte: " + (hdr[0] & 0xFF));
        } catch (IllegalStateException expectedEof) {
            log.info("[重复登录-跨连接] 旧连接按 SESSION_CONFLICT 被踢并关闭: {}",
                    expectedEof.getMessage());
            assertTrue(expectedEof.getMessage().contains("EOF"));
        }
    }

    @Test
    void rejectedSessionCannotWriteBusinessState() {
        try (GatewayTestHarness h = new GatewayTestHarness(); LoopbackClient c = h.newClient()) {
            c.negotiate();
            // 已协商但未登录直接发 DATA -> NOT_AUTHENTICATED
            c.sendRaw(LoopbackClient.frame(MessageType.DATA, 0, 1, "hello".getBytes()));
            ErrorFrames.Parsed err = FrameAsserts.readErrorFrame(c);
            log.info("[未认证DATA] -> code={} reason='{}'", err.code(), err.reason());
            assertEquals(GatewayErrorCode.NOT_AUTHENTICATED.code(), err.code());

            // 认证失败后再发 DATA，仍是 NOT_AUTHENTICATED，业务状态为零
            c.login("device-001", "bad", 2);
            ErrorFrames.Parsed loginErr = FrameAsserts.readErrorFrame(c);
            assertEquals(GatewayErrorCode.CREDENTIAL_INVALID.code(), loginErr.code());
            c.sendRaw(LoopbackClient.frame(MessageType.DATA, 0, 1, "hello2".getBytes()));
            ErrorFrames.Parsed err2 = FrameAsserts.readErrorFrame(c);
            assertEquals(GatewayErrorCode.NOT_AUTHENTICATED.code(), err2.code());

            // 用正确凭证登录另一连接，业务计数从 0 开始，证明之前的 DATA 未生效
            try (LoopbackClient ok = h.newClient()) {
                ok.negotiate();
                ok.login("device-001", "secret-001", 1);
                FrameAsserts.readFrameType(ok);
                ok.sendRaw(LoopbackClient.frame(MessageType.DATA, 0, 1, "first".getBytes()));
                assertEquals(MessageType.ACK.code(), FrameAsserts.readFrameType(ok));
                log.info("[拒绝不触达状态] 合法首帧 seq=1 生效, appliedCount={}",
                        h.states.get("device-001").appliedCount());
                assertEquals(1, h.states.get("device-001").appliedCount());
            }
        }
    }
}
