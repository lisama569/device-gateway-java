package com.github.lisama569.devicegatewayjava.state;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * 单设备业务状态：严格递增序号 + 有界滑动窗口去重。
 * 规则（确定、可解释）：
 * - seq == lastApplied+1         -> APPLIED（唯一会更新业务状态的路径）
 * - seq <= lastApplied 且落在最近 window 个已生效序号内 -> DUPLICATE（幂等，不再生效）
 * - seq <= lastApplied 且已滑出窗口                    -> EXPIRED（明确拒绝）
 * - seq >  lastApplied+1（跳号）                       -> OUT_OF_ORDER（明确拒绝）
 * 判定与更新在同一把锁内完成。
 */
public final class DeviceState {

    private final String deviceId;
    private final long window;
    private long lastApplied = 0;
    private final Deque<Long> recent = new ArrayDeque<>();
    private final Set<Long> recentSet = new HashSet<>();
    private long appliedCount = 0;
    private byte[] lastPayload = new byte[0];

    public DeviceState(String deviceId, long window) {
        this.deviceId = deviceId;
        this.window = window;
    }

    public String deviceId() { return deviceId; }

    public long lastAppliedSeq() {
        synchronized (this) { return lastApplied; }
    }

    public long appliedCount() {
        synchronized (this) { return appliedCount; }
    }

    public byte[] lastPayload() {
        synchronized (this) { return lastPayload.clone(); }
    }

    public ApplyOutcome submit(long seq, byte[] payload) {
        synchronized (this) {
            if (seq == lastApplied + 1) {
                lastApplied = seq;
                appliedCount++;
                lastPayload = payload == null ? new byte[0] : payload.clone();
                recent.addLast(seq);
                recentSet.add(seq);
                while (recent.size() > window) {
                    recentSet.remove(recent.pollFirst());
                }
                return ApplyOutcome.APPLIED;
            }
            if (seq <= lastApplied) {
                if (seq > lastApplied - window && recentSet.contains(seq)) {
                    return ApplyOutcome.DUPLICATE;
                }
                return ApplyOutcome.EXPIRED;
            }
            return ApplyOutcome.OUT_OF_ORDER;
        }
    }
}
