package com.github.lisama569.devicegatewayjava.transport;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** connectionId -> 活动连接上下文。 */
public final class ConnectionRegistry {

    private final ConcurrentMap<String, ConnectionContext> active = new ConcurrentHashMap<>();

    public void register(ConnectionContext ctx) {
        active.put(ctx.connectionId(), ctx);
    }

    public void unregister(ConnectionContext ctx) {
        active.remove(ctx.connectionId(), ctx);
    }

    public ConnectionContext get(String connectionId) {
        return active.get(connectionId);
    }

    public int count() {
        return active.size();
    }

    public Iterable<ConnectionContext> all() {
        return active.values();
    }
}
