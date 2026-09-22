package com.github.lisama569.devicegatewayjava.delivery;

/** Injectable clock so retry/timeout behaviour is deterministic in tests. */
public interface GatewayClock {

    long nowMillis();

    void schedule(Runnable task, long delayMillis);
}
