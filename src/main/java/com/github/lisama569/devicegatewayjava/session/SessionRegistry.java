package com.github.lisama569.devicegatewayjava.session;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 活跃会话注册表：一个设备同一时刻至多一条已认证连接。
 * 重连登录时新连接取代旧连接（旧连接被踢并记录 SESSION_CONFLICT）。
 */
public final class SessionRegistry {

    private final ConcurrentMap<String, GatewaySession> byDevice = new ConcurrentHashMap<>();

    /** 原子地把会话登记为已认证；若设备此前在其他连接上登录，返回旧会话。 */
    public synchronized Optional<GatewaySession> registerAuthenticated(GatewaySession session) {
        String id = session.deviceId();
        if (id == null) throw new IllegalStateException("session not authenticated");
        GatewaySession old = byDevice.put(id, session);
        return Optional.ofNullable(old == session ? null : old);
    }

    public Optional<GatewaySession> get(String deviceId) {
        return Optional.ofNullable(byDevice.get(deviceId));
    }

    public int activeCount() {
        return byDevice.size();
    }

    /** 仅当映射确实指向自己时才移除，避免新会话误注销造成串号。 */
    public void unregister(GatewaySession session) {
        if (session.deviceId() == null) return;
        byDevice.remove(session.deviceId(), session);
    }
}
