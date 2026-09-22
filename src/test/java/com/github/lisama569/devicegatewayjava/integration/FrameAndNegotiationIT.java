package com.github.lisama569.devicegatewayjava.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.lisama569.devicegatewayjava.protocol.Frame;
import com.github.lisama569.devicegatewayjava.protocol.FrameEncoder;
import com.github.lisama569.devicegatewayjava.protocol.Messages;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Sticky/half packets over the real loopback TCP stack, and version negotiation. */
class FrameAndNegotiationIT {

    private static final Logger log = LoggerFactory.getLogger(FrameAndNegotiationIT.class);

    private TestGateway gw;

    @AfterEach
    void tearDown() {
        if (gw != null) {
            gw.stop();
        }
    }

    @Test
    void stickyPacketTwoFramesOneWriteBothProcessed() throws IOException {
        gw = TestGateway.start();
        try (TestGateway.DeviceClient c = TestGateway.DeviceClient.create(gw, gw.connect())) {
            byte[] h = FrameEncoder.encode(new Frame(Frame.T_HELLO, (byte) 0, 0,
                    Messages.hello(1, 1)));
            byte[] login = FrameEncoder.encode(new Frame(Frame.T_LOGIN, (byte) 0, 0,
                    Messages.login("dev-001", "tok_valid_001", gw.clock.nowMillis())));
            byte[] sticky = new byte[h.length + login.length];
            System.arraycopy(h, 0, sticky, 0, h.length);
            System.arraycopy(login, 0, sticky, h.length, login.length);
            c.sendRaw(sticky); // two frames in one TCP write
            Frame helloAck = c.readFrame();
            Frame loginAck = c.readFrame();
            assertEquals(Frame.T_HELLO_ACK, helloAck.type());
            assertEquals(Frame.T_LOGIN_ACK, loginAck.type());
            Messages.LoginAck parsed = Messages.parseLoginAck(loginAck.payload());
            assertTrue(parsed.accepted(), "sticky packet login must be accepted");
            log.info("judgement: 2 frames in 1 write -> HELLO_ACK + LOGIN_ACK(accepted) both returned");
        }
    }

    @Test
    void halfPacketOneByteAtATimeStillParses() throws IOException {
        gw = TestGateway.start();
        try (TestGateway.DeviceClient c = TestGateway.DeviceClient.create(gw, gw.connect())) {
            byte[] h = FrameEncoder.encode(new Frame(Frame.T_HELLO, (byte) 0, 0,
                    Messages.hello(1, 1)));
            c.sendRawChunks(h, 1); // byte-by-byte
            Frame helloAck = c.readFrame();
            assertEquals(Frame.T_HELLO_ACK, helloAck.type());
            log.info("judgement: HELLO sent 1 byte/wire-write ({} writes) still parsed",
                    h.length);
        }
    }

    @Test
    void versionMismatchIsRejectedWithReason() throws IOException {
        gw = TestGateway.start();
        try (TestGateway.DeviceClient c = TestGateway.DeviceClient.create(gw, gw.connect())) {
            c.sendRaw(FrameEncoder.encode(new Frame(Frame.T_HELLO, (byte) 0, 0,
                    Messages.hello(99, 100))));
            Frame err = c.readFrame();
            assertEquals(Frame.T_ERROR, err.type());
            Messages.ErrorBody body = Messages.parseError(err.payload());
            assertEquals(4007, body.code());
            assertEquals("protocol", body.category());
            log.info("judgement: device version 99..100 vs server 1 -> VERSION_MISMATCH code={} reason='{}'",
                    body.code(), body.reason());
        }
    }

    @Test
    void unknownMessageTypeIsRejectedAndConnectionSurvives() throws IOException {
        gw = TestGateway.start();
        try (TestGateway.DeviceClient c = TestGateway.DeviceClient.create(gw, gw.connect())) {
            c.sendRaw(FrameEncoder.encode(new Frame((byte) 0x7F, (byte) 0, 1,
                    new byte[0])));
            Frame err = c.readFrame();
            assertEquals(Frame.T_ERROR, err.type());
            assertEquals(4006, Messages.parseError(err.payload()).code());
            // a later valid HELLO on the same connection still works
            c.sendRaw(FrameEncoder.encode(new Frame(Frame.T_HELLO, (byte) 0, 2,
                    Messages.hello(1, 1))));
            Frame helloAck = c.readFrame();
            assertEquals(Frame.T_HELLO_ACK, helloAck.type());
            log.info("judgement: unknown type 0x7f -> UNKNOWN_MESSAGE_TYPE(4006), following HELLO still negotiated");
        }
    }

    @Test
    void badCrcFrameRejectedThenValidFrameOnSameConnection() throws IOException {
        gw = TestGateway.start();
        try (TestGateway.DeviceClient c = TestGateway.DeviceClient.create(gw, gw.connect())) {
            byte[] bad = FrameEncoder.encode(new Frame(Frame.T_HEARTBEAT, (byte) 0, 1,
                    new byte[]{0x01}));
            bad[Frame.HEADER_LEN] ^= 0xFF; // corrupt payload -> body crc mismatch
            byte[] good = FrameEncoder.encode(new Frame(Frame.T_HELLO, (byte) 0, 2,
                    Messages.hello(1, 1)));
            c.sendRaw(bad);
            c.sendRaw(good);
            Frame err = c.readFrame();
            assertEquals(4005, Messages.parseError(err.payload()).code());
            Frame helloAck = c.readFrame();
            assertEquals(Frame.T_HELLO_ACK, helloAck.type());
            log.info("judgement: corrupted frame -> BODY_CRC_MISMATCH(4005), no desync, next HELLO negotiated");
        }
    }
}
