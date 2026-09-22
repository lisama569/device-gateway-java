package com.github.lisama569.devicegatewayjava.delivery;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic clock: time only advances when the test says so. Scheduled
 * tasks run on virtual time, so retry/timeout behaviour needs no real sleeps.
 */
public final class ManualClock implements GatewayClock {

    private long now;
    private final List<Scheduled> tasks = new ArrayList<>();

    private record Scheduled(Runnable task, long atMillis) {
    }

    public ManualClock() {
        this(0L);
    }

    public ManualClock(long startMillis) {
        this.now = startMillis;
    }

    @Override
    public synchronized long nowMillis() {
        return now;
    }

    @Override
    public synchronized void schedule(Runnable task, long delayMillis) {
        tasks.add(new Scheduled(task, now + Math.max(0, delayMillis)));
    }

    /**
     * Advance virtual time by exactly {@code deltaMillis} and run only tasks
     * scheduled at or before the new instant. Tasks scheduled during the run
     * are NOT executed here unless their deadline is also &lt;= the new instant;
     * call again to move to the following deadline (one retry per step).
     */
    public synchronized void advance(long deltaMillis) {
        long deadline = now + deltaMillis;
        List<Scheduled> due = new ArrayList<>();
        for (Scheduled t : tasks) {
            if (t.atMillis() <= deadline) {
                due.add(t);
            }
        }
        for (Scheduled t : due) {
            tasks.remove(t);
        }
        now = deadline;
        // execute after moving time; tasks scheduled at the same deadline
        // still run in scheduled order on this same step
        for (Scheduled t : due) {
            t.task().run();
        }
    }

    /** Advance to the earliest scheduled deadline (no-op if none pending). */
    public synchronized void advanceToNextDeadline() {
        long min = Long.MAX_VALUE;
        for (Scheduled t : tasks) {
            min = Math.min(min, t.atMillis());
        }
        if (min != Long.MAX_VALUE && min > now) {
            advance(min - now);
        }
    }

    public synchronized int scheduledCount() {
        return tasks.size();
    }
}
