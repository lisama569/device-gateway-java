package com.github.lisama569.devicegatewayjava.auth;

import com.github.lisama569.devicegatewayjava.config.GatewayProperties;
import org.springframework.stereotype.Component;

/** CredentialStore backed by gateway configuration. */
@Component
public class ConfigCredentialStore implements CredentialStore {

    private final GatewayProperties properties;

    public ConfigCredentialStore(GatewayProperties properties) {
        this.properties = properties;
    }

    @Override
    public StoredCredential find(String deviceId) {
        if (deviceId == null) {
            return null;
        }
        GatewayProperties.Credential c = properties.getCredentials().get(deviceId);
        if (c == null) {
            return null;
        }
        return new StoredCredential(c.getToken(), c.getExpiresAt());
    }
}
