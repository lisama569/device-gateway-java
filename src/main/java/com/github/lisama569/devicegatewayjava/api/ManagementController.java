package com.github.lisama569.devicegatewayjava.api;

import com.github.lisama569.devicegatewayjava.gateway.GatewayServer;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Loopback-only management: trigger reliable downlinks for tests/ops. */
@RestController
@RequestMapping("/gateway/devices")
public class ManagementController {

    private final GatewayServer server;

    public ManagementController(GatewayServer server) {
        this.server = server;
    }

    public record DownlinkRequest(long seq, String command, String arg) {
    }

    @PostMapping("/{deviceId}/downlink")
    public Map<String, Object> downlink(@PathVariable String deviceId,
                                        @RequestBody DownlinkRequest request) {
        boolean queued = server.sendDownlink(deviceId, request.seq(),
                request.command() == null ? "" : request.command(),
                request.arg() == null ? "" : request.arg());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deviceId", deviceId);
        out.put("seq", request.seq());
        out.put("queued", queued);
        if (!queued) {
            out.put("reason", "no active session for device or outbound queue is full");
        }
        return out;
    }
}
