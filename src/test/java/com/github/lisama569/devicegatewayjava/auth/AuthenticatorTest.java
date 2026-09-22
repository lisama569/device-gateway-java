package com.github.lisama569.devicegatewayjava.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.lisama569.devicegatewayjava.config.GatewayProperties;
import com.github.lisama569.devicegatewayjava.protocol.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class AuthenticatorTest {

    private static final Logger log = LoggerFactory.getLogger(AuthenticatorTest.class);

    private Authenticator authenticator;
    private static final String VALID_TOKEN = "tok_valid_001";

    @BeforeEach
    void setUp() {
        GatewayProperties props = new GatewayProperties();
        props.getCredentials().put("dev-001", cred(VALID_TOKEN, 4102444800000L));
        props.getCredentials().put("dev-expired", cred("tok_expired", 1000L));
        authenticator = new Authenticator(new ConfigCredentialStore(props));
    }

    private GatewayProperties.Credential cred(String token, long expiresAt) {
        GatewayProperties.Credential c = new GatewayProperties.Credential();
        c.setToken(token);
        c.setExpiresAt(expiresAt);
        return c;
    }

    @Test
    void missingDeviceIdAndTokenAreDistinguished() {
        AuthResult r1 = authenticator.authenticate(null, "t", 5000L);
        AuthResult r2 = authenticator.authenticate("dev-001", "", 5000L);
        assertEquals(ErrorCode.CREDENTIAL_MISSING, r1.code());
        assertEquals(ErrorCode.CREDENTIAL_MISSING, r2.code());
        log.info("input: deviceId=null token='t' -> {} reason='{}'", r1.code(), r1.reason());
        log.info("input: deviceId='dev-001' token='' -> {} reason='{}'", r2.code(), r2.reason());
    }

    @Test
    void unknownDeviceOrWrongTokenIsInvalid() {
        AuthResult unknown = authenticator.authenticate("ghost", "x", 5000L);
        AuthResult wrong = authenticator.authenticate("dev-001", "WRONG", 5000L);
        assertEquals(ErrorCode.CREDENTIAL_INVALID, unknown.code());
        assertEquals(ErrorCode.CREDENTIAL_INVALID, wrong.code());
        log.info("judgement: unknown device -> {}, wrong token -> {} (reason='{}')",
                unknown.code(), wrong.code(), wrong.reason());
    }

    @Test
    void expiredCredentialIsDistinguished() {
        AuthResult r = authenticator.authenticate("dev-expired", "tok_expired", 5000L);
        assertEquals(ErrorCode.CREDENTIAL_EXPIRED, r.code());
        log.info("input: deviceId='dev-expired' correct token at now=5000 -> {} (expiresAt=1000)",
                r.code());
    }

    @Test
    void validCredentialAccepts() {
        AuthResult r = authenticator.authenticate("dev-001", VALID_TOKEN, 5000L);
        assertTrue(r.accepted());
        assertEquals("dev-001", r.deviceId());
        log.info("judgement: valid credential -> accepted device='{}'", r.deviceId());
    }

    @Test
    void redactionNeverContainsRawToken() {
        String shown = Authenticator.redact(VALID_TOKEN);
        String none = Authenticator.redact("");
        assertFalse(shown.contains(VALID_TOKEN), "redaction must not include raw token");
        assertEquals("<none>", none);
        log.info("judgement: raw token '{}' is logged as '{}'; empty token shown as '{}'",
                VALID_TOKEN, shown, none);
    }
}
