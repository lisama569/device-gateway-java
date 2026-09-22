package com.github.lisama569.devicegatewayjava.auth;

/** 设备凭证。expiresAtEpochMs 为 0 表示不过期。 */
public record DeviceCredential(String deviceId, String secret, long expiresAtEpochMs) {
}
