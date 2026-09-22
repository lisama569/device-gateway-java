package com.github.lisama569.devicegatewayjava.auth;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 基于内存映射的凭证存储。 */
public class InMemoryCredentialStore implements CredentialStore {

    private final Map<String, DeviceCredential> devices = new ConcurrentHashMap<>();

    public void register(DeviceCredential credential) {
        devices.put(credential.deviceId(), credential);
    }

    public void clear() {
        devices.clear();
    }

    @Override
    public Optional<DeviceCredential> find(String deviceId) {
        return Optional.ofNullable(devices.get(deviceId));
    }
}
