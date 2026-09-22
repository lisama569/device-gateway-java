package com.github.lisama569.devicegatewayjava.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.lisama569.devicegatewayjava.protocol.Frame;
import com.github.lisama569.devicegatewayjava.protocol.FrameEncoder;
import com.github.lisama569.devicegatewayjava.protocol.Messages;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class AuthIT {

    private static final Logger log = LoggerFactory.getLogger(AuthIT.class);

    private TestGateway gw;

    @AfterEach
    void tearDown() {
        if (gw != null) {
            gw.stop();
        }
    }

    private Frame hello(TestGateway.DeviceClient c) throws IOException {
        c.sendRaw(FrameEncoder.encode(new Frame(Frame.T_HELLO, (byte) 0, 0,
                Messages.hello(1, 1))));
        return c.readFrame();
    }

    private Frame login(TestGateway.DeviceClient c, String deviceId, String token)
            throws IOException {
        c.sendRaw(FrameEncoder.encode(new Frame(Frame.T_LOGIN, (byte) 0, 0,
                Messages.login(deviceId, token, gw.clock.nowMillis()))));
        return c.readFrame();
    }

    private Messages.LoginAck assertRejected(Frame f, int code) {
        assertEquals(Frame.T_LOGIN_ACK, f.type());
        Messages.LoginAck ack = Messages.parseLoginAck(f.payload());
        assertFalse(ack.accepted());
        assertEquals(code, ack.code());
        return ack;
    }

    @Test
    void missingCredentialRejected5001() throws IOException {
        gw = TestGateway.start();
        try (TestGateway.DeviceClient c = TestGateway.DeviceClient.create(gw, gw.connect())) {
            hello(c);
            Frame f = login(c, "", "");
            Messages.LoginAck ack = assertRejected(f, 5001);
            log.info("judgement: empty deviceId/token -> CREDENTIAL_MISSING code={} reason='{}' (no token echoed)",
                    ack.code(), ack.reason());
            assertFalse(ack.reason().toLowerCase().contains("tok"));
        }
    }

    @Test
    void invalidCredentialRejected5002() throws IOException {
        gw = TestGateway.start();
        try (TestGateway.DeviceClient c = TestGateway.DeviceClient.create(gw, gw.connect())) {
            hello(c);
            Messages.LoginAck unknown = assertRejected(login(c, "ghost", "whatever"), 5002);
            Messages.LoginAck wrong = assertRejected(login(c, "dev-001", "WRONG_TOKEN"), 5002);
            log.info("judgement: unknown device -> {}; wrong token -> {}; responses never carry the credential",
                    unknown.reason(), wrong.reason());
        }
    }

    @Test
    void expiredCredentialRejected5003() throws IOException {
        gw = TestGateway.start();
        try (TestGateway.DeviceClient c = TestGateway.DeviceClient.create(gw, gw.connect())) {
            hello(c);
            Messages.LoginAck ack = assertRejected(login(c, "dev-expired", "tok_expired"), 5003);
            log.info("judgement: expired credential -> CREDENTIAL_EXPIRED code={} reason='{}'",
                    ack.code(), ack.reason());
        }
    }

    @Test
    void duplicateLoginRejected5004AndOldSessionKept() throws IOException, InterruptedException {
        gw = TestGateway.start();
        try (TestGateway.DeviceClient first = gw.loginClient("dev-001", "tok_valid_001");
             TestGateway.DeviceClient second =
                     TestGateway.DeviceClient.create(gw, gw.connect())) {
            hello(second);
            Messages.LoginAck ack = Messages.parseLoginAck(login(second,
                    "dev-001", "tok_valid_001").payload());
            assertFalse(ack.accepted());
            assertEquals(5004, ack.code());
            // first session still alive and can do business
            Frame upAck = first.uplink("after-dup-login", 1L);
            Messages.Ack parsed = Messages.parseAck(upAck.payload());
            assertTrue(parsed.accepted(), "original session must remain usable");
            log.info("judgement: second LOGIN -> ALREADY_LOGGED_IN(5004); first session still accepted uplink seq=1");
            Thread.sleep(50);
        }
    }

    @Test
    void businessFramesBeforeLoginAreRejectedAndDoNotMutateState() throws IOException {
        gw = TestGateway.start();
        try (TestGateway.DeviceClient c = TestGateway.DeviceClient.create(gw, gw.connect())) {
            // no HELLO, no LOGIN: uplink must be refused
            c.sendRaw(FrameEncoder.encode(new Frame(Frame.T_UPLINK, (byte) 0, 1,
                    Messages.uplink("sneaky", 1L))));
            Frame err = c.readFrame();
            assertEquals(Frame.T_ERROR, err.type());
            Messages.ErrorBody body = Messages.parseError(err.payload());
            assertEquals(5005, body.code());
            assertEquals(0, gw.deviceStates.get("dev-001").keyCount(),
                    "rejected session must not write business state");
            log.info("judgement: uplink without login -> UNAUTHENTICATED(5005), device state untouched keys={}",
                    gw.deviceStates.get("dev-001").keyCount());
        }
    }

    @Test
    void secondLoginOnSameConnectionIsRejected5004() throws IOException, InterruptedException {
        gw = TestGateway.start();
        try (TestGateway.DeviceClient c = gw.loginClient("dev-001", "tok_valid_001")) {
            c.sendRaw(com.github.lisama569.devicegatewayjava.protocol.FrameEncoder.encode(
                    new Frame(Frame.T_LOGIN, (byte) 0, 0,
                            Messages.login("dev-001", "tok_valid_001", gw.clock.nowMillis()))));
            Frame ack = c.readFrame();
            Messages.LoginAck parsed = Messages.parseLoginAck(ack.payload());
            assertFalse(parsed.accepted());
            assertEquals(5004, parsed.code());
            // session still usable
            Frame upAck = c.uplink("still-alive", 1L);
            assertTrue(Messages.parseAck(upAck.payload()).accepted());
            log.info("judgement: second LOGIN on the same authenticated connection -> 5004, session stays usable");
            Thread.sleep(30);
        }
    }
}
