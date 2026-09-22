package com.github.lisama569.devicegatewayjava.auth;

/** Lookup of configured credentials. Implementations must never log raw tokens. */
public interface CredentialStore {

    record StoredCredential(String token, long expiresAt) {
    }

    /** Returns null when deviceId is unknown. */
    StoredCredential find(String deviceId);
}
