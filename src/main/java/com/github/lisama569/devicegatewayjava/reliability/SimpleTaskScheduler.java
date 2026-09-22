package com.github.lisama569.devicegatewayjava.reliability;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 基于 ScheduledExecutorService 的生产实现。 */
public final class SimpleTaskScheduler implements TaskScheduler, AutoCloseable {

    private final ScheduledExecutorService executor;

    public SimpleTaskScheduler(int threads) {
        this.executor = Executors.newScheduledThreadPool(Math.max(1, threads), r -> {
            Thread t = new Thread(r, "gw-timer");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public ScheduledTask schedule(Runnable task, long delayMs) {
        var future = executor.schedule(task, Math.max(0, delayMs), TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }

    @Override
    public void shutdownNow() {
        executor.shutdownNow();
    }

    @Override
    public void close() {
        shutdownNow();
    }
}
