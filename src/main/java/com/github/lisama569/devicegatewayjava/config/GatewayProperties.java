package com.github.lisama569.devicegatewayjava.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** All tunable limits for the gateway. */
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    private String bindHost = "127.0.0.1";
    private int port = 19090;
    private int protocolVersion = 1;
    private int minProtocolVersion = 1;
    private int maxConnections = 64;
    private int maxFramePayload = 4096;
    private int maxBufferedBytes = 1 << 20;
    private int maxOutboundPending = 256;
    private int maxRetries = 3;
    private long retryIntervalMillis = 200;
    private long downlinkTimeoutMillis = 1000;
    private boolean enableManagement = true;
    private Map<String, Credential> credentials = new LinkedHashMap<>();

    public String getBindHost() { return bindHost; }
    public void setBindHost(String bindHost) { this.bindHost = bindHost; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public int getProtocolVersion() { return protocolVersion; }
    public void setProtocolVersion(int protocolVersion) { this.protocolVersion = protocolVersion; }
    public int getMinProtocolVersion() { return minProtocolVersion; }
    public void setMinProtocolVersion(int minProtocolVersion) { this.minProtocolVersion = minProtocolVersion; }
    public int getMaxConnections() { return maxConnections; }
    public void setMaxConnections(int maxConnections) { this.maxConnections = maxConnections; }
    public int getMaxFramePayload() { return maxFramePayload; }
    public void setMaxFramePayload(int maxFramePayload) { this.maxFramePayload = maxFramePayload; }
    public int getMaxBufferedBytes() { return maxBufferedBytes; }
    public void setMaxBufferedBytes(int maxBufferedBytes) { this.maxBufferedBytes = maxBufferedBytes; }
    public int getMaxOutboundPending() { return maxOutboundPending; }
    public void setMaxOutboundPending(int maxOutboundPending) { this.maxOutboundPending = maxOutboundPending; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public long getRetryIntervalMillis() { return retryIntervalMillis; }
    public void setRetryIntervalMillis(long retryIntervalMillis) { this.retryIntervalMillis = retryIntervalMillis; }
    public long getDownlinkTimeoutMillis() { return downlinkTimeoutMillis; }
    public void setDownlinkTimeoutMillis(long downlinkTimeoutMillis) { this.downlinkTimeoutMillis = downlinkTimeoutMillis; }
    public boolean isEnableManagement() { return enableManagement; }
    public void setEnableManagement(boolean enableManagement) { this.enableManagement = enableManagement; }
    public Map<String, Credential> getCredentials() { return credentials; }
    public void setCredentials(Map<String, Credential> credentials) { this.credentials = credentials; }

    public static class Credential {
        private String token = "";
        private long expiresAt = 0L;

        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public long getExpiresAt() { return expiresAt; }
        public void setExpiresAt(long expiresAt) { this.expiresAt = expiresAt; }
    }
}
