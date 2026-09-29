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
    private final LongAdder receiveBufferOverflows = new LongAdder();
    private final LongAdder negotiationsSucceeded = new LongAdder();
    private final LongAdder negotiationsFailed = new LongAdder();

    public void sessionActive() { activeSessions.incrementAndGet(); }
    public void sessionInactive() { activeSessions.updateAndGet(v -> Math.max(0, v - 1)); }

    public void frameIn() { framesIn.increment(); }
    public void frameOut() { framesOut.increment(); }
    public void connectionAccepted() { connectionsAccepted.increment(); }
    public void connectionRejected() { connectionsRejected.increment(); }
    public void retryExhausted() { sessionsTerminatedByRetry.incrementAndGet(); }
    public void receiveBufferOverflow() { receiveBufferOverflows.increment(); }
    public void negotiationSucceeded() { negotiationsSucceeded.increment(); }
    public void negotiationFailed() { negotiationsFailed.increment(); }

    public long receiveBufferOverflows() { return receiveBufferOverflows.sum(); }
    public long negotiationsSucceeded() { return negotiationsSucceeded.sum(); }
    public long negotiationsFailed() { return negotiationsFailed.sum(); }

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
        m.put("receiveBufferOverflows", receiveBufferOverflows.sum());
        m.put("negotiationsSucceeded", negotiationsSucceeded.sum());
        m.put("negotiationsFailed", negotiationsFailed.sum());
        m.put("activeSessions", (long) activeSessions.get());
        for (Map.Entry<Integer, LongAdder> e : errors.entrySet()) {
            m.put("error[" + e.getKey() + "]", e.getValue().sum());
        }
        return m;
    }
}
