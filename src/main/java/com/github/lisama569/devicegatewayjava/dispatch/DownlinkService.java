package com.github.lisama569.devicegatewayjava.dispatch;

import com.github.lisama569.devicegatewayjava.error.GatewayErrorCode;
import com.github.lisama569.devicegatewayjava.metrics.GatewayMetrics;
import com.github.lisama569.devicegatewayjava.protocol.FrameCodec;
import com.github.lisama569.devicegatewayjava.protocol.FrameConstants;
import com.github.lisama569.devicegatewayjava.protocol.MessageType;
import com.github.lisama569.devicegatewayjava.reliability.ReliableDownlink;
import com.github.lisama569.devicegatewayjava.session.GatewaySession;
import com.github.lisama569.devicegatewayjava.session.SessionRegistry;
import com.github.lisama569.devicegatewayjava.transport.ConnectionContext;
import com.github.lisama569.devicegatewayjava.transport.ConnectionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/** 本机管理面使用的可靠下行投递。 */
public final class DownlinkService {

    private static final Logger log = LoggerFactory.getLogger(DownlinkService.class);

    public record Result(boolean accepted, int errorCode, String detail) {}

    private final SessionRegistry sessions;
    private final ConnectionRegistry connections;
    private final ReliableDownlink reliable;
    private final GatewayMetrics metrics;
    private final ConcurrentMap<String, AtomicLong> seqByDevice = new ConcurrentHashMap<>();

    public DownlinkService(SessionRegistry sessions, ConnectionRegistry connections,
                           ReliableDownlink reliable, GatewayMetrics metrics) {
        this.sessions = sessions;
        this.connections = connections;
        this.reliable = reliable;
        this.metrics = metrics;
    }

    public Result sendReliable(String deviceId, byte[] payload) {
        Optional<GatewaySession> sessionOpt = sessions.get(deviceId);
        if (sessionOpt.isEmpty()) {
            return new Result(false, GatewayErrorCode.SESSION_CLOSED.code(),
                    "no active session for device " + deviceId);
        }
        GatewaySession session = sessionOpt.get();
        if (session.closed()) {
            return new Result(false, GatewayErrorCode.SESSION_CLOSED.code(), "session closed");
        }
        ConnectionContext ctx = connections.get(session.connectionId());
        if (ctx == null) {
            return new Result(false, GatewayErrorCode.SESSION_CLOSED.code(), "connection gone");
        }

        long seq = seqByDevice.computeIfAbsent(deviceId, k -> new AtomicLong()).incrementAndGet();
        if (seq > 0xFFFFFFFFL) {
            return new Result(false, GatewayErrorCode.SEQ_OVERFLOW.code(), "downlink seq overflow");
        }
        byte[] frame = FrameCodec.encode(MessageType.DOWNLINK,
                FrameConstants.FLAG_RELIABLE, seq, payload);

        // offer 内会立即发送第一次；IO 异常在 ReliableDownlink 内部按发送失败处理
        reliable.offer(session.connectionId(), seq, frame, f -> {
            boolean queued = ctx.writeFrame(f);
            metrics.frameOut();
            return queued;
        }, () -> {
            metrics.retryExhausted();
            String reason = GatewayErrorCode.RETRY_EXHAUSTED.description() + " seq=" + seq;
            log.error("retry exhausted device={} conn={} seq={} -> session terminated with code {}",
                    deviceId, session.connectionId(), seq, GatewayErrorCode.RETRY_EXHAUSTED.code());
            // 不在此同步关 socket：终止动作排到写线程队列末尾，保证最后一份重传先送达
            ctx.terminateAfterDrain(reason);
        });
        log.info("downlink enqueued device={} conn={} seq={} bytes={}",
                deviceId, session.connectionId(), seq, payload == null ? 0 : payload.length);
        return new Result(true, 0, "seq=" + seq);
    }
}
