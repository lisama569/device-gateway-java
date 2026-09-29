package com.github.lisama569.devicegatewayjava.metrics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** 网关指标：按错误码分类计数，供可观测与测试断言。 */
public final class GatewayMetrics {

    private final Map<Integer, LongAdder> errors = new ConcurrentHashMap<>();
    private final LongAdder framesIn = new LongAdder();
    private final LongAdder framesOut = new LongAdder();
    private final LongAdder connectionsAccepted = new LongAdder();
    private final LongAdder connectionsRejected = new LongAdder();
    private final AtomicLong sessionsTerminatedByRetry = new AtomicLong();
    private final AtomicLong activeSessions = new AtomicLong();

    // 能力协商三分类计数（与连接最终“谈成/版本无交集/能力不识别”一一对应）
    private final AtomicLong negotiationsSucceeded = new AtomicLong();
    private final AtomicLong negotiationsVersionFailed = new AtomicLong();
    private final AtomicLong negotiationsCapabilityFailed = new AtomicLong();
    /** 接收侧残留超限而被结束的连接次数（4003，按连接容量保护）。 */
    private final AtomicLong receiveBufferTerminations = new AtomicLong();
    /** 当前生效的按连接接收残留硬上限（管理接口可见）。 */
    private volatile long receiveResidualLimitBytes = -1;

    public void negotiationSucceeded() { negotiationsSucceeded.incrementAndGet(); }
    public void negotiationVersionFailed() { negotiationsVersionFailed.incrementAndGet(); }
    public void negotiationCapabilityFailed() { negotiationsCapabilityFailed.incrementAndGet(); }
    public void receiveBufferTerminated() { receiveBufferTerminations.incrementAndGet(); }
    public void setReceiveResidualLimitBytes(long limit) { this.receiveResidualLimitBytes = limit; }

    public long negotiationsSucceeded() { return negotiationsSucceeded.get(); }
    public long negotiationsVersionFailed() { return negotiationsVersionFailed.get(); }
    public long negotiationsCapabilityFailed() { return negotiationsCapabilityFailed.get(); }
    public long receiveBufferTerminations() { return receiveBufferTerminations.get(); }

    public void sessionActive() { activeSessions.incrementAndGet(); }
    public void sessionInactive() { activeSessions.updateAndGet(v -> Math.max(0, v - 1)); }

    public void frameIn() { framesIn.increment(); }
    public void frameOut() { framesOut.increment(); }
    public void connectionAccepted() { connectionsAccepted.increment(); }
    public void connectionRejected() { connectionsRejected.increment(); }
    public void retryExhausted() { sessionsTerminatedByRetry.incrementAndGet(); }

    public void error(int code) {
        errors.computeIfAbsent(code, k -> new LongAdder()).increment();
    }

    public long errorCount(int code) {
        LongAdder a = errors.get(code);
        return a == null ? 0 : a.sum();
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("framesIn", framesIn.sum());
        m.put("framesOut", framesOut.sum());
        m.put("connectionsAccepted", connectionsAccepted.sum());
        m.put("connectionsRejected", connectionsRejected.sum());
        m.put("sessionsTerminatedByRetry", sessionsTerminatedByRetry.get());
        m.put("activeSessions", (long) activeSessions.get());
        m.put("negotiationsSucceeded", negotiationsSucceeded.get());
        m.put("negotiationsVersionFailed", negotiationsVersionFailed.get());
        m.put("negotiationsCapabilityFailed", negotiationsCapabilityFailed.get());
        m.put("receiveBufferTerminations", receiveBufferTerminations.get());
        m.put("receiveResidualLimitBytes", receiveResidualLimitBytes);
        for (Map.Entry<Integer, LongAdder> e : errors.entrySet()) {
            m.put("error[" + e.getKey() + "]", e.getValue().sum());
        }
        return m;
    }
}
