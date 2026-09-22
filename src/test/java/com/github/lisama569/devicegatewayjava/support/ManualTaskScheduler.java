package com.github.lisama569.devicegatewayjava.support;

import com.github.lisama569.devicegatewayjava.reliability.ScheduledTask;
import com.github.lisama569.devicegatewayjava.reliability.TaskScheduler;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** 确定性手动时钟：时间仅在 advance() 时前进，同刻任务按注册顺序执行，无真实等待。 */
public final class ManualTaskScheduler implements TaskScheduler {

    private static final class Timer implements ScheduledTask {
        long fireAt;
        final Runnable task;
        boolean cancelled;

        Timer(long fireAt, Runnable task) {
            this.fireAt = fireAt;
            this.task = task;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }

    private long now = 0;
    private final List<Timer> timers = new ArrayList<>();

    public void advance(long deltaMs) {
        long target = now + deltaMs;
        while (true) {
            Timer next = null;
            for (Timer t : timers) {
                if (!t.cancelled && t.fireAt <= target && (next == null || t.fireAt < next.fireAt)) {
                    next = t;
                }
            }
            if (next == null) break;
            now = next.fireAt;
            next.cancelled = true; // 先标记，防止任务自身重排导致重入
            next.task.run();
        }
        now = target;
        timers.removeIf(t -> t.cancelled);
    }

    public long now() { return now; }

    public int pendingTimers() {
        timers.removeIf(t -> t.cancelled);
        return timers.size();
    }

    @Override
    public ScheduledTask schedule(Runnable task, long delayMs) {
        Timer t = new Timer(now + Math.max(0, delayMs), task);
        timers.add(t);
        return t;
    }

    @Override
    public void shutdownNow() {
        timers.clear();
    }
}
