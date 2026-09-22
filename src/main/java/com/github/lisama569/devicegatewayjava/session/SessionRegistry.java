package com.github.lisama569.devicegatewayjava.session;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * All ACTIVE sessions keyed by device id. Enforces single-login semantics:
 * {@link #register} is atomic via putIfAbsent so a duplicate login is rejected
 * distinctly (ALREADY_LOGGED_IN) instead of silently kicking the old session.
 */
@Component
public class SessionRegistry {

    private final ConcurrentHashMap<String, SessionState> byDevice = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, SessionState> bySessionId = new ConcurrentHashMap<>();

    public SessionState findActive(String deviceId) {
        return byDevice.get(deviceId);
    }

    public SessionState get(long sessionId) {
        return bySessionId.get(sessionId);
    }

    public Collection<SessionState> all() {
        return byDevice.values();
    }

    public int activeCount() {
        return byDevice.size();
    }

    /**
     * Atomically claim the device slot. Returns the existing session on
     * duplicate login (caller rejects with ALREADY_LOGGED_IN), null on success.
     */
    public SessionState register(SessionState session) {
        SessionState existing = byDevice.putIfAbsent(session.deviceId(), session);
        if (existing != null) {
            return existing;
        }
        bySessionId.put(session.sessionId(), session);
        return null;
    }

    /** Removes only if this exact session still owns the slot (no session swap). */
    public void unregister(SessionState session, String reason) {
        byDevice.remove(session.deviceId(), session);
        bySessionId.remove(session.sessionId(), session);
        session.close(reason);
    }
}
