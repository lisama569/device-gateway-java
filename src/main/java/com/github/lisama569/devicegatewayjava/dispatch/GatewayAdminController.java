package com.github.lisama569.devicegatewayjava.dispatch;

import com.github.lisama569.devicegatewayjava.metrics.GatewayMetrics;
import com.github.lisama569.devicegatewayjava.session.SessionRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** 本机管理接口（与设备 TCP 服务一样只面向回环使用）。 */
@RestController
public class GatewayAdminController {

    private final DownlinkService downlink;
    private final GatewayMetrics metrics;
    private final SessionRegistry sessions;

    public GatewayAdminController(DownlinkService downlink, GatewayMetrics metrics,
                                  SessionRegistry sessions) {
        this.downlink = downlink;
        this.metrics = metrics;
        this.sessions = sessions;
    }

    @GetMapping("/admin/metrics")
    public Map<String, Long> metrics() {
        return metrics.snapshot();
    }

    @GetMapping("/admin/sessions")
    public Map<String, String> sessions() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("activeAuthenticatedSessions", String.valueOf(sessions.activeCount()));
        return m;
    }

    @PostMapping("/admin/downlink")
    public ResponseEntity<Map<String, Object>> sendDownlink(@RequestParam String deviceId,
                                                            @RequestBody(required = false) byte[] body) {
        byte[] payload = body == null ? new byte[0] : body;
        DownlinkService.Result r = downlink.sendReliable(deviceId, payload);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("accepted", r.accepted());
        resp.put("errorCode", r.errorCode());
        resp.put("detail", r.detail());
        return ResponseEntity.status(r.accepted() ? 202 : 503).body(resp);
    }
}
