package com.github.lisama569.devicegatewayjava.tests;

import com.github.lisama569.devicegatewayjava.dispatch.GatewayAdminController;
import com.github.lisama569.devicegatewayjava.protocol.MessageType;
import com.github.lisama569.devicegatewayjava.support.FrameAsserts;
import com.github.lisama569.devicegatewayjava.support.GatewayTestHarness;
import com.github.lisama569.devicegatewayjava.support.LoopbackClient;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 管理面快照包含协商三分类、残留上限与触发次数（不依赖真实 HTTP，直接走控制器）。 */
class AdminObservabilityTest {

    private static final Logger log = LoggerFactory.getLogger(AdminObservabilityTest.class);

    @Test
    void metricsSnapshotExposesNegotiationAndReceiveLimit() {
        try (GatewayTestHarness h = new GatewayTestHarness();
             LoopbackClient ok = h.newClient();
             LoopbackClient badVer = h.newClient()) {
            ok.login("device-001", "secret-001", 1);
            assertEquals(MessageType.ACK.code(), FrameAsserts.readFrameType(ok));

            badVer.sendNegotiate(0, 2, 3);
            FrameAsserts.readFrame(badVer); // NEGOTIATE_ACK
            FrameAsserts.readErrorFrame(badVer); // 1002

            GatewayAdminController admin = new GatewayAdminController(h.downlink, h.metrics, h.sessions);
            Map<String, Long> m = admin.metrics();
            log.info("[管理面] 协商与容量相关快照: negotiationsSucceeded={} versionFailed={} "
                            + "capabilityFailed={} receiveBufferTerminations={} receiveResidualLimitBytes={}",
                    m.get("negotiationsSucceeded"), m.get("negotiationsVersionFailed"),
                    m.get("negotiationsCapabilityFailed"), m.get("receiveBufferTerminations"),
                    m.get("receiveResidualLimitBytes"));
            assertEquals(1L, m.get("negotiationsSucceeded"));
            assertEquals(1L, m.get("negotiationsVersionFailed"));
            assertEquals(0L, m.get("negotiationsCapabilityFailed"));
            assertEquals(0L, m.get("receiveBufferTerminations"));
            assertEquals((long) h.props().getMaxReceiveResidualBytes(),
                    m.get("receiveResidualLimitBytes"));
        }
    }
}
