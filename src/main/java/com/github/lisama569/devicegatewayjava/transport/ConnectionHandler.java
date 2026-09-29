package com.github.lisama569.devicegatewayjava.transport;

import com.github.lisama569.devicegatewayjava.error.GatewayErrorCode;
import com.github.lisama569.devicegatewayjava.metrics.GatewayMetrics;
import com.github.lisama569.devicegatewayjava.protocol.FrameDecoder;
import com.github.lisama569.devicegatewayjava.protocol.ErrorFrames;
import com.github.lisama569.devicegatewayjava.dispatch.FrameDispatcher;
import com.github.lisama569.devicegatewayjava.reliability.ReliableDownlink;
import com.github.lisama569.devicegatewayjava.session.GatewaySession;
import com.github.lisama569.devicegatewayjava.session.SessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单连接读循环，同时实现 ConnectionContext。
 * - 接收缓冲有界：单次最多读 maxFrameLength 字节，解码器跨帧残留同样受帧长上限约束；
 * - 解码错误逐帧回报 dispatcher，连接不中断（除显式 requestClose）；
 * - 关闭时统一清理：注销会话、取消定时器、排空写出队列、关 socket。
 */
public final class ConnectionHandler implements Runnable, ConnectionContext {

    private static final Logger log = LoggerFactory.getLogger(ConnectionHandler.class);

    private final GatewaySession session;
    private final FrameDecoder decoder;
    private final FrameDispatcher dispatcher;
    private final ReliableDownlink reliableDownlink;
    private final SessionRegistry registry;
    private final OutboundWriter writer;
    private final GatewayMetrics metrics;
    private final int readChunk;
    private final AtomicBoolean cleanedUp = new AtomicBoolean(false);
    private volatile boolean closeRequested = false;
    private volatile String requestedReason = "";
    private volatile boolean drainThenClose = false;

    public ConnectionHandler(GatewaySession session,
                             FrameDecoder decoder,
                             FrameDispatcher dispatcher,
                             ReliableDownlink reliableDownlink,
                             SessionRegistry registry,
                             OutboundWriter writer,
                             GatewayMetrics metrics,
                             int readChunk) {
        this.session = session;
        this.decoder = decoder;
        this.dispatcher = dispatcher;
        this.reliableDownlink = reliableDownlink;
        this.registry = registry;
        this.writer = writer;
        this.metrics = metrics;
        this.readChunk = Math.max(64, readChunk);
    }

    @Override
    public void run() {
        String cid = connectionId();
        log.info("connection accepted conn={} remote={}", cid, session.socket().getRemoteSocketAddress());
        try {
            InputStream in = session.socket().getInputStream();
            byte[] chunk = new byte[readChunk];
            int n;
            while (!closeRequested && !session.closed() && (n = in.read(chunk)) > 0) {
                log.trace("raw bytes conn={} n={}", cid, n);
                for (FrameDecoder.Result r : decoder.feed(chunk, 0, n)) {
                    if (r instanceof FrameDecoder.Result.FrameOk ok) {
                        dispatcher.dispatch(this, ok.frame());
                    } else if (r instanceof FrameDecoder.Result.FrameError err) {
                        dispatcher.dispatchDecodeError(this, err);
                    } else if (r instanceof FrameDecoder.Result.BufferOverflow over) {
                        onReceiveBufferOverflow(over);
                    }
                    if (closeRequested || session.closed()) break;
                }
            }
        } catch (IOException e) {
            log.debug("connection io ended conn={}: {}", cid, e.getMessage());
        } finally {
            cleanup();
        }
    }

    /**
     * 接收侧按连接容量保护触发：未拼成完整帧的残留超过硬上限。
     * 结论可区分（4003）、只结束本条连接（terminateAfterDrain 让错误帧先送达），
     * 日志给出会话标识与判定依据（残留字节数 / 上限）；其他连接不受影响。
     */
    private void onReceiveBufferOverflow(FrameDecoder.Result.BufferOverflow over) {
        metrics.error(GatewayErrorCode.RECEIVE_BUFFER_FULL.code());
        metrics.receiveBufferTerminated();
        String basis = "unframed residual " + over.bufferedBytes() + " exceeds per-connection limit "
                + over.limit();
        log.warn("receive buffer overflow conn={} device={} basis='{}' -> terminating this connection only",
                connectionId(), session.deviceId(), basis);
        try {
            byte[] err = ErrorFrames.encode(GatewayErrorCode.RECEIVE_BUFFER_FULL, basis, 0);
            writer.offer(err);
            metrics.frameOut();
        } finally {
            terminateAfterDrain("receive buffer overflow: " + basis);
        }
    }

    private void cleanup() {
        if (!cleanedUp.compareAndSet(false, true)) return;
        String reason = session.closed() ? session.closeReason() : requestedReason;
        registry.unregister(session);
        if (session.authenticated()) {
            metrics.sessionInactive();
        }
        reliableDownlink.abortConnection(connectionId());
        if (drainThenClose) {
            // 关 socket 的动作已排到写线程队列末尾（terminateAfterDrain），
            // 此处若先关会与写线程竞争、截断待排空的结论帧；由写线程在 flush 后执行 session.close
            log.info("connection closing after drain conn={} device={} reason='{}' bufferedResidual={}",
                    connectionId(), session.deviceId(), reason, decoder.bufferedBytes());
        } else {
            writer.close();
            session.close(reason);
            log.info("connection closed conn={} device={} reason='{}' bufferedResidual={}",
                    connectionId(), session.deviceId(), reason, decoder.bufferedBytes());
        }
    }

    @Override
    public GatewaySession session() { return session; }

    @Override
    public ReliableDownlink reliableDownlink() { return reliableDownlink; }

    @Override
    public boolean writeFrame(byte[] frame) throws IOException {
        boolean ok = writer.offer(frame);
        if (!ok && !session.closed()) {
            metrics.error(GatewayErrorCode.OUTBOUND_BUFFER_FULL.code());
            log.warn("outbound buffer full conn={} device={} queued={} -> degraded (frame rejected)",
                    connectionId(), session.deviceId(), writer.queuedFrames());
        }
        return ok;
    }

    @Override
    public void requestClose(String reason) {
        closeRequested = true;
        requestedReason = reason == null ? "" : reason;
    }

    @Override
    public void beginShutdown(byte[] notifyFrame, String reason) {
        // 阶段 1：通知帧先入队（写线程持锁顺序保证它排在关闭动作之前）
        if (notifyFrame != null) {
            writer.offer(notifyFrame);
        }
        // 阶段 2：请求关闭并唤醒阻塞中的读循环；读循环清理时 writer.close() 会排空通知帧
        requestClose(reason);
        session.shutdownInput();
    }

    @Override
    public void terminateAfterDrain(String reason) {
        // 关 socket 的动作排到写线程队列末尾，确保最后一份重传先 flush
        drainThenClose = true;
        requestClose(reason);
        writer.closeAfterDrain(() -> session.close(reason));
        // 唤醒可能仍阻塞在 read 上的读循环（socket 最终也会被写线程关闭）
        session.shutdownInput();
    }

    @Override
    public String connectionId() { return session.connectionId(); }
}
