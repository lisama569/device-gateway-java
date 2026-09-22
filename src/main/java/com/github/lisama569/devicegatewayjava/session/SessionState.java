package com.github.lisama569.devicegatewayjava.session;

import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mutable state of one authenticated connection. Sequence ordering is owned by
 * {@link DeviceStateStore} (it must survive reconnect); this class only tracks
 * connection lifecycle and identity.
 */
public class SessionState {

    private static final AtomicLong ID_GEN = new AtomicLong();

    public enum Status { ACTIVE, CLOSED, TERMINATED }

    private final long sessionId;
    private final String deviceId;
    private final long connectedAtMillis;
    private final long resumeSeq;
    private volatile Status status = Status.ACTIVE;
    private volatile SocketAddress remoteAddress;
    private volatile String terminateReason;

    public SessionState(String deviceId, long connectedAtMillis, long resumeSeq) {
        this.sessionId = ID_GEN.incrementAndGet();
        this.deviceId = deviceId;
        this.connectedAtMillis = connectedAtMillis;
        this.resumeSeq = resumeSeq;
    }

    public long sessionId() { return sessionId; }
    public String deviceId() { return deviceId; }
    public long connectedAtMillis() { return connectedAtMillis; }
    public long resumeSeq() { return resumeSeq; }

    public Status status() { return status; }
    public boolean isAlive() { return status == Status.ACTIVE; }

    public void close(String reason) {
        this.status = Status.CLOSED;
        this.terminateReason = reason;
    }

    public void terminate(String reason) {
        this.status = Status.TERMINATED;
        this.terminateReason = reason;
    }

    public String terminateReason() { return terminateReason; }
    public SocketAddress remoteAddress() { return remoteAddress; }
    public void setRemoteAddress(SocketAddress address) { this.remoteAddress = address; }
}
