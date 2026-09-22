package com.github.lisama569.devicegatewayjava.dispatch;

import com.github.lisama569.devicegatewayjava.error.GatewayErrorCode;
import com.github.lisama569.devicegatewayjava.error.GatewayException;
import com.github.lisama569.devicegatewayjava.auth.Authenticator;
import com.github.lisama569.devicegatewayjava.metrics.GatewayMetrics;
import com.github.lisama569.devicegatewayjava.protocol.ErrorFrames;
import com.github.lisama569.devicegatewayjava.protocol.Frame;
import com.github.lisama569.devicegatewayjava.protocol.FrameDecoder;
import com.github.lisama569.devicegatewayjava.protocol.FrameCodec;
import com.github.lisama569.devicegatewayjava.protocol.MessageType;
import com.github.lisama569.devicegatewayjava.reliability.AckStatus;
import com.github.lisama569.devicegatewayjava.session.GatewaySession;
import com.github.lisama569.devicegatewayjava.session.SessionRegistry;
import com.github.lisama569.devicegatewayjava.state.ApplyOutcome;
import com.github.lisama569.devicegatewayjava.state.DeviceState;
import com.github.lisama569.devicegatewayjava.state.DeviceStateStore;
import com.github.lisama569.devicegatewayjava.transport.ConnectionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * 将已解析帧分发到认证/会话/状态/可靠投递子系统。
 * 每条拒绝路径都映射为可区分错误码并记录“判定依据”，且：
 * - 未认证会话永远访问不到 DeviceState；
 * - 重复/乱序/过期在业务状态锁内得出确定结论，绝不静默；
 * - 错误帧不携带任何凭证内容。
 */
public final class FrameDispatcher {

    private static final Logger log = LoggerFactory.getLogger(FrameDispatcher.class);

    private final Authenticator authenticator;
    private final SessionRegistry sessions;
    private final DeviceStateStore states;
    private final GatewayMetrics metrics;

    public FrameDispatcher(Authenticator authenticator, SessionRegistry sessions,
                           DeviceStateStore states, GatewayMetrics metrics) {
        this.authenticator = authenticator;
        this.sessions = sessions;
        this.states = states;
        this.metrics = metrics;
    }

    /** 解码层错误 -> 错误帧 + 计数；连接保持。 */
    public void dispatchDecodeError(ConnectionContext ctx, FrameDecoder.Result.FrameError err) {
        metrics.error(err.code().code());
        log.warn("protocol reject conn={} code={} reason={} discarded={}",
                ctx.connectionId(), err.code().code(), err.reason(), err.discardedBytes());
        sendError(ctx, err.code(), err.reason(), 0);
    }

    public void dispatch(ConnectionContext ctx, Frame frame) {
        metrics.frameIn();
        GatewaySession session = ctx.session();
        log.debug("recv conn={} device={} type={} seq={} payloadBytes={}",
                ctx.connectionId(), session.deviceId(), frame.type(), frame.seq(),
                frame.payload() == null ? 0 : frame.payload().length);
        try {
            switch (frame.type()) {
                case LOGIN -> handleLogin(ctx, frame);
                case LOGOUT -> handleLogout(ctx, frame);
                case DATA -> handleData(ctx, frame);
                case ACK -> handleAck(ctx, frame);
                case DOWNLINK, ERROR -> sendError(ctx, GatewayErrorCode.MALFORMED_PAYLOAD,
                        frame.type() + " is not accepted from device", frame.seq());
            }
        } catch (GatewayException e) {
            metrics.error(e.errorCode().code());
            log.warn("rejected conn={} device={} seq={} code={} basis={}",
                    ctx.connectionId(), session.deviceId(), frame.seq(),
                    e.errorCode().code(), e.getMessage());
            sendError(ctx, e.errorCode(), e.getMessage(), frame.seq());
        }
    }

    private void handleLogin(ConnectionContext ctx, Frame frame) {
        GatewaySession session = ctx.session();
        if (session.authenticated()) {
            throw new GatewayException(GatewayErrorCode.ALREADY_AUTHENTICATED,
                    "connection already authenticated as " + session.deviceId());
        }
        // 认证失败在此抛出（3xxx），不会触碰业务状态；secret 不出现在异常消息中
        String deviceId = authenticator.authenticate(frame.payload());
        session.markAuthenticated(deviceId);
        Optional<GatewaySession> old = sessions.registerAuthenticated(session);
        metrics.sessionActive();
        old.ifPresent(previous -> {
            if (previous.closed()) {
                // 旧连接已死（如 TCP 断开未及清理），属重连：替换陈旧映射，不当冲突
                log.info("reconnect device={} newConn={} replaces stale closed conn={}",
                        deviceId, session.connectionId(), previous.connectionId());
                return;
            }
            metrics.error(GatewayErrorCode.SESSION_CONFLICT.code());
            log.warn("duplicate login device={} newConn={} oldConn={} -> old session terminated",
                    deviceId, session.connectionId(), previous.connectionId());
            // 通知旧连接（由其读循环完成注销清理）
            previous.close(GatewayErrorCode.SESSION_CONFLICT.description());
        });
        log.info("login ok device={} conn={}", deviceId, session.connectionId());
        sendAck(ctx, frame.seq());
    }

    private void handleLogout(ConnectionContext ctx, Frame frame) {
        GatewaySession session = requireAuth(ctx, frame);
        log.info("logout device={} conn={}", session.deviceId(), ctx.connectionId());
        sendAck(ctx, frame.seq());
        sessions.unregister(session);
        metrics.sessionInactive();
        session.close("client logout");
        ctx.requestClose("client logout");
    }

    private void handleData(ConnectionContext ctx, Frame frame) {
        GatewaySession session = requireAuth(ctx, frame);
        DeviceState state = states.get(session.deviceId());
        ApplyOutcome outcome = state.submit(frame.seq(), frame.payload());
        switch (outcome) {
            case APPLIED -> {
                log.info("data applied device={} conn={} seq={} totalApplied={}",
                        session.deviceId(), ctx.connectionId(), frame.seq(), state.appliedCount());
                sendAck(ctx, frame.seq());
            }
            case DUPLICATE -> {
                metrics.error(GatewayErrorCode.DUPLICATE_MESSAGE.code());
                log.warn("duplicate data device={} conn={} seq={} lastApplied={} -> not reapplied",
                        session.deviceId(), ctx.connectionId(), frame.seq(), state.lastAppliedSeq());
                sendError(ctx, GatewayErrorCode.DUPLICATE_MESSAGE,
                        "seq " + frame.seq() + " already applied (idempotent)", frame.seq());
            }
            case EXPIRED -> {
                metrics.error(GatewayErrorCode.SEQ_OUT_OF_ORDER.code());
                log.warn("expired seq device={} conn={} seq={} lastApplied={} -> rejected",
                        session.deviceId(), ctx.connectionId(), frame.seq(), state.lastAppliedSeq());
                sendError(ctx, GatewayErrorCode.SEQ_OUT_OF_ORDER,
                        "seq " + frame.seq() + " rolled back beyond dedupe window", frame.seq());
            }
            case OUT_OF_ORDER -> {
                metrics.error(GatewayErrorCode.SEQ_OUT_OF_ORDER.code());
                log.warn("out-of-order seq device={} conn={} seq={} expected={} -> rejected",
                        session.deviceId(), ctx.connectionId(), frame.seq(), state.lastAppliedSeq() + 1);
                sendError(ctx, GatewayErrorCode.SEQ_OUT_OF_ORDER,
                        "seq " + frame.seq() + " ahead of expected " + (state.lastAppliedSeq() + 1),
                        frame.seq());
            }
        }
    }

    private void handleAck(ConnectionContext ctx, Frame frame) {
        GatewaySession session = requireAuth(ctx, frame);
        long seq = frame.seq();
        AckStatus status = ctx.reliableDownlink().handleAck(ctx.connectionId(), seq);
        switch (status) {
            case ACKED -> log.info("downlink acked device={} conn={} seq={}",
                    session.deviceId(), ctx.connectionId(), seq);
            case DUPLICATE_ACK -> {
                metrics.error(GatewayErrorCode.DUPLICATE_MESSAGE.code());
                log.warn("duplicate ack device={} conn={} seq={} -> ignored deterministically",
                        session.deviceId(), ctx.connectionId(), seq);
                sendError(ctx, GatewayErrorCode.DUPLICATE_MESSAGE, "duplicate ack for seq " + seq, seq);
            }
            case UNKNOWN_SEQ -> {
                metrics.error(GatewayErrorCode.SEQ_OUT_OF_ORDER.code());
                log.warn("ack for unknown seq device={} conn={} seq={} -> rejected",
                        session.deviceId(), ctx.connectionId(), seq);
                sendError(ctx, GatewayErrorCode.SEQ_OUT_OF_ORDER,
                        "ack for unknown/expired downlink seq " + seq, seq);
            }
        }
    }

    private GatewaySession requireAuth(ConnectionContext ctx, Frame frame) {
        GatewaySession session = ctx.session();
        if (!session.authenticated()) {
            throw new GatewayException(GatewayErrorCode.NOT_AUTHENTICATED,
                    frame.type() + " before LOGIN");
        }
        return session;
    }

    private void sendAck(ConnectionContext ctx, long refSeq) {
        byte[] payload = ByteBuffer.allocate(4).putInt((int) refSeq).array();
        byte[] raw = FrameCodec.encode(MessageType.ACK, (byte) 0, refSeq, payload);
        try {
            ctx.writeFrame(raw);
            metrics.frameOut();
        } catch (IOException e) {
            log.debug("send ack failed conn={} seq={}: {}", ctx.connectionId(), refSeq, e.getMessage());
        }
    }

    private void sendError(ConnectionContext ctx, GatewayErrorCode code, String detail, long refSeq) {
        byte[] raw = ErrorFrames.encode(code, detail, refSeq);
        try {
            if (ctx.writeFrame(raw)) {
                metrics.frameOut();
            }
        } catch (IOException e) {
            log.debug("send error failed conn={} code={}: {}", ctx.connectionId(), code.code(), e.getMessage());
        }
    }
}
