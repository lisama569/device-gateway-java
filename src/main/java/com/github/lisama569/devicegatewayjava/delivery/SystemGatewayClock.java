package com.github.lisama569.devicegatewayjava.delivery;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** Wall-clock production implementation of {@link GatewayClock}. */
@Component
public class SystemGatewayClock implements GatewayClock, AutoCloseable {

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(1, new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "gateway-clock");
                    t.setDaemon(true);
                    return t;
                }
            });

    @Override
    public long nowMillis() {
        return System.currentTimeMillis();
    }

    @Override
    public void schedule(Runnable task, long delayMillis) {
        scheduler.schedule(task, Math.max(0, delayMillis), TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
