package com.github.lisama569.devicegatewayjava.auth;

import java.util.Optional;

/** 设备凭证查询接口（本地、无外部依赖）。 */
public interface CredentialStore {

    Optional<DeviceCredential> find(String deviceId);
}
