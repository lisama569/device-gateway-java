package com.github.lisama569.devicegatewayjava.gateway;

import com.github.lisama569.devicegatewayjava.protocol.ErrorCode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/** Counters for externally observable error categories and lifecycle events. */
@Component
public class GatewayMetrics {

    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();

    public long increment(String name) {
        return counters.computeIfAbsent(name, k -> new AtomicLong()).incrementAndGet();
    }

    public long increment(ErrorCode code) {
        increment("errors.total");
        increment("errors.category." + code.category());
        return increment("errors.code." + code.name());
    }

    public long get(String name) {
        AtomicLong c = counters.get(name);
        return c == null ? 0 : c.get();
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> out = new LinkedHashMap<>();
        counters.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> out.put(e.getKey(), e.getValue().get()));
        return out;
    }

    public void reset() {
        counters.clear();
    }
}
