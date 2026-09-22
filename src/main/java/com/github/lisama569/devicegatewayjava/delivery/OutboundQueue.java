package com.github.lisama569.devicegatewayjava.delivery;

import com.github.lisama569.devicegatewayjava.protocol.ErrorCode;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;

/**
 * Bounded queue of encoded frames waiting to be written to one socket.
 * Backpressure is explicit: producers receive {@link OfferResult#QUEUE_FULL}
 * instead of causing unbounded memory growth, and the rejection is counted.
 */
public final class OutboundQueue {

    public enum OfferResult { ACCEPTED, QUEUE_FULL }

    /** Stop the writer thread immediately (shutdown). */
    public static final byte[] POISON = new byte[0];
    /** Ordered marker: all frames already queued are written, then socket closes. */
    public static final byte[] CLOSE_AFTER_FLUSH = new byte[0];

    private final int capacity;
    private final LinkedBlockingQueue<byte[]> queue;
    private final AtomicInteger rejectedCount = new AtomicInteger();

    public OutboundQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity <= 0");
        }
        this.capacity = capacity;
        this.queue = new LinkedBlockingQueue<>(capacity);
    }

    public OfferResult offer(byte[] encodedFrame) {
        if (queue.offer(encodedFrame)) {
            return OfferResult.ACCEPTED;
        }
        rejectedCount.incrementAndGet();
        return OfferResult.QUEUE_FULL;
    }

    public byte[] take() throws InterruptedException {
        return queue.take();
    }

    public byte[] poll(long timeout, TimeUnit unit) throws InterruptedException {
        return queue.poll(timeout, unit);
    }

    public void poison() {
        queue.offer(POISON);
    }

    /**
     * Block until the ordered close marker is queued behind every frame
     * already offered, so the writer flushes pending data and then closes.
     */
    public void closeAfterFlush() throws InterruptedException {
        queue.put(CLOSE_AFTER_FLUSH);
    }

    public int size() {
        return queue.size();
    }

    public int capacity() {
        return capacity;
    }

    public int rejectedCount() {
        return rejectedCount.get();
    }

    public ErrorCode rejectionCode() {
        return ErrorCode.OUTBOUND_QUEUE_FULL;
    }
}
