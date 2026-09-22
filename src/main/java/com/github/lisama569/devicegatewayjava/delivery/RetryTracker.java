package com.github.lisama569.devicegatewayjava.delivery;

import com.github.lisama569.devicegatewayjava.protocol.ErrorCode;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks pending downlink frames for one session.
 *
 * <p>Reliability rules:
 * <ul>
 *   <li>the first ACK for a seq completes it and removes it from tracking;</li>
 *   <li>duplicate ACKs for an already completed seq are reported as such
 *       ({@link #isDuplicateAck}) and never re-apply anything;</li>
 *   <li>retransmission is bounded: at most {@code maxRetries} retransmissions
 *       follow the original send. When a timer fires after that budget is
 *       exhausted, the session is terminated with
 *       SESSION_TERMINATED_RETRY_EXHAUSTED.</li>
 * </ul>
 */
public final class RetryTracker {

    private final int maxRetries;
    private final Map<Long, PendingDownlink> pending = new ConcurrentHashMap<>();
    private final java.util.Set<Long> completed = ConcurrentHashMap.newKeySet();

    public RetryTracker(int maxRetries) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries < 0");
        }
        this.maxRetries = maxRetries;
    }

    public void register(PendingDownlink p) {
        pending.put(p.seq(), p);
    }

    public PendingDownlink get(long seq) {
        return pending.get(seq);
    }

    /** Returns true only on the first ACK for this seq; removes it from pending. */
    public boolean acknowledge(long seq) {
        PendingDownlink p = pending.remove(seq);
        if (p == null) {
            return false;
        }
        p.markAcked();
        completed.add(seq);
        return true;
    }

    /** ACK for a seq that was already completed (duplicate ACK). */
    public boolean isDuplicateAck(long seq) {
        return !pending.containsKey(seq) && completed.contains(seq);
    }

    public void remove(long seq) {
        pending.remove(seq);
    }

    public int pendingCount() {
        return pending.size();
    }

    public Collection<PendingDownlink> pendingFrames() {
        return pending.values();
    }

    public int maxRetries() {
        return maxRetries;
    }

    /**
     * Records one retransmission of {@code p}. Returns true if this
     * retransmission used up the last allowed retry, i.e. the NEXT timeout
     * must terminate the session rather than resend.
     */
    public boolean recordRetry(PendingDownlink p, long nowMillis) {
        int count = p.markSent(nowMillis);
        return count - 1 >= maxRetries;
    }

    /** True when the retry budget is already fully used (no further resend). */
    public boolean budgetUsed(PendingDownlink p) {
        return p.retryCount() >= maxRetries;
    }

    public ErrorCode exhaustionErrorCode() {
        return ErrorCode.SESSION_TERMINATED_RETRY_EXHAUSTED;
    }
}
