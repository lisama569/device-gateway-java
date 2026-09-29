package com.github.lisama569.devicegatewayjava.config;

import com.github.lisama569.devicegatewayjava.auth.Authenticator;
import com.github.lisama569.devicegatewayjava.auth.CredentialStore;
import com.github.lisama569.devicegatewayjava.auth.DeviceCredential;
import com.github.lisama569.devicegatewayjava.auth.InMemoryCredentialStore;
import com.github.lisama569.devicegatewayjava.dispatch.DownlinkService;
import com.github.lisama569.devicegatewayjava.dispatch.FrameDispatcher;
import com.github.lisama569.devicegatewayjava.metrics.GatewayMetrics;
import com.github.lisama569.devicegatewayjava.negotiation.Negotiator;
import com.github.lisama569.devicegatewayjava.protocol.FrameConstants;
import com.github.lisama569.devicegatewayjava.reliability.ReliableDownlink;
import com.github.lisama569.devicegatewayjava.reliability.SimpleTaskScheduler;
import com.github.lisama569.devicegatewayjava.reliability.TaskScheduler;
import com.github.lisama569.devicegatewayjava.session.SessionRegistry;
import com.github.lisama569.devicegatewayjava.state.DeviceStateStore;
import com.github.lisama569.devicegatewayjava.transport.ConnectionRegistry;
import com.github.lisama569.devicegatewayjava.transport.GatewayServer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** 网关 Bean 装配（全部本地组件，无外部依赖）。 */
@Configuration
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayConfig {

    /** 默认演示凭证（测试可覆盖此 Bean）。 */
    @Bean
    public CredentialStore credentialStore() {
        InMemoryCredentialStore store = new InMemoryCredentialStore();
        store.register(new DeviceCredential("device-001", "secret-001", 0L));
        store.register(new DeviceCredential("device-002", "secret-002", 0L));
        store.register(new DeviceCredential("device-expired", "old-secret", 1L));
        return store;
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public Authenticator authenticator(CredentialStore store, Clock clock) {
        return new Authenticator(store, clock);
    }

    @Bean
    public GatewayMetrics gatewayMetrics(GatewayProperties props) {
        GatewayMetrics metrics = new GatewayMetrics();
        metrics.setReceiveResidualLimitBytes(props.getMaxReceiveResidualBytes());
        return metrics;
    }

    /** 网关自身支持的协议版本范围：当前仅 1（线格式恒为 v1，协商决定会话版本）。 */
    @Bean
    public Negotiator negotiator() {
        return new Negotiator(FrameConstants.PROTOCOL_VERSION, FrameConstants.PROTOCOL_VERSION);
    }

    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistry();
    }

    @Bean
    public ConnectionRegistry connectionRegistry() {
        return new ConnectionRegistry();
    }

    @Bean
    public DeviceStateStore deviceStateStore(GatewayProperties props) {
        return new DeviceStateStore(props.getSeqWindow());
    }

    @Bean
    public TaskScheduler taskScheduler(GatewayProperties props) {
        return new SimpleTaskScheduler(Math.max(1, props.getIoWorkerThreads()));
    }

    @Bean
    public ReliableDownlink reliableDownlink(TaskScheduler scheduler, GatewayProperties props) {
        return new ReliableDownlink(scheduler, props.getMaxRetries(), props.getAckTimeoutMs());
    }

    @Bean
    public FrameDispatcher frameDispatcher(Authenticator authenticator, SessionRegistry sessions,
                                           DeviceStateStore states, GatewayMetrics metrics,
                                           Negotiator negotiator) {
        return new FrameDispatcher(authenticator, sessions, states, metrics, negotiator);
    }

    @Bean
    public DownlinkService downlinkService(SessionRegistry sessions, ConnectionRegistry connections,
                                           ReliableDownlink reliable, GatewayMetrics metrics) {
        return new DownlinkService(sessions, connections, reliable, metrics);
    }

    @Bean
    public GatewayLifecycle gatewayLifecycle(GatewayProperties props, FrameDispatcher dispatcher,
                                             ReliableDownlink reliable, SessionRegistry sessions,
                                             ConnectionRegistry connections, GatewayMetrics metrics,
                                             TaskScheduler scheduler) {
        GatewayServer server = new GatewayServer(props, dispatcher, reliable, sessions, connections, metrics);
        return new GatewayLifecycle(server, scheduler);
    }

    /** 供管理面/测试直接使用服务器（如取实际端口）。 */
    @Bean
    public GatewayServer gatewayServer(GatewayLifecycle lifecycle) {
        return lifecycle.server();
    }
}
