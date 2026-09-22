package com.github.lisama569.devicegatewayjava.auth;

import com.github.lisama569.devicegatewayjava.protocol.ErrorCode;
import org.springframework.stereotype.Component;
import com.github.lisama569.devicegatewayjava.auth.CredentialStore.StoredCredential;

/**
 * Distinguishes authentication outcomes:
 * <ol>
 *   <li>missing device id / token -> CREDENTIAL_MISSING</li>
 *   <li>unknown device or wrong token -> CREDENTIAL_INVALID</li>
 *   <li>known credential whose expiresAt is in the past -> CREDENTIAL_EXPIRED</li>
 * </ol>
 * Duplicate-login handling lives in the session layer (ALREADY_LOGGED_IN).
 * Raw tokens are never returned in results or log messages; use {@link #redact}.
 */
@Component
public class Authenticator {

    private final CredentialStore store;

    public Authenticator(CredentialStore store) {
        this.store = store;
    }

    public AuthResult authenticate(String deviceId, String token, long nowMillis) {
        boolean idMissing = deviceId == null || deviceId.isBlank();
        boolean tokenMissing = token == null || token.isEmpty();
        if (idMissing || tokenMissing) {
            return AuthResult.reject(ErrorCode.CREDENTIAL_MISSING,
                    (idMissing ? "deviceId" : "token") + " is missing");
        }

        StoredCredential stored = store.find(deviceId);
        if (stored == null || !constantTimeEquals(stored.token(), token)) {
            return AuthResult.reject(ErrorCode.CREDENTIAL_INVALID,
                    "unknown device or token mismatch");
        }

        if (stored.expiresAt() > 0 && stored.expiresAt() <= nowMillis) {
            return AuthResult.reject(ErrorCode.CREDENTIAL_EXPIRED,
                    "credential expired at " + stored.expiresAt());
        }

        return AuthResult.ok(deviceId);
    }

    /** Length-aware constant-time comparison to avoid trivial timing leaks. */
    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] y = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int diff = x.length ^ y.length;
        int n = Math.min(x.length, y.length);
        for (int i = 0; i < n; i++) {
            diff |= x[i] ^ y[i];
        }
        return diff == 0;
    }

    /** Redacted form safe for logs, e.g. "tok_a1b2***" or "&lt;none&gt;". */
    public static String redact(String token) {
        if (token == null || token.isEmpty()) {
            return "<none>";
        }
        int shown = Math.min(4, token.length());
        return token.substring(0, shown) + "***(" + token.length() + ")";
    }
}
