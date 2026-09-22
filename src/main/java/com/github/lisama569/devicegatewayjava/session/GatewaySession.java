package com.github.lisama569.devicegatewayjava.session;

import java.io.IOException;
import java.net.Socket;

/**
 * 一条 TCP 连接对应的会话：CONNECTED -> AUTHENTICATED -> CLOSED。
 * 被拒绝的登录保持 CONNECTED，dispatcher 保证其无法读写业务状态。
 */
public final class GatewaySession {

    private final String connectionId;
    private final Socket socket;
    private volatile String deviceId;
    private volatile boolean closed;
    private volatile String closeReason = "";

    public GatewaySession(String connectionId, Socket socket) {
        this.connectionId = connectionId;
        this.socket = socket;
    }

    public String connectionId() { return connectionId; }
    public Socket socket() { return socket; }
    public String deviceId() { return deviceId; }
    public boolean authenticated() { return deviceId != null; }
    public boolean closed() { return closed; }
    public String closeReason() { return closeReason; }

    /** 仅允许标记一次；重复登录（同连接）由调用方在更早处拒绝。 */
    public void markAuthenticated(String id) {
        if (this.deviceId != null) {
            throw new IllegalStateException("session already authenticated as " + this.deviceId);
        }
        this.deviceId = id;
    }

    /** 幂等关闭：记录第一个原因，后续原因不覆盖。 */
    public void close(String reason) {
        if (closed) return;
        closed = true;
        closeReason = reason == null ? "" : reason;
        try {
            socket.close();
        } catch (IOException ignored) {
            // 关闭尽力而为
        }
    }

    /**
     * 优雅关闭第一步：只半关闭读方向，唤醒阻塞在读循环上的线程；
     * 写方向保持打开，使已入队的待发帧（如 SERVER_SHUTTING_DOWN）能够排空。
     */
    public void shutdownInput() {
        if (closed) return;
        try {
            socket.shutdownInput();
        } catch (IOException ignored) {
            // 已关闭或不支持时忽略，后续 close 兜底
        }
    }
}
