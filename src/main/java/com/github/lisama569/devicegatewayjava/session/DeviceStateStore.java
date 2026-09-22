package com.github.lisama569.devicegatewayjava.session;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Per-device durable-in-process state: the high-water uplink sequence and the
 * set of applied business keys. Survives reconnect, so:
 * <ul>
 *   <li>re-delivery after reconnect is detected as DUPLICATE / ROLLBACK,</li>
 *   <li>the high-water mark and the business write happen under one lock,
 *       so a crash between "applied" and "advanced" cannot happen in-process
 *       and no half-update is observable.</li>
 * </ul>
 */
@Component
public class DeviceStateStore {

    public static final class DeviceState {
        final Object lock = new Object();
        long lastAppliedSeq;
        final Set<String> appliedKeys = ConcurrentHashMap.newKeySet();

        public long lastAppliedSeq() {
            synchronized (lock) {
                return lastAppliedSeq;
            }
        }

        public boolean containsKey(String key) {
            return appliedKeys.contains(key);
        }

        public int keyCount() {
            return appliedKeys.size();
        }

        /**
         * Classify the sequence; only ACCEPT advances the water mark AND records
         * the business key atomically.
         */
        public SeqDecision submit(long seq, String businessKey) {
            synchronized (lock) {
                if (seq <= 0) {
                    return SeqDecision.OUT_OF_ORDER;
                }
                if (seq == lastAppliedSeq + 1) {
                    lastAppliedSeq = seq;
                    if (businessKey != null) {
                        appliedKeys.add(businessKey);
                    }
                    return SeqDecision.ACCEPT;
                }
                if (seq == lastAppliedSeq) {
                    return SeqDecision.DUPLICATE;
                }
                if (seq < lastAppliedSeq) {
                    return SeqDecision.ROLLBACK;
                }
                return SeqDecision.OUT_OF_ORDER;
            }
        }
    }

    private final Map<String, DeviceState> devices = new ConcurrentHashMap<>();

    public DeviceState get(String deviceId) {
        return devices.computeIfAbsent(deviceId, k -> new DeviceState());
    }

    /** Test helper: forget everything. */
    public void reset() {
        devices.clear();
    }
}
