package com.github.lisama569.devicegatewayjava.dispatch;

import com.github.lisama569.devicegatewayjava.error.GatewayErrorCode;
import com.github.lisama569.devicegatewayjava.error.GatewayException;
import com.github.lisama569.devicegatewayjava.auth.Authenticator;
import com.github.lisama569.devicegatewayjava.metrics.GatewayMetrics;
import com.github.lisama569.devicegatewayjava.negotiation.Capability;
import com.github.lisama569.devicegatewayjava.negotiation.CapabilityNegotiator;
import com.github.lisama569.devicegatewayjava.negotiation.HelloPayload;
import com.github.lisama569.devicegatewayjava.negotiation.NegotiatedProfile;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
    private final CapabilityNegotiator negotiator;

    public FrameDispatcher(Authenticator authenticator, SessionRegistry sessions,
                           DeviceStateStore states, GatewayMetrics metrics,
                           CapabilityNegotiator negotiator) {
        this.authenticator = authenticator;
        this.sessions = sessions;
        this.states = states;
        this.metrics = metrics;
        this.negotiator = negotiator;
    }

    /** 接收残留超限 -> 资源耗尽错误帧 + 计数，随后排空并关闭该连接（不影响其他连接）。 */
    public void dispatchBufferExceeded(ConnectionContext ctx, FrameDecoder.Result.BufferExceeded ex) {
        metrics.error(GatewayErrorCode.RECEIVE_BUFFER_FULL.code());
        metrics.receiveBufferOverflow();
        log.warn("receive buffer limit exceeded conn={} device={} buffered={} limit={} -> connection terminated",
                ctx.connectionId(), ctx.session().deviceId(), ex.bufferedBytes(), ex.limit());
        sendError(ctx, GatewayErrorCode.RECEIVE_BUFFER_FULL,
                "incomplete-frame residual " + ex.bufferedBytes()
                        + " bytes exceeds per-connection limit " + ex.limit(), 0);
        ctx.terminateAfterDrain(GatewayErrorCode.RECEIVE_BUFFER_FULL.description());
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
            enforceConnectionStageAndProfile(ctx, frame);
            switch (frame.type()) {
                case HELLO -> handleHello(ctx, frame);
                case LOGIN -> handleLogin(ctx, frame);
                case LOGOUT -> handleLogout(ctx, frame);
                case DATA -> handleData(ctx, frame);
                case ACK -> handleAck(ctx, frame);
                case DOWNLINK, ERROR, HELLO_ACK -> sendError(ctx, GatewayErrorCode.MALFORMED_PAYLOAD,
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

    /**
     * 连接建立阶段的能力协商：仅在未协商且未认证时允许。
     * 谈成 -> HELLO_ACK 并锁定会话约束；谈不成 -> 明确错误帧后排空关闭，绝不按默认版本硬套。
     */
    private void handleHello(ConnectionContext ctx, Frame frame) {
        GatewaySession session = ctx.session();
        if (session.authenticated()) {
            throw new GatewayException(GatewayErrorCode.NEGOTIATION_NOT_ALLOWED,
                    "session already authenticated as " + session.deviceId()
                            + ", negotiation is only allowed at connection start");
        }
        if (session.negotiated()) {
            throw new GatewayException(GatewayErrorCode.NEGOTIATION_NOT_ALLOWED,
                    "capabilities already negotiated for this session, re-negotiation rejected");
        }
        HelloPayload.Hello hello = HelloPayload.parseHello(frame.payload());
        if (hello == null) {
            throw new GatewayException(GatewayErrorCode.MALFORMED_PAYLOAD,
                    "malformed HELLO payload");
        }
        CapabilityNegotiator.Result result = negotiator.negotiate(hello);
        if (result instanceof CapabilityNegotiator.Result.Ok ok) {
            session.markNegotiated(ok.profile());
            metrics.negotiationSucceeded();
            log.info("negotiation ok conn={} version={} capabilities={}",
                    ctx.connectionId(), ok.profile().version(), ok.profile().capabilities());
            byte[] payload = HelloPayload.encodeHelloAck(ok.profile().version(),
                    List.copyOf(ok.profile().capabilities()));
            byte[] raw = FrameCodec.encode(MessageType.HELLO_ACK, (byte) 0, frame.seq(), payload);
            try {
                ctx.writeFrame(raw);
                metrics.frameOut();
            } catch (IOException e) {
                log.debug("send hello-ack failed conn={}: {}", ctx.connectionId(), e.getMessage());
            }
        } else if (result instanceof CapabilityNegotiator.Result.VersionIncompatible v) {
            metrics.negotiationFailed();
            log.warn("negotiation failed conn={} deviceRange={}..{} gatewayRange={}..{} -> connection terminated",
                    ctx.connectionId(), v.deviceMin(), v.deviceMax(),
                    v.gatewayMin(), v.gatewayMax());
            sendError(ctx, GatewayErrorCode.NEGOTIATION_VERSION_INCOMPATIBLE,
                    "device range " + v.deviceMin() + ".." + v.deviceMax()
                            + " has no overlap with gateway range "
                            + v.gatewayMin() + ".." + v.gatewayMax(), frame.seq());
            ctx.terminateAfterDrain(GatewayErrorCode.NEGOTIATION_VERSION_INCOMPATIBLE.description());
        } else if (result instanceof CapabilityNegotiator.Result.UnsupportedCapabilities u) {
            metrics.negotiationFailed();
            log.warn("negotiation failed conn={} unsupportedCapabilities={} -> connection terminated",
                    ctx.connectionId(), u.unsupported());
            sendError(ctx, GatewayErrorCode.NEGOTIATION_CAPABILITY_UNSUPPORTED,
                    "unsupported capabilities: " + String.join(",", u.unsupported()), frame.seq());
            ctx.terminateAfterDrain(GatewayErrorCode.NEGOTIATION_CAPABILITY_UNSUPPORTED.description());
        }
    }

    /**
     * 连接阶段闸门 + 协商结果硬约束。
     *
     * 阶段闸门：协商是进入业务前的必经一关。未协商成功时，除 HELLO 以外的任何帧
     * （含 LOGIN/DATA/ACK/LOGOUT）都以 {@link GatewayErrorCode#NEGOTIATION_REQUIRED}
     * 明确拒绝——该码专指“协商这一环还没完成”，与未登录(2001)、版本不匹配(1002)、
     * 能力对不上(1009)明确区分。拒绝只针对当前帧：不写任何会话状态、不按默认版本或
     * 默认能力放行，连接保持可用，设备随后补发 HELLO 即可继续。
     *
     * 硬约束（仅对已协商会话生效）：帧版本须等于选定版本；帧用到的能力/扩展字段须
     * 已授予。违规只拒绝当前帧，连接保持。
     *
     * HELLO 帧本身不受版本/能力约束（其版本字段尚无协商结论可依据），能否进入
     * handleHello 由阶段闸门按帧类型放行，再由 handleHello 按会话状态判定。
     */
    private void enforceConnectionStageAndProfile(ConnectionContext ctx, Frame frame) {
        GatewaySession session = ctx.session();
        NegotiatedProfile profile = session.negotiatedProfile();
        if (profile == null) {
            if (frame.type() != MessageType.HELLO) {
                throw new GatewayException(GatewayErrorCode.NEGOTIATION_REQUIRED,
                        frame.type() + " rejected: HELLO negotiation must succeed before login or "
                                + "business frames on this connection");
            }
            return; // HELLO：唯一允许在未协商阶段发送的帧，无既定版本/能力可校验
        }
        // 已协商：版本与能力集合是会话期硬约束（HELLO 的重发由 handleHello 按状态拒绝）
        int expectedVersion = profile.version();
        if ((frame.version() & 0xFF) != expectedVersion) {
            throw new GatewayException(GatewayErrorCode.VERSION_MISMATCH,
                    "frame version " + (frame.version() & 0xFF)
                            + " != session negotiated version " + expectedVersion);
        }
        Set<String> granted = profile.capabilities();
        Set<Capability> required = Capability.requiredBy(frame);
        List<String> missing = required.stream()
                .map(Capability::wireName)
                .filter(c -> !granted.contains(c))
                .toList();
        if (!missing.isEmpty()) {
            throw new GatewayException(GatewayErrorCode.CAPABILITY_NOT_NEGOTIATED,
                    "frame uses capabilities not negotiated for this session: "
                            + missing.stream().collect(Collectors.joining(","))
                            + " (granted=" + granted + ")");
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
