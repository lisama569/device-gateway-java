package com.github.lisama569.devicegatewayjava.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.lisama569.devicegatewayjava.protocol.Frame;
import com.github.lisama569.devicegatewayjava.protocol.Messages;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class RetryTrackerTest {

    private static final Logger log = LoggerFactory.getLogger(RetryTrackerTest.class);

    private PendingDownlink pending(long seq) {
        Frame f = new Frame(Frame.T_DOWNLINK, (byte) 0, seq,
                Messages.downlink("cmd", "arg"));
        return new PendingDownlink(f, 0L);
    }

    @Test
    void firstAckCompletesDuplicateAckDetected() {
        RetryTracker tracker = new RetryTracker(3);
        tracker.register(pending(10L));

        assertTrue(tracker.acknowledge(10L), "first ACK completes");
        assertTrue(tracker.isDuplicateAck(10L), "second ACK is a duplicate");
        assertFalse(tracker.acknowledge(10L), "duplicate ACK does not complete again");
        assertEquals(0, tracker.pendingCount());
        log.info("judgement: downSeq=10 first ACK completed, subsequent ACKs are DUPLICATE and ignored");
    }

    @Test
    void retryBudgetIsBoundedAndConvergesToExhaustion() {
        int maxRetries = 3;
        RetryTracker tracker = new RetryTracker(maxRetries);
        PendingDownlink p = pending(20L);
        tracker.register(p);

        // Timer firings without any ACK: 3 retransmissions, then the 4th
        // timer firing must see the budget used and trigger termination.
        boolean last1 = tracker.recordRetry(p, 200L);
        boolean last2 = tracker.recordRetry(p, 400L);
        boolean last3 = tracker.recordRetry(p, 600L);
        assertFalse(last1);
        assertFalse(last2);
        assertTrue(last3, "the 3rd retry uses the last allowed retransmission");
        assertEquals(4, p.sendCount(), "1 original + 3 retransmissions");
        assertTrue(tracker.budgetUsed(p), "next timeout must terminate, not resend");
        log.info("judgement: maxRetries={} -> sends={} (1 original + {} retry), next timeout => SESSION_TERMINATED_RETRY_EXHAUSTED",
                maxRetries, p.sendCount(), p.retryCount());
    }

    @Test
    void ackAfterRetryCompletesNormally() {
        RetryTracker tracker = new RetryTracker(3);
        PendingDownlink p = pending(30L);
        tracker.register(p);
        tracker.recordRetry(p, 200L);
        assertNotNull(tracker.get(30L));
        assertTrue(tracker.acknowledge(30L));
        assertFalse(tracker.isDuplicateAck(31L), "ACK for unknown seq is not a duplicate");
        log.info("judgement: ACK arriving after one retransmission completes; unknown seq ACK not treated as duplicate");
    }

    @Test
    void zeroRetriesMeansSingleSendThenExhaustion() {
        RetryTracker tracker = new RetryTracker(0);
        PendingDownlink p = pending(40L);
        tracker.register(p);
        assertTrue(tracker.budgetUsed(p), "with maxRetries=0 the first timeout must terminate");
        log.info("judgement: maxRetries=0 -> single send, first unacked timeout terminates session");
    }
}
