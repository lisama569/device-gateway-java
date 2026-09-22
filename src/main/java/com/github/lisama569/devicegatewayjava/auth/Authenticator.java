package com.github.lisama569.devicegatewayjava.auth;

import com.github.lisama569.devicegatewayjava.error.GatewayErrorCode;
import com.github.lisama569.devicegatewayjava.error.GatewayException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;

/**
 * 认证器：
 * - 负载无法解析/字段缺失 -> CREDENTIAL_MISSING / MALFORMED_PAYLOAD
 * - 设备不存在或密钥不匹配 -> CREDENTIAL_INVALID（不区分，避免枚举设备）
 * - 已过期 -> CREDENTIAL_EXPIRED
 * 任何分支都不记录/回传 secret 原文，日志只出现 deviceId 与判定依据。
 */
public class Authenticator {

    private final CredentialStore store;
    private final Clock clock;

    public Authenticator(CredentialStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public String authenticate(byte[] loginPayload) {
        final LoginPayload login;
        try {
            login = LoginPayload.parse(loginPayload);
        } catch (GatewayException malformed) {
            throw new GatewayException(GatewayErrorCode.CREDENTIAL_MISSING,
                    "login payload unparseable: " + malformed.getMessage());
        }

        String deviceId = login.deviceId();
        String secret = login.secret();
        if (deviceId == null || deviceId.isBlank() || secret == null || secret.isEmpty()) {
            throw new GatewayException(GatewayErrorCode.CREDENTIAL_MISSING, "deviceId or secret empty");
        }

        DeviceCredential cred = store.find(deviceId)
                .orElseThrow(() -> new GatewayException(GatewayErrorCode.CREDENTIAL_INVALID,
                        "device not registered"));

        if (!constantTimeEquals(sha256(secret), sha256(cred.secret()))) {
            throw new GatewayException(GatewayErrorCode.CREDENTIAL_INVALID, "secret mismatch");
        }

        if (cred.expiresAtEpochMs() > 0 && clock.millis() >= cred.expiresAtEpochMs()) {
            throw new GatewayException(GatewayErrorCode.CREDENTIAL_EXPIRED,
                    "credential expired at " + cred.expiresAtEpochMs());
        }
        return deviceId;
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
