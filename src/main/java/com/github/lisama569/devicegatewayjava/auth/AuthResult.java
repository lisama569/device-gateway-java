package com.github.lisama569.devicegatewayjava.auth;

import com.github.lisama569.devicegatewayjava.protocol.ErrorCode;

/** Result of an authentication decision. Never carries the raw credential. */
public record AuthResult(boolean accepted, ErrorCode code, String reason, String deviceId) {

    public static AuthResult ok(String deviceId) {
        return new AuthResult(true, null, null, deviceId);
    }

    public static AuthResult reject(ErrorCode code, String reason) {
        return new AuthResult(false, code, reason, null);
    }
}
