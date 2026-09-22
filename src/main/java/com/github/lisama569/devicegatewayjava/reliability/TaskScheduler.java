package com.github.lisama569.devicegatewayjava.reliability;

/** 可替换的定时器抽象，生产用线程池实现，测试用手动步进实现。 */
public interface TaskScheduler {

    ScheduledTask schedule(Runnable task, long delayMs);

    void shutdownNow();
}
