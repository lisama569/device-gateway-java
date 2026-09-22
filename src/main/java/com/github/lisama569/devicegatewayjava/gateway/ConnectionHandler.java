package com.github.lisama569.devicegatewayjava.gateway;

import com.github.lisama569.devicegatewayjava.auth.AuthResult;
import com.github.lisama569.devicegatewayjava.auth.Authenticator;
import com.github.lisama569.devicegatewayjava.config.GatewayProperties;
import com.github.lisama569.devicegatewayjava.delivery.GatewayClock;
import com.github.lisama569.devicegatewayjava.delivery.OutboundQueue;
import com.github.lisama569.devicegatewayjava.delivery.PendingDownlink;
import com.github.lisama569.devicegatewayjava.delivery.RetryScheduler;
import com.github.lisama569.devicegatewayjava.delivery.RetryTracker;
import com.github.lisama569.devicegatewayjava.protocol.DecodeResult;
import com.github.lisama569.devicegatewayjava.protocol.ErrorCode;
import com.github.lisama569.devicegatewayjava.protocol.Frame;
import com.github.lisama569.devicegatewayjava.protocol.FrameDecoder;
import com.github.lisama569.devicegatewayjava.protocol.FrameEncoder;
import com.github.lisama569.devicegatewayjava.protocol.Messages;
import com.github.lisama569.devicegatewayjava.protocol.RejectReason;
import com.github.lisama569.devicegatewayjava.session.DeviceStateStore;
import com.github.lisama569.devicegatewayjava.session.SeqDecision;
import com.github.lisama569.devicegatewayjava.session.SessionRegistry;
import com.github.lisama569.devicegatewayjava.session.SessionState;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the full lifecycle of one accepted TCP connection:
 * frame parsing/resync, version negotiation, authentication, sequence
 * validation, uplink ACKs, downlink reliable delivery and ordered shutdown.
 *
 * <p>All business writes happen on the single reader thread of this connection,
 * while device-level locks guard cross-connection (reconnect) races. Outbound
 * frames pass through one bounded {@link OutboundQueue} and a single writer
 * thread, so frames for a session can never interleave ("串帧").
 */
public final class ConnectionHandler implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ConnectionHandler.class);

    private static final Set<Byte> KNOWN_TYPES = Set.of(
            Frame.T_HELLO, Frame.T_HELLO_ACK, Frame.T_LOGIN, Frame.T_LOGIN_ACK,
            Frame.T_UPLINK, Frame.T_UPLINK_ACK, Frame.T_DOWNLINK, Frame.T_DOWNLINK_ACK,
            Frame.T_ERROR, Frame.T_LOGOUT, Frame.T_HEARTBEAT);

    private final Socket socket;
    private final GatewayProperties properties;
    private final Authenticator authenticator;
    private final SessionRegistry sessions;
    private final DeviceStateStore deviceStates;
    private final GatewayMetrics metrics;
    private final GatewayClock clock;
    private final RetryScheduler retryScheduler;

    private final long connectionId = CONN_IDS.incrementAndGet();
    private final FrameDecoder decoder;
    private final OutboundQueue outbound;

    private volatile SessionState session;
    private volatile int negotiatedVersion = -1;
    private volatile boolean helloDone;
    private volatile boolean authenticated;
    private volatile boolean stopping;
    private Thread readerThread;
    private Thread writerThread;
    private RetryTracker retryTracker;
    private final AtomicLong downlinkSeqGen = new AtomicLong();

    private static final AtomicLong CONN_IDS = new AtomicLong();

    /** Callbacks into the server, invoked on authentication success and close. */
    public interface CloseCallback {
        void onClosed(ConnectionHandler handler);

        default void onAuthenticated(ConnectionHandler handler) {
        }
    }

    private final CloseCallback callback;

    public ConnectionHandler(Socket socket,
                             GatewayProperties properties,
                             Authenticator authenticator,
                             SessionRegistry sessions,
                             DeviceStateStore deviceStates,
                             GatewayMetrics metrics,
                             GatewayClock clock) {
        this(socket, properties, authenticator, sessions, deviceStates, metrics, clock, null, null);
    }

    public ConnectionHandler(Socket socket,
                             GatewayProperties properties,
                             Authenticator authenticator,
                             SessionRegistry sessions,
                             DeviceStateStore deviceStates,
                             GatewayMetrics metrics,
                             GatewayClock clock,
                             CloseCallback callback) {
        this(socket, properties, authenticator, sessions, deviceStates, metrics, clock,
                callback, null);
    }

    public ConnectionHandler(Socket socket,
                             GatewayProperties properties,
                             Authenticator authenticator,
                             SessionRegistry sessions,
                             DeviceStateStore deviceStates,
                             GatewayMetrics metrics,
                             GatewayClock clock,
                             CloseCallback callback,
                             RetryScheduler retryScheduler) {
        this.socket = socket;
        this.properties = properties;
        this.authenticator = authenticator;
        this.sessions = sessions;
        this.deviceStates = deviceStates;
        this.metrics = metrics;
        this.clock = clock;
        this.callback = callback;
        this.retryScheduler = retryScheduler != null ? retryScheduler : clock::schedule;
        this.decoder = new FrameDecoder(properties.getMaxFramePayload(),
                properties.getMaxBufferedBytes());
        this.outbound = new OutboundQueue(properties.getMaxOutboundPending());
    }

    public long connectionId() {
        return connectionId;
    }

    public SessionState session() {
        return session;
    }

    /** Ask the connection to stop and close the socket (graceful shutdown). */
    public void initiateShutdown() {
        stopping = true;
        closeSocketQuietly();
    }

    @Override
    public void run() {
        String cid = cid();
        log.info("[{}] connection opened remote={}", cid, socket.getRemoteSocketAddress());
        metrics.increment("connections.opened");
        try {
            socket.setTcpNoDelay(true);
            startWriter();
            readLoop();
        } catch (Exception e) {
            if (!stopping) {
                log.debug("[{}] connection ended with exception: {}", cid, e.toString());
            }
        } finally {
            cleanup();
        }
    }

    // ------------------------------------------------------------------
    // read loop
    // ------------------------------------------------------------------

    private void startWriter() {
        writerThread = new Thread(this::writeLoop, "gateway-writer-" + connectionId);
        writerThread.setDaemon(true);
        writerThread.start();
    }

    private long unparsedBudgetUsed;

    private void readLoop() throws IOException {
        BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
        byte[] chunk = new byte[Math.min(8192, properties.getMaxBufferedBytes())];
        readerThread = Thread.currentThread();
        int n;
        while (!stopping && (n = in.read(chunk)) >= 0) {
            if (n == 0) {
                continue;
            }
            log.info("[{}] received {} byte(s): {}", cid(), n, hex(chunk, n));
            // The receive budget covers both bytes currently held in the
            // decoder and garbage already discarded while searching for magic,
            // so a peer flooding non-frame bytes is still bounded.
            if (unparsedBudgetUsed + n > properties.getMaxBufferedBytes()) {
                metrics.increment(ErrorCode.RECEIVE_BUFFER_EXCEEDED);
                log.warn("[{}] reject connection: receive budget {} byte(s) exceeded by garbage stream",
                        cid(), properties.getMaxBufferedBytes());
                sendError(ErrorCode.RECEIVE_BUFFER_EXCEEDED,
                        "receive buffer limit exceeded", 0);
                orderlyCloseAfterFlush();
                stopping = true;
                break;
            }
            if (!decoder.append(chunk, 0, n)) {
                metrics.increment(ErrorCode.RECEIVE_BUFFER_EXCEEDED);
                log.warn("[{}] reject connection: receive buffer would exceed {} byte(s), buffered={}",
                        cid(), properties.getMaxBufferedBytes(), decoder.bufferedBytes());
                sendError(ErrorCode.RECEIVE_BUFFER_EXCEEDED,
                        "receive buffer limit exceeded", 0);
                decoder.markFatal(RejectReason.FRAME_TOO_LONG);
                stopping = true;
                break;
            }
            drainDecoder();
            if (decoder.isFatal()) {
                stopping = true;
                break;
            }
        }
    }

    private void drainDecoder() {
        DecodeResult result;
        while (!((result = decoder.decode()) instanceof DecodeResult.NeedMoreData)) {
            if (result instanceof DecodeResult.FrameRejected rejected) {
                metrics.increment("frames.rejected");
                if (rejected.reason() == RejectReason.BAD_MAGIC) {
                    unparsedBudgetUsed += rejected.consumedBytes();
                }
                log.info("[{}] frame rejected reason={} consumed={} detail='{}' input-bytes={}",
                        cid(), rejected.reason(), rejected.consumedBytes(),
                        rejected.detail(), rejected.consumedBytes());
                metrics.increment(ErrorCode.from(rejected.reason()));
                sendError(ErrorCode.from(rejected.reason()),
                        rejected.reason() + ": " + rejected.detail(), 0);
                if (decoder.isFatal()) {
                    stopping = true;
                    return;
                }
            } else if (result instanceof DecodeResult.FrameOk ok) {
                metrics.increment("frames.received");
                unparsedBudgetUsed = 0; // valid frame resynchronises the budget
                dispatch(ok.frame());
                if (stopping) {
                    return;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // dispatch / protocol state machine
    // ------------------------------------------------------------------

    private void dispatch(Frame frame) {
        byte type = frame.type();
        long seq = frame.seq();
        if (!KNOWN_TYPES.contains(type)) {
            metrics.increment(ErrorCode.UNKNOWN_MESSAGE_TYPE);
            log.info("[{}] reject unknown message type=0x{} seq={}", cid(),
                    Integer.toHexString(type & 0xFF), seq);
            sendError(ErrorCode.UNKNOWN_MESSAGE_TYPE,
                    "unknown message type 0x" + Integer.toHexString(type & 0xFF), seq);
            return;
        }
        switch (type) {
            case Frame.T_HELLO -> handleHello(frame);
            case Frame.T_LOGIN -> handleLogin(frame);
            case Frame.T_UPLINK -> handleUplink(frame);
            case Frame.T_DOWNLINK_ACK -> handleDownlinkAck(frame);
            case Frame.T_HEARTBEAT -> handleHeartbeat(frame);
            case Frame.T_LOGOUT -> handleLogout(frame);
            default -> {
                // Server-bound ACK/ERROR/DOWNLINK from a device are unexpected.
                metrics.increment(ErrorCode.UNKNOWN_MESSAGE_TYPE);
                sendError(ErrorCode.UNKNOWN_MESSAGE_TYPE,
                        "message type 0x" + Integer.toHexString(type & 0xFF)
                                + " is not valid from device", seq);
            }
        }
    }

    private void handleHello(Frame frame) {
        try {
            Messages.Hello hello = Messages.parseHello(frame.payload());
            if (authenticated) {
                metrics.increment(ErrorCode.SESSION_CLOSED);
                log.info("[{}] reject HELLO after login for session={}", cid(),
                        session != null ? session.sessionId() : -1);
                sendError(ErrorCode.SESSION_CLOSED,
                        "renegotiation not allowed on an authenticated session", frame.seq());
                return;
            }
            int maxV = properties.getProtocolVersion();
            int minV = Math.min(properties.getMinProtocolVersion(), maxV);
            if (hello.maxVersion() < minV || hello.minVersion() > maxV) {
                metrics.increment(ErrorCode.VERSION_MISMATCH);
                log.info("[{}] version reject device=[{},{}] server=[{},{}]",
                        cid(), hello.minVersion(), hello.maxVersion(), minV, maxV);
                sendFrame(new Frame(Frame.T_ERROR, (byte) 0, frame.seq(),
                        Messages.error(ErrorCode.VERSION_MISMATCH.code(),
                                ErrorCode.VERSION_MISMATCH.category(),
                                "no common protocol version; server supports "
                                        + minV + ".." + maxV, frame.seq())));
                return;
            }
            negotiatedVersion = Math.min(maxV, hello.maxVersion());
            helloDone = true;
            long provisionalSessionId = -connectionId;
            sendFrame(new Frame(Frame.T_HELLO_ACK, (byte) 0, frame.seq(),
                    Messages.helloAck(negotiatedVersion, provisionalSessionId)));
            log.info("[{}] version negotiated {} (device=[{},{}])",
                    cid(), negotiatedVersion, hello.minVersion(), hello.maxVersion());
        } catch (Messages.PayloadException e) {
            rejectMalformed(e, frame.seq());
        }
    }

    private void handleLogin(Frame frame) {
        Messages.Login login;
        try {
            login = Messages.parseLogin(frame.payload());
        } catch (Messages.PayloadException e) {
            rejectMalformed(e, frame.seq());
            return;
        }

        if (!helloDone) {
            sendAuthReject(frame.seq(), ErrorCode.UNAUTHENTICATED,
                    "HELLO negotiation required before LOGIN");
            return;
        }

        if (authenticated) {
            metrics.increment(ErrorCode.ALREADY_LOGGED_IN);
            log.info("[{}] reject duplicate LOGIN frame on already authenticated session={}",
                    cid(), session != null ? session.sessionId() : -1);
            sendFrame(new Frame(Frame.T_LOGIN_ACK, (byte) 0, frame.seq(),
                    Messages.loginAck(false, ErrorCode.ALREADY_LOGGED_IN.code(),
                            ErrorCode.ALREADY_LOGGED_IN.name())));
            return;
        }

        AuthResult result = authenticator.authenticate(
                login.deviceId(), login.token(), clock.nowMillis());
        if (!result.accepted()) {
            metrics.increment(result.code());
            log.info("[{}] auth reject device='{}' reason={}/{} (token={})",
                    cid(), login.deviceId(), result.code().name(), result.reason(),
                    Authenticator.redact(login.token()));
            sendFrame(new Frame(Frame.T_LOGIN_ACK, (byte) 0, frame.seq(),
                    Messages.loginAck(false, result.code().code(), result.code().name())));
            return;
        }

        DeviceStateStore.DeviceState deviceState = deviceStates.get(result.deviceId());
        long resumeSeq = deviceState.lastAppliedSeq();
        SessionState candidate =
                new SessionState(result.deviceId(), clock.nowMillis(), resumeSeq);
        candidate.setRemoteAddress(socket.getRemoteSocketAddress());
        SessionState existing = sessions.register(candidate);
        if (existing != null) {
            metrics.increment(ErrorCode.ALREADY_LOGGED_IN);
            log.info("[{}] duplicate login reject device='{}' existing-session={}",
                    cid(), result.deviceId(), existing.sessionId());
            sendFrame(new Frame(Frame.T_LOGIN_ACK, (byte) 0, frame.seq(),
                    Messages.loginAck(false, ErrorCode.ALREADY_LOGGED_IN.code(),
                            ErrorCode.ALREADY_LOGGED_IN.name())));
            return;
        }

        this.session = candidate;
        this.authenticated = true;
        this.retryTracker = new RetryTracker(properties.getMaxRetries());
        metrics.increment("sessions.started");
        // Register in the server's handler index BEFORE releasing LOGIN_ACK,
        // so a management downlink triggered the instant the device sees the
        // ack can never race an empty handler map.
        if (callback != null) {
            callback.onAuthenticated(this);
        }
        sendFrame(new Frame(Frame.T_LOGIN_ACK, (byte) 0, frame.seq(),
                Messages.loginAck(true, 0, "OK resumeSeq=" + resumeSeq)));
        log.info("[{}] login accepted device='{}' session={} resumeSeq={}",
                cid(), result.deviceId(), candidate.sessionId(), resumeSeq);
    }

    private void handleUplink(Frame frame) {
        if (!requireAuth(frame.seq(), Frame.T_UPLINK_ACK)) {
            return;
        }
        Messages.UplinkData data;
        try {
            data = Messages.parseUplink(frame.payload());
        } catch (Messages.PayloadException e) {
            rejectMalformed(e, frame.seq());
            return;
        }

        DeviceStateStore.DeviceState deviceState = deviceStates.get(session.deviceId());
        SeqDecision decision = deviceState.submit(frame.seq(), data.key());
        switch (decision) {
            case ACCEPT -> {
                metrics.increment("uplink.applied");
                log.info("[{}] uplink ACCEPT device='{}' session={} seq={} key='{}' value={}",
                        cid(), session.deviceId(), session.sessionId(),
                        frame.seq(), data.key(), data.value());
                sendFrame(new Frame(Frame.T_UPLINK_ACK, (byte) 0, frame.seq(),
                        Messages.ack(frame.seq(), true, 0, "APPLIED")));
            }
            case DUPLICATE -> {
                metrics.increment(ErrorCode.DUPLICATE_SEQ);
                log.info("[{}] uplink DUPLICATE device='{}' session={} seq={} key='{}' (not re-applied)",
                        cid(), session.deviceId(), session.sessionId(),
                        frame.seq(), data.key());
                sendFrame(new Frame(Frame.T_UPLINK_ACK, (byte) Frame.FLAG_DUP, frame.seq(),
                        Messages.ack(frame.seq(), false, ErrorCode.DUPLICATE_SEQ.code(),
                                "DUPLICATE")));
            }
            case ROLLBACK -> {
                metrics.increment(ErrorCode.SEQ_ROLLBACK);
                log.info("[{}] uplink ROLLBACK device='{}' session={} seq={} highWater={}",
                        cid(), session.deviceId(), session.sessionId(),
                        frame.seq(), deviceState.lastAppliedSeq());
                sendFrame(new Frame(Frame.T_UPLINK_ACK, (byte) 0, frame.seq(),
                        Messages.ack(frame.seq(), false, ErrorCode.SEQ_ROLLBACK.code(),
                                "SEQ_ROLLBACK")));
            }
            case OUT_OF_ORDER -> {
                metrics.increment(ErrorCode.SEQ_OUT_OF_ORDER);
                log.info("[{}] uplink OUT_OF_ORDER device='{}' session={} seq={} highWater={}",
                        cid(), session.deviceId(), session.sessionId(),
                        frame.seq(), deviceState.lastAppliedSeq());
                sendFrame(new Frame(Frame.T_UPLINK_ACK, (byte) 0, frame.seq(),
                        Messages.ack(frame.seq(), false, ErrorCode.SEQ_OUT_OF_ORDER.code(),
                                "SEQ_OUT_OF_ORDER")));
            }
        }
    }

    private void handleDownlinkAck(Frame frame) {
        if (!requireAuth(frame.seq(), Frame.T_ERROR)) {
            return;
        }
        Messages.Ack ack;
        try {
            ack = Messages.parseAck(frame.payload());
        } catch (Messages.PayloadException e) {
            rejectMalformed(e, frame.seq());
            return;
        }
        long downSeq = ack.ackedSeq();
        if (retryTracker.acknowledge(downSeq)) {
            metrics.increment("downlink.acked");
            log.info("[{}] downlink ACK device='{}' session={} downSeq={} accepted={}",
                    cid(), session.deviceId(), session.sessionId(), downSeq, ack.accepted());
        } else if (retryTracker.isDuplicateAck(downSeq)) {
            metrics.increment("downlink.duplicate-ack");
            log.info("[{}] downlink DUPLICATE-ACK device='{}' session={} downSeq={} (ignored)",
                    cid(), session.deviceId(), session.sessionId(), downSeq);
        } else {
            metrics.increment("downlink.unknown-ack");
            log.info("[{}] downlink UNKNOWN-ACK device='{}' session={} downSeq={} (no pending frame)",
                    cid(), session.deviceId(), session.sessionId(), downSeq);
            sendError(ErrorCode.SESSION_CLOSED,
                    "no pending downlink with seq " + downSeq, frame.seq());
        }
    }

    private void handleHeartbeat(Frame frame) {
        if (!requireAuth(frame.seq(), Frame.T_ERROR)) {
            return;
        }
        sendFrame(new Frame(Frame.T_HEARTBEAT, (byte) 0, frame.seq(), new byte[0]));
    }

    private void handleLogout(Frame frame) {
        if (authenticated && session != null) {
            log.info("[{}] logout device='{}' session={} seq={}",
                    cid(), session.deviceId(), session.sessionId(), frame.seq());
            metrics.increment("sessions.logout");
        }
        stopping = true;
    }

    // ------------------------------------------------------------------
    // reliable downlink (server -> device)
    // ------------------------------------------------------------------

    /** Enqueue a reliable downlink and arm its bounded retry schedule. */
    public boolean sendDownlink(long downlinkSeq, String command, String arg) {
        SessionState s = session;
        RetryTracker tracker = retryTracker;
        if (s == null || !s.isAlive() || tracker == null || stopping) {
            return false;
        }
        synchronized (downlinkSeqGen) {
            Frame frame = new Frame(Frame.T_DOWNLINK, (byte) 0, downlinkSeq,
                    Messages.downlink(command, arg));
            PendingDownlink pending = new PendingDownlink(frame, clock.nowMillis());
            tracker.register(pending);
            OutboundQueue.OfferResult offer = enqueue(frame);
            if (offer != OutboundQueue.OfferResult.ACCEPTED) {
                tracker.remove(downlinkSeq);
                metrics.increment(ErrorCode.OUTBOUND_QUEUE_FULL);
                log.warn("[{}] downlink rejected: outbound queue full device='{}' downSeq={}",
                        cid(), s.deviceId(), downlinkSeq);
                return false;
            }
            metrics.increment("downlink.sent");
            log.info("[{}] downlink sent device='{}' session={} downSeq={} cmd='{}'",
                    cid(), s.deviceId(), s.sessionId(), downlinkSeq, command);
            armRetry(pending);
            return true;
        }
    }

    private void armRetry(PendingDownlink pending) {
        long delay = properties.getRetryIntervalMillis();
        retryScheduler.schedule(() -> onRetryTimer(pending.seq()), delay);
    }

    private void onRetryTimer(long downSeq) {
        SessionState s = session;
        RetryTracker tracker = retryTracker;
        if (stopping || s == null || tracker == null) {
            return;
        }
        PendingDownlink p = tracker.get(downSeq);
        if (p == null) {
            return; // acknowledged or removed
        }
        // The frame stayed unacknowledged for another full interval.
        if (tracker.budgetUsed(p)) {
            terminateSession(ErrorCode.SESSION_TERMINATED_RETRY_EXHAUSTED,
                    "downlink seq " + downSeq + " unacked after "
                            + p.sendCount() + " send(s) (1 original + "
                            + p.retryCount() + " retry), maxRetries=" + tracker.maxRetries());
            return;
        }
        Frame retransmit = new Frame(p.frame().type(), (byte) Frame.FLAG_DUP,
                p.frame().seq(), p.frame().payload());
        boolean lastRetry = tracker.recordRetry(p, clock.nowMillis());
        OutboundQueue.OfferResult offer = enqueue(retransmit);
        metrics.increment("downlink.retransmit");
        log.info("[{}] downlink retransmit device='{}' downSeq={} retryCount={} lastRetry={} queued={}",
                cid(), s.deviceId(), downSeq, p.retryCount(), lastRetry,
                offer == OutboundQueue.OfferResult.ACCEPTED);
        if (offer != OutboundQueue.OfferResult.ACCEPTED) {
            terminateSession(ErrorCode.SESSION_TERMINATED_RETRY_EXHAUSTED,
                    "downlink seq " + downSeq + " could not be queued within retry budget");
            return;
        }
        retryScheduler.schedule(() -> onRetryTimer(downSeq), properties.getRetryIntervalMillis());
    }

    private void terminateSession(ErrorCode code, String reason) {
        SessionState s = session;
        if (s == null || !s.isAlive()) {
            return;
        }
        metrics.increment(code);
        s.terminate(code.name() + ": " + reason);
        log.error("[{}] session TERMINATED device='{}' session={} code={} reason='{}'",
                cid(), s.deviceId(), s.sessionId(), code.name(), reason);
        sendError(code, reason, 0);
        stopping = true;
        orderlyCloseAfterFlush();
    }

    /**
     * Enqueue the close marker behind everything already queued. The bounded
     * queue may already hold error frames, so block until the writer drains;
     * the writer flushes in order and then closes the socket.
     */
    private void orderlyCloseAfterFlush() {
        try {
            outbound.closeAfterFlush();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closeSocketQuietly();
        }
    }

    // ------------------------------------------------------------------
    // output
    // ------------------------------------------------------------------

    private OutboundQueue.OfferResult enqueue(Frame frame) {
        return outbound.offer(FrameEncoder.encode(frame,
                negotiatedVersion > 0 ? negotiatedVersion : properties.getProtocolVersion()));
    }

    private boolean sendFrame(Frame frame) {
        return enqueue(frame) == OutboundQueue.OfferResult.ACCEPTED;
    }

    private void sendError(ErrorCode code, String reason, long refSeq) {
        sendFrame(new Frame(Frame.T_ERROR, (byte) 0, 0,
                Messages.error(code.code(), code.category(), reason, refSeq)));
    }

    private void sendAuthReject(long refSeq, ErrorCode code, String reason) {
        metrics.increment(code);
        sendFrame(new Frame(Frame.T_LOGIN_ACK, (byte) 0, refSeq,
                Messages.loginAck(false, code.code(), code.name() + ": " + reason)));
    }

    private void rejectMalformed(Messages.PayloadException e, long seq) {
        metrics.increment(ErrorCode.MALFORMED_PAYLOAD);
        log.info("[{}] malformed payload seq={} detail='{}'", cid(), seq, e.getMessage());
        sendError(ErrorCode.MALFORMED_PAYLOAD, e.getMessage(), seq);
    }

    private boolean requireAuth(long seq, byte replyType) {
        if (authenticated && session != null && session.isAlive()) {
            return true;
        }
        metrics.increment(ErrorCode.UNAUTHENTICATED);
        log.info("[{}] reject frame seq={}: unauthenticated session", cid(), seq);
        if (replyType == Frame.T_LOGIN_ACK) {
            sendAuthReject(seq, ErrorCode.UNAUTHENTICATED, "login required");
        } else {
            sendError(ErrorCode.UNAUTHENTICATED, "authentication required", seq);
        }
        return false;
    }

    private void writeLoop() {
        try (OutputStream out = socket.getOutputStream()) {
            while (true) {
                byte[] frame = outbound.take();
                if (frame == OutboundQueue.POISON) {
                    return;
                }
                if (frame == OutboundQueue.CLOSE_AFTER_FLUSH) {
                    // Everything previously queued (e.g. the TERMINATED error
                    // frame) has already been written and flushed: close now.
                    out.flush();
                    closeSocketQuietly();
                    return;
                }
                out.write(frame);
                out.flush();
                metrics.increment("frames.sent");
            }
        } catch (IOException | RuntimeException e) {
            if (!stopping) {
                log.debug("[{}] writer ended: {}", cid(), e.toString());
            }
            stopping = true;
            closeSocketQuietly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void cleanup() {
        stopping = true;
        SessionState s = session;
        if (s != null) {
            sessions.unregister(s, s.terminateReason() != null ? s.terminateReason() : "connection closed");
            log.info("[{}] session removed device='{}' session={} status={} reason='{}'",
                    cid(), s.deviceId(), s.sessionId(), s.status(), s.terminateReason());
        }
        metrics.increment("connections.closed");
        outbound.poison();
        closeSocketQuietly();
        if (writerThread != null) {
            writerThread.interrupt();
        }
        if (callback != null) {
            callback.onClosed(this);
        }
    }

    private void closeSocketQuietly() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // best effort
        }
    }

    private String cid() {
        return "conn-" + connectionId;
    }

    private static String hex(byte[] data, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format("%02x", data[i] & 0xFF));
        }
        return sb.toString();
    }
}
