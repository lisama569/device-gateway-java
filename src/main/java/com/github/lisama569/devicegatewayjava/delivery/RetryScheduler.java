package com.github.lisama569.devicegatewayjava.delivery;

/**
 * Schedules retry/timeout work. Production delegates to the virtual-or-wall
 * {@link GatewayClock}; tests can substitute a controllable scheduler and fire
 * timers explicitly, which makes retransmission timing race-free regardless of
 * real I/O thread scheduling.
 */
@FunctionalInterface
public interface RetryScheduler {

    void schedule(Runnable task, long delayMillis);
}
