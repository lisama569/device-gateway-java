package com.github.lisama569.devicegatewayjava.support;

import com.github.lisama569.devicegatewayjava.auth.Authenticator;
import com.github.lisama569.devicegatewayjava.auth.DeviceCredential;
import com.github.lisama569.devicegatewayjava.auth.InMemoryCredentialStore;
import com.github.lisama569.devicegatewayjava.config.GatewayProperties;
import com.github.lisama569.devicegatewayjava.dispatch.DownlinkService;
import com.github.lisama569.devicegatewayjava.dispatch.FrameDispatcher;
import com.github.lisama569.devicegatewayjava.metrics.GatewayMetrics;
import com.github.lisama569.devicegatewayjava.reliability.ReliableDownlink;
import com.github.lisama569.devicegatewayjava.reliability.TaskScheduler;
import com.github.lisama569.devicegatewayjava.session.SessionRegistry;
import com.github.lisama569.devicegatewayjava.state.DeviceStateStore;
import com.github.lisama569.devicegatewayjava.transport.ConnectionRegistry;
import com.github.lisama569.devicegatewayjava.transport.GatewayServer;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/** 无 Spring 的网关装配：随机回环端口、可选手动时钟、可配置上限。 */
public final class GatewayTestHarness implements AutoCloseable {

    public final InMemoryCredentialStore credentials = new InMemoryCredentialStore();
    public final GatewayMetrics metrics = new GatewayMetrics();
    public final SessionRegistry sessions = new SessionRegistry();
    public final ConnectionRegistry connections = new ConnectionRegistry();
    public final DeviceStateStore states;
    public final TaskScheduler scheduler;
    public final ReliableDownlink reliable;
    public final DownlinkService downlink;
    public final GatewayServer server;

    private final GatewayProperties props;

    public GatewayTestHarness() {
        this(defaultProps(), new ManualTaskScheduler(), Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"),
                ZoneOffset.UTC));
    }

    public GatewayTestHarness(GatewayProperties props, TaskScheduler scheduler, Clock clock) {
        this.props = props;
        this.scheduler = scheduler;
        Authenticator authenticator = new Authenticator(credentials, clock);
        this.states = new DeviceStateStore(props.getSeqWindow());
        this.reliable = new ReliableDownlink(scheduler, props.getMaxRetries(), props.getAckTimeoutMs());
        FrameDispatcher dispatcher = new FrameDispatcher(authenticator, sessions, states, metrics);
        this.downlink = new DownlinkService(sessions, connections, reliable, metrics);
        this.server = new GatewayServer(props, dispatcher, reliable, sessions, connections, metrics);
        registerDefaults();
        server.start();
    }

    private void registerDefaults() {
        credentials.register(new DeviceCredential("device-001", "secret-001", 0L));
        credentials.register(new DeviceCredential("device-002", "secret-002", 0L));
        credentials.register(new DeviceCredential("device-expired", "old-secret",
                Instant.parse("2020-01-01T00:00:00Z").toEpochMilli()));
    }

    public static GatewayProperties defaultProps() {
        GatewayProperties p = new GatewayProperties();
        p.setPort(0);
        p.setMaxConnections(64);
        p.setMaxFrameLength(512);
        p.setMaxOutboundPerSession(16);
        p.setAckTimeoutMs(1000);
        p.setMaxRetries(2);
        p.setSeqWindow(8);
        p.setShutdownQuietPeriodMs(0);
        p.setShutdownTimeoutMs(2000);
        return p;
    }

    public int port() {
        return server.boundPort();
    }

    public GatewayProperties props() { return props; }

    public LoopbackClient newClient() {
        return new LoopbackClient(port());
    }

    @Override
    public void close() {
        server.stop();
        scheduler.shutdownNow();
    }
}
