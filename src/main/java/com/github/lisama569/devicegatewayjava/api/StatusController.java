package com.github.lisama569.devicegatewayjava.api;

import com.github.lisama569.devicegatewayjava.gateway.GatewayMetrics;
import com.github.lisama569.devicegatewayjava.gateway.GatewayServer;
import com.github.lisama569.devicegatewayjava.session.SessionRegistry;
import com.github.lisama569.devicegatewayjava.session.SessionState;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Loopback-only observability endpoint: status, sessions, error counters. */
@RestController
@RequestMapping("/gateway")
public class StatusController {

    private final GatewayServer server;
    private final SessionRegistry sessions;
    private final GatewayMetrics metrics;

    public StatusController(GatewayServer server, SessionRegistry sessions, GatewayMetrics metrics) {
        this.server = server;
        this.sessions = sessions;
        this.metrics = metrics;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("running", server.isRunning());
        out.put("port", server.boundPort());
        out.put("activeConnections", server.activeConnections());
        out.put("activeSessions", sessions.activeCount());
        Map<String, Map<String, Object>> sessionMap = new LinkedHashMap<>();
        for (SessionState s : sessions.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sessionId", s.sessionId());
            m.put("status", s.status());
            m.put("resumeSeq", s.resumeSeq());
            m.put("connectedAt", s.connectedAtMillis());
            m.put("terminateReason", s.terminateReason());
            sessionMap.put(s.deviceId(), m);
        }
        out.put("sessions", sessionMap);
        out.put("counters", metrics.snapshot());
        return out;
    }
}
