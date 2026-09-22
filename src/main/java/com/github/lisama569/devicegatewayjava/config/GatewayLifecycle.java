package com.github.lisama569.devicegatewayjava.config;

import com.github.lisama569.devicegatewayjava.reliability.TaskScheduler;
import com.github.lisama569.devicegatewayjava.transport.GatewayServer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/** 随 Spring 容器启动/停止网关。 */
public class GatewayLifecycle {

    private final GatewayServer server;
    private final TaskScheduler scheduler;

    public GatewayLifecycle(GatewayServer server, TaskScheduler scheduler) {
        this.server = server;
        this.scheduler = scheduler;
    }

    public GatewayServer server() { return server; }

    @PostConstruct
    public void start() {
        server.start();
    }

    @PreDestroy
    public void stop() {
        server.stop();
        scheduler.shutdownNow();
    }
}
