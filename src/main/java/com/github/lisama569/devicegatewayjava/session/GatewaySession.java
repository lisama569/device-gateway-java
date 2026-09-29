package com.github.lisama569.devicegatewayjava.session;

import com.github.lisama569.devicegatewayjava.negotiation.Capability;
import com.github.lisama569.devicegatewayjava.negotiation.NegotiationPhase;

import java.io.IOException;
import java.net.Socket;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 一条 TCP 连接对应的会话：
 *   NEW（未协商）-> SETTLED（能力协商定稿）-> AUTHENTICATED（LOGIN 成功）-> CLOSED。
 * 协商失败不进入本状态机：由调用方直接发 ERROR 后关连接。
 * 被拒绝的登录保持 SETTLED 未认证，dispatcher 保证其无法读写业务状态。
 */
public final class GatewaySession {

    private final String connectionId;
    private final Socket socket;
    private volatile String deviceId;
    private volatile boolean closed;
    private volatile String closeReason = "";

    /** 协商状态：定稿后版本与能力集合即为本会话硬约束，只能读、不能再改。 */
    private volatile NegotiationPhase phase = NegotiationPhase.NEW;
    private volatile int negotiatedVersion;
    private volatile Set<Capability> capabilities = Collections.emptySet();

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

    /** 协商是否已定稿（定稿后任何重协商都必须被拒绝）。 */
    public boolean negotiationSettled() { return phase == NegotiationPhase.SETTLED; }

    /** 本会话谈定的协议版本；未定稿返回 0。 */
    public int negotiatedVersion() { return negotiatedVersion; }

    /** 本会话谈定的能力集合（不可变视图）。 */
    public Set<Capability> capabilities() { return capabilities; }

    public boolean allows(Capability c) { return capabilities.contains(c); }

    /** 已协商会话在 DATA 帧 FLAGS 上允许出现的全部能力位。 */
    public byte allowedDataFlags() {
        int bits = 0;
        for (Capability c : capabilities) {
            bits |= (c.dataFlag() & 0xFF);
        }
        return (byte) bits;
    }

    /**
     * 协商定稿：只允许从 NEW 进入 SETTLED 一次；
     * 已认证/已定稿/已关闭后再次定稿一律拒绝（防止会话中途换规则）。
     */
    public synchronized void settleNegotiation(int version, List<Capability> agreed) {
        if (phase == NegotiationPhase.SETTLED) {
            throw new IllegalStateException("negotiation already settled on this session");
        }
        if (deviceId != null) {
            throw new IllegalStateException("session already authenticated");
        }
        if (closed) {
            throw new IllegalStateException("session already closed");
        }
        this.negotiatedVersion = version;
        EnumSet<Capability> set = EnumSet.noneOf(Capability.class);
        set.addAll(agreed);
        this.capabilities = Collections.unmodifiableSet(set);
        this.phase = NegotiationPhase.SETTLED;
    }

    /** 仅允许标记一次；重复登录（同连接）由调用方在更早处拒绝。 */
    public synchronized void markAuthenticated(String id) {
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
