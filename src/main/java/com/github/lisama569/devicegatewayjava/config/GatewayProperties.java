package com.github.lisama569.devicegatewayjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 网关可配置项，前缀 gateway.*。 */
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    private int port = 19000;
    private int maxConnections = 64;
    private int maxFrameLength = 8192;
    private int maxOutboundPerSession = 256;
    private long ackTimeoutMs = 3000;
    private int maxRetries = 3;
    /** 序号允许的回退窗口（重复/乱序容忍范围）。 */
    private long seqWindow = 32;
    private long shutdownQuietPeriodMs = 500;
    private long shutdownTimeoutMs = 5000;
    private int ioWorkerThreads = 4;

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public int getMaxConnections() { return maxConnections; }
    public void setMaxConnections(int v) { this.maxConnections = v; }
    public int getMaxFrameLength() { return maxFrameLength; }
    public void setMaxFrameLength(int v) { this.maxFrameLength = v; }
    public int getMaxOutboundPerSession() { return maxOutboundPerSession; }
    public void setMaxOutboundPerSession(int v) { this.maxOutboundPerSession = v; }
    public long getAckTimeoutMs() { return ackTimeoutMs; }
    public void setAckTimeoutMs(long v) { this.ackTimeoutMs = v; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int v) { this.maxRetries = v; }
    public long getSeqWindow() { return seqWindow; }
    public void setSeqWindow(long v) { this.seqWindow = v; }
    public long getShutdownQuietPeriodMs() { return shutdownQuietPeriodMs; }
    public void setShutdownQuietPeriodMs(long v) { this.shutdownQuietPeriodMs = v; }
    public long getShutdownTimeoutMs() { return shutdownTimeoutMs; }
    public void setShutdownTimeoutMs(long v) { this.shutdownTimeoutMs = v; }
    public int getIoWorkerThreads() { return ioWorkerThreads; }
    public void setIoWorkerThreads(int v) { this.ioWorkerThreads = v; }
}
