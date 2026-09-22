package com.github.lisama569.devicegatewayjava.gateway;

import com.github.lisama569.devicegatewayjava.auth.Authenticator;
import com.github.lisama569.devicegatewayjava.config.GatewayProperties;
import com.github.lisama569.devicegatewayjava.delivery.GatewayClock;
import com.github.lisama569.devicegatewayjava.delivery.RetryScheduler;
import com.github.lisama569.devicegatewayjava.session.DeviceStateStore;
import com.github.lisama569.devicegatewayjava.session.SessionRegistry;
import com.github.lisama569.devicegatewayjava.session.SessionState;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Loopback-only TCP server.
 *
 * <p>Resource bounds:
 * <ul>
 *   <li>accept is limited to {@code gateway.max-connections}; excess
 *       connections are immediately closed with a TOO_MANY_CONNECTIONS event
 *       (no thread is allocated);</li>
 *   <li>per-connection receive buffering and outbound queue are bounded inside
 *       {@link ConnectionHandler};</li>
 *   <li>the worker pool is sized to the connection limit, so threads cannot
 *       grow without bound.</li>
 * </ul>
 *
 * <p>Graceful shutdown: the server socket is closed first (no new accepts),
 * active connections are signalled to stop, and the executor is awaited so
 * already-committed state stays consistent.
 */
@Component
@ConditionalOnProperty(name = "gateway.tcp-enabled", havingValue = "true", matchIfMissing = true)
public class GatewayServer {

    private static final Logger log = LoggerFactory.getLogger(GatewayServer.class);

    private final GatewayProperties properties;
    private final Authenticator authenticator;
    private final SessionRegistry sessions;
    private final DeviceStateStore deviceStates;
    private final GatewayMetrics metrics;
    private final GatewayClock clock;
    private final RetryScheduler retryScheduler;

    private volatile ServerSocket serverSocket;
    private volatile Thread acceptThread;
    private volatile ExecutorService workers;
    private volatile boolean running;
    private final AtomicInteger activeConnections = new AtomicInteger();
    private final ConcurrentHashMap<String, ConnectionHandler> handlers = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public GatewayServer(GatewayProperties properties,
                         Authenticator authenticator,
                         SessionRegistry sessions,
                         DeviceStateStore deviceStates,
                         GatewayMetrics metrics,
                         GatewayClock clock) {
        this(properties, authenticator, sessions, deviceStates, metrics, clock, null);
    }

    public GatewayServer(GatewayProperties properties,
                         Authenticator authenticator,
                         SessionRegistry sessions,
                         DeviceStateStore deviceStates,
                         GatewayMetrics metrics,
                         GatewayClock clock,
                         RetryScheduler retryScheduler) {
        this.properties = properties;
        this.authenticator = authenticator;
        this.sessions = sessions;
        this.deviceStates = deviceStates;
        this.metrics = metrics;
        this.clock = clock;
        this.retryScheduler = retryScheduler;
    }

    @PostConstruct
    public void start() throws IOException {
        String host = properties.getBindHost();
        if (!isLoopback(host)) {
            throw new IllegalStateException("gateway.bind-host must be a loopback address, got: " + host);
        }
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(host, properties.getPort()));
        workers = Executors.newFixedThreadPool(Math.max(2, properties.getMaxConnections()),
                new ThreadFactory() {
                    private final AtomicInteger n = new AtomicInteger();
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "gateway-conn-" + n.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    }
                });
        running = true;
        acceptThread = new Thread(this::acceptLoop, "gateway-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        log.info("device gateway listening on {}:{} (loopback only), maxConnections={}",
                host, serverSocket.getLocalPort(), properties.getMaxConnections());
    }

    static boolean isLoopback(String host) {
        try {
            java.net.InetAddress addr = java.net.InetAddress.getByName(host);
            return addr.isLoopbackAddress();
        } catch (java.net.UnknownHostException e) {
            return false;
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                int current = activeConnections.incrementAndGet();
                if (current > properties.getMaxConnections()) {
                    activeConnections.decrementAndGet();
                    metrics.increment(com.github.lisama569.devicegatewayjava.protocol.ErrorCode
                            .TOO_MANY_CONNECTIONS);
                    log.warn("reject connection from {}: connection limit {} reached",
                            socket.getRemoteSocketAddress(), properties.getMaxConnections());
                    socket.close();
                    continue;
                }
                ConnectionHandler handler = new ConnectionHandler(socket, properties,
                        authenticator, sessions, deviceStates, metrics, clock,
                        new ConnectionHandler.CloseCallback() {
                            @Override
                            public void onClosed(ConnectionHandler h) {
                                activeConnections.decrementAndGet();
                                SessionState s = h.session();
                                if (s != null) {
                                    handlers.remove(s.deviceId(), h);
                                }
                            }

                            @Override
                            public void onAuthenticated(ConnectionHandler h) {
                                handlers.put(h.session().deviceId(), h);
                            }
                        }, retryScheduler);
                workers.execute(handler);
            } catch (IOException e) {
                if (running) {
                    log.warn("accept loop I/O error: {}", e.toString());
                }
            }
        }
    }

    @PreDestroy
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        log.info("device gateway shutting down: stop accepting, draining {} active connection(s)",
                activeConnections.get());
        metrics.increment("shutdown");
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
            // best effort
        }
        // Mark every live session closed and close its socket so in-flight
        // reader/writer threads finish promptly. Already-committed device state
        // (high-water seq + applied keys) is left intact in DeviceStateStore.
        for (SessionState s : sessions.all()) {
            if (s.isAlive()) {
                s.close("gateway shutdown");
            }
        }
        for (ConnectionHandler h : handlers.values()) {
            h.initiateShutdown();
        }
        if (workers != null) {
            workers.shutdown();
            try {
                if (!workers.awaitTermination(3, TimeUnit.SECONDS)) {
                    workers.shutdownNow();
                }
            } catch (InterruptedException e) {
                workers.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("device gateway stopped; final active sessions={}", sessions.activeCount());
    }

    public boolean isRunning() {
        return running;
    }

    public int boundPort() {
        ServerSocket s = serverSocket;
        return s == null ? properties.getPort() : s.getLocalPort();
    }

    public int activeConnections() {
        return activeConnections.get();
    }

    /** Registers a handler keyed by its authenticated device (called by handlers). */
    public void registerHandler(String deviceId, ConnectionHandler handler) {
        handlers.put(deviceId, handler);
    }

    /** Management trigger for a reliable downlink. */
    public boolean sendDownlink(String deviceId, long seq, String command, String arg) {
        ConnectionHandler handler = handlers.get(deviceId);
        SessionState session = sessions.findActive(deviceId);
        if (handler == null || session == null || !session.isAlive()) {
            return false;
        }
        return handler.sendDownlink(seq, command, arg);
    }

    public SessionState session(String deviceId) {
        return sessions.findActive(deviceId);
    }
}
