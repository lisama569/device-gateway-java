package com.github.lisama569.devicegatewayjava.delivery;

import com.github.lisama569.devicegatewayjava.protocol.Frame;

/**
 * One unacknowledged downlink frame with bounded retry bookkeeping.
 * {@code sendCount} starts at 1 (the original send); each retransmission
 * increments it, i.e. sendCount = 1 + retryCount.
 */
public final class PendingDownlink {

    private final Frame frame;
    private final long createdAtMillis;
    private int sendCount;
    private long lastSentAtMillis;
    private volatile boolean acknowledged;

    public PendingDownlink(Frame frame, long nowMillis) {
        this.frame = frame;
        this.createdAtMillis = nowMillis;
        this.lastSentAtMillis = nowMillis;
        this.sendCount = 1;
    }

    public Frame frame() { return frame; }
    public long seq() { return frame.seq(); }
    public int sendCount() { return sendCount; }
    public int retryCount() { return sendCount - 1; }
    public long lastSentAtMillis() { return lastSentAtMillis; }
    public long createdAtMillis() { return createdAtMillis; }
    public boolean acknowledged() { return acknowledged; }

    /** Records one (re)transmission. Returns the new send count. */
    public int markSent(long nowMillis) {
        this.sendCount++;
        this.lastSentAtMillis = nowMillis;
        return this.sendCount;
    }

    /** First ACK wins; further ACKs are duplicates. */
    public boolean markAcked() {
        if (acknowledged) {
            return false;
        }
        acknowledged = true;
        return true;
    }
}
