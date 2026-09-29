package com.github.lisama569.devicegatewayjava.transport;

import com.github.lisama569.devicegatewayjava.config.GatewayProperties;
import com.github.lisama569.devicegatewayjava.dispatch.FrameDispatcher;
import com.github.lisama569.devicegatewayjava.metrics.GatewayMetrics;
import com.github.lisama569.devicegatewayjava.protocol.ErrorFrames;
import com.github.lisama569.devicegatewayjava.protocol.FrameDecoder;
import com.github.lisama569.devicegatewayjava.error.GatewayErrorCode;
import com.github.lisama569.devicegatewayjava.reliability.ReliableDownlink;
import com.github.lisama569.devicegatewayjava.session.GatewaySession;
import com.github.lisama569.devicegatewayjava.session.SessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 仅绑定 127.0.0.1 的 TCP 接入服务器。
 *
 * 资源上限：
 * - maxConnections 个许可（Semaphore），超限连接收到 TOO_MANY_CONNECTIONS 错误帧后被关闭；
 * - 每连接读线程 1 个 + 写线程 1 个，随连接释放，线程数有界（2*maxConnections）；
 * - 接收 chunk 与解码器残留受 maxFrameLength 约束；待发队列受 maxOutboundPerSession 约束。
 *
 * 优雅关闭：停止 accept -> 向全部活动会话发 SERVER_SHUTTING_DOWN -> 等待在途处理排空 -> 强制关 socket。
 */
public final class GatewayServer {

    private static final Logger log = LoggerFactory.getLogger(GatewayServer.class);

    private final GatewayProperties props;
    private final FrameDispatcher dispatcher;
    private final ReliableDownlink reliable;
    private final SessionRegistry registry;
    private final ConnectionRegistry connections;
    private final GatewayMetrics metrics;

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private ExecutorService workers;
    private final Semaphore permits;
    private final AtomicInteger connSeq = new AtomicInteger();
    private volatile boolean running;

    public GatewayServer(GatewayProperties props, FrameDispatcher dispatcher,
                         ReliableDownlink reliable, SessionRegistry registry,
                         ConnectionRegistry connections, GatewayMetrics metrics) {
        this.props = props;
        this.dispatcher = dispatcher;
        this.reliable = reliable;
        this.registry = registry;
        this.connections = connections;
        this.metrics = metrics;
        this.permits = new Semaphore(props.getMaxConnections());
    }

    public synchronized void start() {
        if (running) return;
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), props.getPort()));
        } catch (IOException e) {
            throw new IllegalStateException("bind 127.0.0.1:" + props.getPort() + " failed", e);
        }
        workers = Executors.newFixedThreadPool(props.getMaxConnections(), r -> {
            Thread t = new Thread(r, "gw-io");
            t.setDaemon(true);
            return t;
        });
        running = true;
        acceptThread = new Thread(this::acceptLoop, "gw-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        log.info("gateway listening on 127.0.0.1:{} maxConnections={} maxFrameBytes={} maxReceiveBufferBytes={} maxOutbound={} versions={}..{} capabilities={}",
                serverSocket.getLocalPort(), props.getMaxConnections(),
                props.getMaxFrameLength(), props.getMaxReceiveBufferBytes(),
                props.getMaxOutboundPerSession(), props.getProtocolMinVersion(),
                props.getProtocolMaxVersion(), props.getSupportedCapabilities());
    }

    public boolean isRunning() { return running; }

    public int boundPort() {
        return serverSocket == null ? -1 : serverSocket.getLocalPort();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                if (!permits.tryAcquire()) {
                    rejectOverLimit(socket);
                    continue;
                }
                metrics.connectionAccepted();
                String cid = "c" + connSeq.incrementAndGet();
                GatewaySession session = new GatewaySession(cid, socket);
                OutboundWriter writer;
                try {
                    writer = new OutboundWriter(socket.getOutputStream(),
                            props.getMaxOutboundPerSession(), cid);
                } catch (IOException e) {
                    permits.release();
                    closeQuietly(socket);
                    continue;
                }
                ConnectionHandler handler = new ConnectionHandler(session,
                        new FrameDecoder(props.getMaxFrameLength(), props.getMaxReceiveBufferBytes()),
                        dispatcher, reliable,
                        registry, writer, metrics, props.getMaxFrameLength());
                connections.register(handler);
                workers.execute(() -> {
                    try {
                        handler.run();
                    } finally {
                        connections.unregister(handler);
                        permits.release();
                        writer.close();
                    }
                });
            } catch (SocketException e) {
                if (running) log.warn("accept socket error: {}", e.getMessage());
            } catch (IOException e) {
                if (running) log.warn("accept failed: {}", e.getMessage());
            }
        }
    }

    private void rejectOverLimit(Socket socket) {
        metrics.connectionRejected();
        metrics.error(GatewayErrorCode.TOO_MANY_CONNECTIONS.code());
        log.warn("connection limit {} reached -> rejecting {}", props.getMaxConnections(),
                socket.getRemoteSocketAddress());
        try {
            OutputStream out = socket.getOutputStream();
            byte[] err = ErrorFrames.encode(GatewayErrorCode.TOO_MANY_CONNECTIONS, 0);
            out.write(err);
            out.flush();
        } catch (IOException ignored) {
            // 尽力告知
        } finally {
            closeQuietly(socket);
        }
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        log.info("gateway shutting down: quiet={}ms timeout={}ms active={}",
                props.getShutdownQuietPeriodMs(), props.getShutdownTimeoutMs(), connections.count());

        closeQuietly(serverSocket); // 停止 accept
        sleep(props.getShutdownQuietPeriodMs()); // 让在途请求自然完成

        for (ConnectionContext ctx : connections.all()) {
            // 两阶段优雅关闭：通知帧先入队，再半关闭读端；读循环清理时排空待发帧后关 socket
            byte[] err = ErrorFrames.encode(GatewayErrorCode.SERVER_SHUTTING_DOWN, 0);
            ctx.beginShutdown(err, "server shutdown");
        }

        workers.shutdown();
        try {
            if (!workers.awaitTermination(props.getShutdownTimeoutMs(), TimeUnit.MILLISECONDS)) {
                log.warn("grace period exceeded, forcing shutdown of {} connections",
                        connections.count());
                workers.shutdownNow();
                // 超时仍未退出的连接强制关闭 socket
                for (ConnectionContext ctx : connections.all()) {
                    ctx.session().close("server shutdown timeout");
                }
            }
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
        reliable.abortAll();
        log.info("gateway stopped, final metrics: {}", metrics.snapshot());
    }

    private static void closeQuietly(Socket s) {
        try { s.close(); } catch (IOException ignored) { }
    }

    private static void closeQuietly(ServerSocket s) {
        try { s.close(); } catch (IOException ignored) { }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
