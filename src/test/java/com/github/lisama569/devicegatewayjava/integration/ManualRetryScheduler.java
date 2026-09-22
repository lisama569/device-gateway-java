package com.github.lisama569.devicegatewayjava.integration;

import com.github.lisama569.devicegatewayjava.delivery.RetryScheduler;
import java.util.ArrayList;
import java.util.List;

/**
 * Retry scheduler for deterministic tests: tasks never run by themselves.
 * The test calls {@link #fireDue(long)} with a virtual "now", and exactly the
 * tasks whose deadline has been reached run on the calling thread. This makes
 * retransmission independent of real I/O thread scheduling.
 */
final class ManualRetryScheduler implements RetryScheduler {

    private record Task(Runnable runnable, long atMillis) {
    }

    private long virtualNow;
    private final List<Task> tasks = new ArrayList<>();

    ManualRetryScheduler(long startMillis) {
        this.virtualNow = startMillis;
    }

    @Override
    public synchronized void schedule(Runnable task, long delayMillis) {
        tasks.add(new Task(task, virtualNow + Math.max(0, delayMillis)));
    }

    /** Advance virtual time by delta and run all tasks due at/before it. */
    public synchronized void advance(long deltaMillis) {
        virtualNow += deltaMillis;
        List<Task> due = new ArrayList<>();
        for (Task t : tasks) {
            if (t.atMillis() <= virtualNow) {
                due.add(t);
            }
        }
        tasks.removeAll(due);
        for (Task t : due) {
            t.runnable().run();
        }
    }

    public synchronized int pending() {
        return tasks.size();
    }
}
