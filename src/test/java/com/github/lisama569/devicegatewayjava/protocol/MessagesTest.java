package com.github.lisama569.devicegatewayjava.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class MessagesTest {

    private static final Logger log = LoggerFactory.getLogger(MessagesTest.class);

    @Test
    void roundTrips() {
        var hello = Messages.parseHello(Messages.hello(1, 2));
        assertEquals(new Messages.Hello(1, 2), hello);

        var helloAck = Messages.parseHelloAck(Messages.helloAck(2, 99L));
        assertEquals(2, helloAck.negotiatedVersion());
        assertEquals(99L, helloAck.sessionId());

        var login = Messages.parseLogin(
                Messages.login("dev-001", "tok-secret", 123456789L));
        assertEquals("dev-001", login.deviceId());
        assertEquals("tok-secret", login.token());
        assertEquals(123456789L, login.timestamp());
        log.info("login payload roundtrip ok (token value is only held in memory, never logged here)");

        var ack = Messages.parseAck(Messages.ack(7, false, 6001, "DUPLICATE"));
        assertEquals(7, ack.ackedSeq());
        assertEquals(6001, ack.code());
        assertEquals("DUPLICATE", ack.reason());

        var dl = Messages.parseDownlink(Messages.downlink("reboot", "now"));
        assertEquals("reboot", dl.command());
        assertEquals("now", dl.arg());

        var err = Messages.parseError(Messages.error(5003, "auth", "CREDENTIAL_EXPIRED", 4));
        assertEquals(5003, err.code());
        assertEquals("auth", err.category());
        assertEquals(4, err.refSeq());

        var up = Messages.parseUplink(Messages.uplink("温度", 42L));
        assertEquals("温度", up.key());
        assertEquals(42L, up.value());
        log.info("all message payloads roundtripped, including UTF-8 key='{}'", up.key());
    }

    @Test
    void truncatedPayloadIsRejectedAsMalformed() {
        byte[] good = Messages.login("d", "t", 1L);
        byte[] truncated = java.util.Arrays.copyOf(good, good.length - 3);
        Messages.PayloadException ex = assertThrows(Messages.PayloadException.class,
                () -> Messages.parseLogin(truncated));
        log.info("judgement: truncated login payload -> MALFORMED_PAYLOAD: {}", ex.getMessage());
    }

    @Test
    void trailingBytesAreMalformed() {
        byte[] good = Messages.uplink("k", 1L);
        byte[] plus = java.util.Arrays.copyOf(good, good.length + 1);
        assertThrows(Messages.PayloadException.class, () -> Messages.parseUplink(plus));
        log.info("judgement: payload with trailing bytes -> MALFORMED_PAYLOAD");
    }

    @Test
    void emptyPayloadIsMalformed() {
        assertThrows(Messages.PayloadException.class,
                () -> Messages.parseHello(new byte[0]));
        assertThrows(Messages.PayloadException.class,
                () -> Messages.parseAck(new byte[0]));
    }

    @Test
    void utf8LengthsAreBytesNotChars() {
        byte[] encoded = Messages.uplink("温度", 1L);
        int expected = 4 + "温度".getBytes(StandardCharsets.UTF_8).length + 8;
        assertEquals(expected, encoded.length);
        log.info("judgement: string length prefix counts UTF-8 bytes ({} bytes for 温度)",
                "温度".getBytes(StandardCharsets.UTF_8).length);
    }
}
