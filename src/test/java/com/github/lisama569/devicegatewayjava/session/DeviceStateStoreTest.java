package com.github.lisama569.devicegatewayjava.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class DeviceStateStoreTest {

    private static final Logger log = LoggerFactory.getLogger(DeviceStateStoreTest.class);

    private DeviceStateStore.DeviceState state(DeviceStateStore store) {
        return store.get("dev-001");
    }

    @Test
    void duplicateAndGapAndRollbackAreDeterministic() {
        DeviceStateStore store = new DeviceStateStore();
        var s = state(store);

        assertEquals(SeqDecision.ACCEPT, s.submit(1, "k1"));
        SeqDecision dup = s.submit(1, "k1");
        assertEquals(SeqDecision.DUPLICATE, dup);
        SeqDecision gap = s.submit(3, "k3");
        assertEquals(SeqDecision.OUT_OF_ORDER, gap);
        assertEquals(SeqDecision.ACCEPT, s.submit(2, "k2"));
        SeqDecision rollback = s.submit(1, "k1");
        assertEquals(SeqDecision.ROLLBACK, rollback,
                "seq older than the high-water mark is a rollback");
        SeqDecision dupAgain = s.submit(2, "k2");
        assertEquals(SeqDecision.DUPLICATE, dupAgain,
                "seq equal to the high-water mark is the last-message duplicate");

        assertEquals(2, s.lastAppliedSeq());
        assertEquals(2, s.keyCount(), "duplicates must not re-apply business state");
        assertTrue(s.containsKey("k1"));
        assertTrue(s.containsKey("k2"));
        assertFalse(s.containsKey("k3"), "gap frame must not mutate business state");
        log.info("judgement: seq 1=ACCEPT, 1=DUPLICATE, 3=OUT_OF_ORDER(gap), "
                + "2=ACCEPT, 1=ROLLBACK, 2=DUPLICATE; appliedKeys={}", s.keyCount());
    }

    @Test
    void nonPositiveAndGiantSequencesRejected() {
        DeviceStateStore store = new DeviceStateStore();
        var s = state(store);
        assertEquals(SeqDecision.OUT_OF_ORDER, s.submit(0, "k0"));
        assertEquals(SeqDecision.OUT_OF_ORDER, s.submit(-5, "kx"));
        assertEquals(0, s.lastAppliedSeq());
        log.info("judgement: seq=0 and seq=-5 -> OUT_OF_ORDER, highWater stays 0");
    }

    @Test
    void stateSurvivesReconnectSemantically() {
        DeviceStateStore store = new DeviceStateStore();
        state(store).submit(1, "k1");
        state(store).submit(2, "k2");
        // A brand new session object resumes from the device water mark.
        SessionState reconnect = new SessionState("dev-001", 100L, store.get("dev-001").lastAppliedSeq());
        assertEquals(2, reconnect.resumeSeq());
        SeqDecision replay = store.get("dev-001").submit(1, "k1");
        assertEquals(SeqDecision.ROLLBACK, replay, "replayed old seq after reconnect");
        SeqDecision duplicate = store.get("dev-001").submit(2, "k2");
        assertEquals(SeqDecision.DUPLICATE, duplicate);
        log.info("judgement: after reconnect at resumeSeq=2, replay of 1=ROLLBACK, 2=DUPLICATE");
    }

    @Test
    void concurrentSubmissionsAreAppliedExactlyOnceInOrder() throws Exception {
        DeviceStateStore store = new DeviceStateStore();
        var s = state(store);
        int threads = 16;
        int perThread = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepts = new AtomicInteger();
        AtomicInteger rejects = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            final int base = t * perThread;
            pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                for (int i = 1; i <= perThread; i++) {
                    long seq = base + i; // globally unique but submitted out of order
                    SeqDecision d = s.submit(seq, "key-" + seq);
                    if (d == SeqDecision.ACCEPT) {
                        accepts.incrementAndGet();
                    } else {
                        rejects.incrementAndGet();
                    }
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        log.info("judgement: concurrent unique-but-unordered seqs -> accepts={}, rejected={} (exactly-once requires delivery order)",
                accepts.get(), rejects.get());
        // Only contiguous-from-1 accepts advance the water mark; state never
        // shows a half update: accept count equals applied key count.
        assertEquals(accepts.get(), s.keyCount());
    }
}
