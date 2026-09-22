package com.github.lisama569.devicegatewayjava.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ManualClockTest {

    private static final Logger log = LoggerFactory.getLogger(ManualClockTest.class);

    @Test
    void tasksRunOnlyWhenVirtualDeadlineReached() {
        ManualClock clock = new ManualClock(1000L);
        AtomicInteger fired = new AtomicInteger();
        clock.schedule(fired::incrementAndGet, 500L); // at 1500
        clock.advance(499L);
        assertEquals(0, fired.get());
        clock.advance(1L);
        assertEquals(1, fired.get());
        log.info("judgement: scheduled task did not fire at +499ms, fired at +500ms virtual time");
    }

    @Test
    void sameDeadlineTasksRunInOneStepButLaterDeadlinesNeedAnotherAdvance() {
        ManualClock clock = new ManualClock(0L);
        AtomicInteger chain = new AtomicInteger();
        Runnable[] recursive = new Runnable[1];
        recursive[0] = () -> {
            chain.incrementAndGet();
            if (chain.get() < 3) {
                clock.schedule(recursive[0], 10L); // next deadline, not the same instant
            }
        };
        clock.schedule(recursive[0], 10L);
        clock.advance(10L);
        assertEquals(1, chain.get());
        clock.advance(10L);
        assertEquals(2, chain.get());
        clock.advance(10L);
        assertEquals(3, chain.get());
        log.info("judgement: one scheduled deadline per advance() -> chain needs 3 steps, exactly once each");
    }
}
