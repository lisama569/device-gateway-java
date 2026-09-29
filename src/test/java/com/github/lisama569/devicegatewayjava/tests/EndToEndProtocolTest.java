package com.github.lisama569.devicegatewayjava.tests;

import com.github.lisama569.devicegatewayjava.error.GatewayErrorCode;
import com.github.lisama569.devicegatewayjava.protocol.ErrorFrames;
import com.github.lisama569.devicegatewayjava.protocol.MessageType;
import com.github.lisama569.devicegatewayjava.support.FrameAsserts;
import com.github.lisama569.devicegatewayjava.support.GatewayTestHarness;
import com.github.lisama569.devicegatewayjava.support.LoopbackClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/** 真实回环 TCP 上：粘包/逐字节半包、坏帧后合法帧继续、未知类型可区分拒绝。 */
@Timeout(15)
class EndToEndProtocolTest {

    private static final Logger log = LoggerFactory.getLogger(EndToEndProtocolTest.class);

    @Test
    void stickyAndByteByByteFramesOverTcp() {
        try (GatewayTestHarness h = new GatewayTestHarness(); LoopbackClient c = h.newClient()) {
            // 粘包：NEGOTIATE + LOGIN + DATA1 + DATA2 拼成一个字节串发送（登录用 seq=1000 避免与数据序号冲突）
            byte[] neg = LoopbackClient.frame(MessageType.NEGOTIATE, 0, 999,
                    LoopbackClient.negotiatePayload(1, 1, 1, 2));
            byte[] login = LoopbackClient.frame(MessageType.LOGIN, 0, 1000,
                    LoopbackClient.loginPayload("device-001", "secret-001"));
            byte[] d1 = LoopbackClient.frame(MessageType.DATA, 0, 1, "a".getBytes());
            byte[] d2 = LoopbackClient.frame(MessageType.DATA, 0, 2, "bb".getBytes());
            byte[] blob = new byte[neg.length + login.length + d1.length + d2.length];
            int o = 0;
            System.arraycopy(neg, 0, blob, o, neg.length); o += neg.length;
            System.arraycopy(login, 0, blob, o, login.length); o += login.length;
            System.arraycopy(d1, 0, blob, o, d1.length); o += d1.length;
            System.arraycopy(d2, 0, blob, o, d2.length);
            c.sendRaw(blob);
            log.info("[E2E粘包] 一次写入 {} 字节(4帧粘连)", blob.length);

            assertEquals(MessageType.NEGOTIATE_ACK.code(), FrameAsserts.readFrameType(c)); // negotiate
            assertEquals(MessageType.ACK.code(), FrameAsserts.readFrameType(c)); // login
            assertEquals(MessageType.ACK.code(), FrameAsserts.readFrameType(c)); // data1
            assertEquals(MessageType.ACK.code(), FrameAsserts.readFrameType(c)); // data2
            assertEquals(2, h.states.get("device-001").appliedCount());

            // 逐字节半包发送 DATA3
            byte[] d3 = LoopbackClient.frame(MessageType.DATA, 0, 3, "ccc".getBytes());
            c.sendByteByByte(d3);
            assertEquals(MessageType.ACK.code(), FrameAsserts.readFrameType(c));
            assertEquals(3, h.states.get("device-001").appliedCount());
            log.info("[E2E半包] DATA3 逐字节发送共 {} 次写入后正常生效", d3.length);
        }
    }

    @Test
    void corruptFrameThenValidFrameSameConnection() {
        try (GatewayTestHarness h = new GatewayTestHarness(); LoopbackClient c = h.newClient()) {
            c.login("device-001", "secret-001", 1000);
            FrameAsserts.readFrameType(c);

            // 发送一帧 CRC 被破坏的 DATA(1)，紧跟一帧合法 DATA(1)
            byte[] bad = LoopbackClient.frame(MessageType.DATA, 0, 1, "x".getBytes());
            bad[bad.length - 1] ^= (byte) 0xFF;
            byte[] good = LoopbackClient.frame(MessageType.DATA, 0, 1, "y".getBytes());
            byte[] both = new byte[bad.length + good.length];
            System.arraycopy(bad, 0, both, 0, bad.length);
            System.arraycopy(good, 0, both, bad.length, good.length);
            c.sendRaw(both);

            ErrorFrames.Parsed crcErr = FrameAsserts.readErrorFrame(c);
            log.info("[E2E坏帧] CRC 失败 -> code={} reason='{}'", crcErr.code(), crcErr.reason());
            assertEquals(GatewayErrorCode.CRC_MISMATCH.code(), crcErr.code());

            // 重同步过程中可能还伴随 MAGIC_MISMATCH 错误帧，排空所有错误帧后读到 ACK
            int extraErrors = 0;
            while (true) {
                FrameAsserts.RawFrame rf = FrameAsserts.readFrame(c);
                if (rf.type() == MessageType.ACK.code()) break;
                if (rf.type() == 0x7F) {
                    extraErrors++;
                    continue;
                }
                fail("unexpected frame type 0x" + Integer.toHexString(rf.type()));
                return;
            }
            assertEquals("y", new String(h.states.get("device-001").lastPayload()));
            log.info("[E2E坏帧恢复] 同连接合法帧 seq=1 生效，连接未受影响(额外重同步错误帧={})", extraErrors);
        }
    }

    @Test
    void unknownMessageTypeRejectedDistinctlyAndServiceContinues() {
        try (GatewayTestHarness h = new GatewayTestHarness(); LoopbackClient c = h.newClient()) {
            c.login("device-001", "secret-001", 1000);
            FrameAsserts.readFrameType(c);

            byte[] unknown = LoopbackClient.frame(MessageType.DATA, 0, 1, new byte[0]);
            unknown[3] = 0x55;
            int crcOff = unknown.length - 2;
            int crc = com.github.lisama569.devicegatewayjava.protocol.Crc16.checksum(unknown, 0, crcOff);
            unknown[crcOff] = (byte) (crc >>> 8);
            unknown[crcOff + 1] = (byte) crc;
            c.sendRaw(unknown);

            ErrorFrames.Parsed err = FrameAsserts.readErrorFrame(c);
            log.info("[E2E未知类型] type=0x55 -> code={} reason='{}'", err.code(), err.reason());
            assertEquals(GatewayErrorCode.UNKNOWN_MESSAGE_TYPE.code(), err.code());

            // 服务仍按当前版本正常处理后续帧
            c.sendRaw(LoopbackClient.frame(MessageType.DATA, 0, 1, "ok".getBytes()));
            assertEquals(MessageType.ACK.code(), FrameAsserts.readFrameType(c));
        }
    }
}
