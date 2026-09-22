package com.github.lisama569.devicegatewayjava.reliability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 下行可靠投递（按连接分组）。
 *
 * 收敛规则：
 * - offer 立即发送第 1 次并登记定时器；
 * - ACK 在任意重试到达都会取消定时器并移除记录（先到的 ACK 生效，之后的重复 ACK -> DUPLICATE_ACK）；
 * - 每次超时重发一次；重发次数达到 maxRetries 仍未确认，则终止会话（RETRY_EXHAUSTED），
 *   即“发送次数 = 1 + maxRetries”，结论确定且有上限；
 * - ACK 指向未知序号 -> UNKNOWN_SEQ（可能是从未发出或已终止）。
 * 所有 per-connection 状态在同一把锁内变更，杜绝半更新。
 */
public final class ReliableDownlink {

    @FunctionalInterface
    public interface FrameSink {
        boolean send(byte[] frame) throws IOException;
    }

    private static final class Pending {
        byte[] frame;
        FrameSink sink;
        Runnable onExhausted;
        int attempts;
        ScheduledTask timer;
    }

    /** 每连接状态：待确认帧 + 有界的近期已确认序号（区分重复 ACK 与未知 ACK）。 */
    private static final class ConnState {
        final ConcurrentHashMap<Long, Pending> pending = new ConcurrentHashMap<>();
        final Deque<Long> ackedRecent = new ArrayDeque<>();
        final Set<Long> ackedSet = new HashSet<>();
        static final int ACK_WINDOW = 64;
    }

    private static final Logger log = LoggerFactory.getLogger(ReliableDownlink.class);

    private final TaskScheduler scheduler;
    private final int maxRetries;
    private final long ackTimeoutMs;
    private final Map<String, ConnState> byConnection = new ConcurrentHashMap<>();

    public ReliableDownlink(TaskScheduler scheduler, int maxRetries, long ackTimeoutMs) {
        this.scheduler = scheduler;
        this.maxRetries = Math.max(0, maxRetries);
        this.ackTimeoutMs = ackTimeoutMs;
    }

    public void offer(String connectionId, long seq, byte[] frame, FrameSink sink, Runnable onExhausted) {
        ConnState state = stateFor(connectionId);
        Pending p = new Pending();
        p.frame = frame;
        p.sink = sink;
        p.onExhausted = onExhausted;
        synchronized (state) {
            if (state.pending.containsKey(seq)) {
                throw new IllegalStateException("duplicate downlink seq " + seq + " conn=" + connectionId);
            }
            state.pending.put(seq, p);
        }
        doSend(connectionId, seq, p);
    }

    public AckStatus handleAck(String connectionId, long seq) {
        ConnState state = byConnection.get(connectionId);
        if (state == null) return AckStatus.UNKNOWN_SEQ;
        synchronized (state) {
            Pending p = state.pending.remove(seq);
            if (p != null) {
                p.timer.cancel();
                state.ackedRecent.addLast(seq);
                state.ackedSet.add(seq);
                while (state.ackedRecent.size() > ConnState.ACK_WINDOW) {
                    state.ackedSet.remove(state.ackedRecent.pollFirst());
                }
                log.debug("ack accepted conn={} seq={} attempts={}", connectionId, seq, p.attempts);
                return AckStatus.ACKED;
            }
            if (state.ackedSet.contains(seq)) {
                return AckStatus.DUPLICATE_ACK;
            }
            return AckStatus.UNKNOWN_SEQ;
        }
    }

    public void abortConnection(String connectionId) {
        ConnState state = byConnection.remove(connectionId);
        if (state == null) return;
        synchronized (state) {
            for (Pending p : state.pending.values()) {
                p.timer.cancel();
            }
            state.pending.clear();
            state.ackedRecent.clear();
            state.ackedSet.clear();
        }
    }

    /** 关闭全部连接的待确认帧与定时器（进程关闭用）。 */
    public void abortAll() {
        for (String connectionId : byConnection.keySet().toArray(new String[0])) {
            abortConnection(connectionId);
        }
    }

    public int pendingCount(String connectionId) {
        ConnState state = byConnection.get(connectionId);
        return state == null ? 0 : state.pending.size();
    }

    private void doSend(String connectionId, long seq, Pending p) {
        p.attempts++;
        boolean delivered;
        try {
            delivered = p.sink.send(p.frame);
        } catch (IOException e) {
            delivered = false;
        }
        log.debug("downlink sent conn={} seq={} attempt={} queued={}", connectionId, seq, p.attempts, delivered);

        ConnState state = stateFor(connectionId);
        synchronized (state) {
            // 可能在 send 期间已被 ACK 移除
            if (!state.pending.containsKey(seq)) return;
            if (p.attempts > maxRetries) {
                state.pending.remove(seq);
                log.warn("downlink retry exhausted conn={} seq={} attempts={} -> terminating session",
                        connectionId, seq, p.attempts);
                Runnable action = p.onExhausted;
                if (action != null) action.run();
                return;
            }
            p.timer = scheduler.schedule(() -> onTimeout(connectionId, seq), ackTimeoutMs);
        }
    }

    private void onTimeout(String connectionId, long seq) {
        ConnState state = byConnection.get(connectionId);
        if (state == null) return;
        Pending p;
        synchronized (state) {
            p = state.pending.get(seq);
            if (p == null) return; // 已 ACK 或已终止
        }
        log.debug("downlink timeout conn={} seq={} retry={}/{}", connectionId, seq, p.attempts, maxRetries);
        doSend(connectionId, seq, p);
    }

    private ConnState stateFor(String connectionId) {
        return byConnection.computeIfAbsent(connectionId, k -> new ConnState());
    }
}
