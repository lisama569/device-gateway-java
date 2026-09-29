package com.github.lisama569.devicegatewayjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** 网关可配置项，前缀 gateway.*。 */
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    private int port = 19000;
    private int maxConnections = 64;
    private int maxFrameLength = 8192;
    /** 每连接接收残留硬上限（字节）：未拼成完整帧的缓冲不得超过该值，与单帧长度上限独立。 */
    private int maxReceiveBufferBytes = 16384;
    private int maxOutboundPerSession = 256;
    /** 网关支持的协议版本区间（含端点），协商时与设备区间求交。 */
    private int protocolMinVersion = 1;
    private int protocolMaxVersion = 1;
    /** 网关识别并支持的可协商能力项（线名）。 */
    private List<String> supportedCapabilities = List.of("priority", "header-extension");
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
    public int getMaxReceiveBufferBytes() { return maxReceiveBufferBytes; }
    public void setMaxReceiveBufferBytes(int v) { this.maxReceiveBufferBytes = v; }
    public int getProtocolMinVersion() { return protocolMinVersion; }
    public void setProtocolMinVersion(int v) { this.protocolMinVersion = v; }
    public int getProtocolMaxVersion() { return protocolMaxVersion; }
    public void setProtocolMaxVersion(int v) { this.protocolMaxVersion = v; }
    public List<String> getSupportedCapabilities() { return supportedCapabilities; }
    public void setSupportedCapabilities(List<String> v) { this.supportedCapabilities = v; }
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
