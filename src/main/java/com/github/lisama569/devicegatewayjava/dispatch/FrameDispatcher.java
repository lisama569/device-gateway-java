package com.github.lisama569.devicegatewayjava.dispatch;

import com.github.lisama569.devicegatewayjava.error.GatewayErrorCode;
import com.github.lisama569.devicegatewayjava.error.GatewayException;
import com.github.lisama569.devicegatewayjava.auth.Authenticator;
import com.github.lisama569.devicegatewayjava.metrics.GatewayMetrics;
import com.github.lisama569.devicegatewayjava.negotiation.NegotiationFrames;
import com.github.lisama569.devicegatewayjava.negotiation.NegotiationOutcome;
import com.github.lisama569.devicegatewayjava.negotiation.NegotiationRequest;
import com.github.lisama569.devicegatewayjava.negotiation.Negotiator;
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
import java.util.stream.Collectors;

/**
 * 将已解析帧分发到协商/认证/会话/状态/可靠投递子系统。
 * 每条拒绝路径都映射为可区分错误码并记录“判定依据”，且：
 * - NEGOTIATE 是连接的唯一合法首帧；未协商会话发业务帧 -> 2008（连接保留，可回头协商）；
 * - 协商失败（版本无交集/能力不识别/负载畸形）发 ERROR 后结束连接，绝不按默认版本硬套；
 * - 协商定稿即会话硬约束：越权能力/扩展 -> 2009 单帧拒绝，连接与后续合法报文不受影响；
 * - 定稿后再次 NEGOTIATE -> 2010 稳定拒绝，规则不得中途更换；
 * - 未认证会话永远访问不到 DeviceState；错误帧不携带任何凭证内容。
 */
public final class FrameDispatcher {

    private static final Logger log = LoggerFactory.getLogger(FrameDispatcher.class);

    private final Authenticator authenticator;
    private final SessionRegistry sessions;
    private final DeviceStateStore states;
    private final GatewayMetrics metrics;
    private final Negotiator negotiator;

    public FrameDispatcher(Authenticator authenticator, SessionRegistry sessions,
                           DeviceStateStore states, GatewayMetrics metrics, Negotiator negotiator) {
        this.authenticator = authenticator;
        this.sessions = sessions;
        this.states = states;
        this.metrics = metrics;
        this.negotiator = negotiator;
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
                case NEGOTIATE -> handleNegotiate(ctx, frame);
                case LOGIN -> handleLogin(ctx, frame);
                case LOGOUT -> handleLogout(ctx, frame);
                case DATA -> handleData(ctx, frame);
                case ACK -> handleAck(ctx, frame);
                case DOWNLINK, ERROR, NEGOTIATE_ACK -> sendError(ctx, GatewayErrorCode.MALFORMED_PAYLOAD,
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

    private void handleNegotiate(ConnectionContext ctx, Frame frame) {
        GatewaySession session = ctx.session();
        // 协商只能发生在会话开头：已定稿（无论是否已登录）再发 NEGOTIATE，稳定拒绝且不改既有规则
        if (session.negotiationSettled()) {
            throw new GatewayException(GatewayErrorCode.NEGOTIATION_SETTLED,
                    "session already using version " + session.negotiatedVersion()
                            + " caps=" + session.capabilities().stream().map(Enum::name).toList());
        }

        final NegotiationRequest request;
        try {
            request = NegotiationFrames.parseRequest(frame.payload());
        } catch (GatewayException malformed) {
            metrics.error(malformed.errorCode().code());
            failNegotiation(ctx, frame.seq(), malformed.errorCode(), malformed.getMessage());
            return;
        }

        NegotiationOutcome outcome = negotiator.evaluate(request);
        sendNegotiationAck(ctx, frame.seq(), outcome);
        switch (outcome.status()) {
            case AGREED -> {
                session.settleNegotiation(outcome.selectedVersion(), outcome.selectedCapabilities());
                metrics.negotiationSucceeded();
                log.info("negotiation settled conn={} version={} caps={}",
                        ctx.connectionId(), outcome.selectedVersion(),
                        outcome.selectedCapabilities().stream().map(Enum::name).toList());
            }
            case VERSION_NO_INTERSECTION -> {
                metrics.negotiationVersionFailed();
                metrics.error(GatewayErrorCode.VERSION_MISMATCH.code());
                terminateNegotiation(ctx, frame.seq(), GatewayErrorCode.VERSION_MISMATCH,
                        "no common protocol version: device=[" + request.minVersion() + ","
                                + request.maxVersion() + "] gateway=["
                                + negotiator.gatewayMinVersion() + "," + negotiator.gatewayMaxVersion() + "]");
            }
            case UNSUPPORTED_CAPABILITY -> {
                metrics.negotiationCapabilityFailed();
                metrics.error(GatewayErrorCode.UNSUPPORTED_CAPABILITY.code());
                String codes = outcome.unknownCodes().stream()
                        .map(b -> String.valueOf(b & 0xFF)).collect(Collectors.joining(","));
                terminateNegotiation(ctx, frame.seq(), GatewayErrorCode.UNSUPPORTED_CAPABILITY,
                        "unrecognized or unsupported capability code(s): " + codes);
            }
        }
    }

    /** 协商失败的统一收口：NEGOTIATE_ACK（机器可判）+ ERROR（可读原因）发出后结束连接，不留默认规则。 */
    private void failNegotiation(ConnectionContext ctx, long refSeq, GatewayErrorCode code, String basis) {
        log.warn("negotiation failed conn={} code={} basis={}", ctx.connectionId(), code.code(), basis);
        terminateNegotiation(ctx, refSeq, code, basis);
    }

    /** 保证两份协商结论帧先送达，再结束连接；不计认证指标（连接从未成为正常设备会话）。 */
    private void terminateNegotiation(ConnectionContext ctx, long refSeq, GatewayErrorCode code, String basis) {
        sendError(ctx, code, basis, refSeq);
        ctx.terminateAfterDrain("negotiation failed: " + code.code() + " " + basis);
    }

    private void sendNegotiationAck(ConnectionContext ctx, long refSeq, NegotiationOutcome outcome) {
        byte[] raw = FrameCodec.encode(MessageType.NEGOTIATE_ACK, (byte) 0, refSeq,
                NegotiationFrames.encodeAck(outcome));
        try {
            ctx.writeFrame(raw);
            metrics.frameOut();
        } catch (IOException e) {
            log.debug("send negotiate-ack failed conn={}: {}", ctx.connectionId(), e.getMessage());
        }
    }

    private void handleLogin(ConnectionContext ctx, Frame frame) {
        GatewaySession session = ctx.session();
        if (!session.negotiationSettled()) {
            throw new GatewayException(GatewayErrorCode.NEGOTIATION_REQUIRED,
                    "LOGIN before capability negotiation");
        }
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
        // 能力硬约束：DATA FLAGS 上只允许出现本会话谈定的能力位；任何越界扩展位单帧拒绝，连接保留
        int allowed = session.allowedDataFlags() & 0xFF;
        int used = frame.flags() & 0xFF;
        if ((used & ~allowed) != 0) {
            int offending = used & ~allowed;
            metrics.error(GatewayErrorCode.CAPABILITY_VIOLATION.code());
            log.warn("capability violation device={} conn={} seq={} flags=0x{} agreedCaps={} -> single frame rejected",
                    session.deviceId(), ctx.connectionId(), frame.seq(),
                    Integer.toHexString(used), session.capabilities().stream().map(Enum::name).toList());
            sendError(ctx, GatewayErrorCode.CAPABILITY_VIOLATION,
                    "DATA flags 0x" + Integer.toHexString(offending)
                            + " not negotiated for this session (allowed=0x" + Integer.toHexString(allowed) + ")",
                    frame.seq());
            return;
        }
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
        if (!session.negotiationSettled()) {
            throw new GatewayException(GatewayErrorCode.NEGOTIATION_REQUIRED,
                    frame.type() + " before capability negotiation");
        }
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
