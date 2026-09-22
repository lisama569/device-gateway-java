package com.github.lisama569.devicegatewayjava.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class SessionRegistryTest {

    private static final Logger log = LoggerFactory.getLogger(SessionRegistryTest.class);

    @Test
    void duplicateLoginIsRejectedAndUnregisterIsIdentitySafe() {
        SessionRegistry registry = new SessionRegistry();
        SessionState first = new SessionState("dev-001", 1L, 0);
        SessionState second = new SessionState("dev-001", 2L, 0);

        assertNull(registry.register(first));
        SessionState existing = registry.register(second);
        assertNotNull(existing);
        assertEquals(first.sessionId(), existing.sessionId());
        assertEquals(1, registry.activeCount());
        log.info("judgement: second LOGIN for 'dev-001' rejected ALREADY_LOGGED_IN, first session intact");

        // stale unregister for a different session must not remove the owner
        registry.unregister(second, "stale");
        assertNotNull(registry.findActive("dev-001"), "real owner must remain");
        registry.unregister(first, "logout");
        assertNull(registry.findActive("dev-001"));
        log.info("judgement: identity-safe unregister leaves owner until it closes itself");
    }
}
