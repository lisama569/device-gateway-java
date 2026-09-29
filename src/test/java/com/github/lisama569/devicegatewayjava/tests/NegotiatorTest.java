package com.github.lisama569.devicegatewayjava.tests;

import com.github.lisama569.devicegatewayjava.error.GatewayErrorCode;
import com.github.lisama569.devicegatewayjava.error.GatewayException;
import com.github.lisama569.devicegatewayjava.negotiation.Capability;
import com.github.lisama569.devicegatewayjava.negotiation.NegotiationFrames;
import com.github.lisama569.devicegatewayjava.negotiation.NegotiationOutcome;
import com.github.lisama569.devicegatewayjava.negotiation.NegotiationRequest;
import com.github.lisama569.devicegatewayjava.negotiation.Negotiator;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 纯协商策略与负载编解码：三种结论可区分，选择规则确定。 */
class NegotiatorTest {

    private static final Logger log = LoggerFactory.getLogger(NegotiatorTest.class);

    private final Negotiator negotiator = new Negotiator(1, 2); // 测试用网关支持 1..2

    @Test
    void agreedPicksHighestCommonVersionAndCapabilityIntersection() {
        NegotiationRequest req = new NegotiationRequest(1, 3, List.of((byte) 1, (byte) 2));
        NegotiationOutcome o = negotiator.evaluate(req);
        log.info("[谈成] 设备[1,3] caps=1,2 网关[1,2] -> {} ver={} caps={}",
                o.status(), o.selectedVersion(), o.selectedCapabilities());
        assertEquals(NegotiationOutcome.Status.AGREED, o.status());
        assertEquals(2, o.selectedVersion(), "交集中选最高版本");
        assertTrue(o.selectedCapabilities().contains(Capability.COMPRESSION));
        assertTrue(o.selectedCapabilities().contains(Capability.BATCH));
    }

    @Test
    void agreedWithEmptyCapabilitiesAndSingletonRange() {
        NegotiationRequest req = new NegotiationRequest(2, 2, List.of());
        NegotiationOutcome o = negotiator.evaluate(req);
        assertEquals(NegotiationOutcome.Status.AGREED, o.status());
        assertEquals(2, o.selectedVersion());
        assertTrue(o.selectedCapabilities().isEmpty());
    }

    @Test
    void versionRangesWithNoIntersectionAreReported() {
        NegotiationRequest req = new NegotiationRequest(3, 5, List.of());
        NegotiationOutcome o = negotiator.evaluate(req);
        log.info("[版本无交集] 设备[3,5] 网关[1,2] -> {}", o.status());
        assertEquals(NegotiationOutcome.Status.VERSION_NO_INTERSECTION, o.status());
    }

    @Test
    void unknownCapabilityWinsOverVersionMismatch() {
        // 即使版本也不沾边，未知能力必须先报 UNSUPPORTED_CAPABILITY，结论不暧昧
        NegotiationRequest req = new NegotiationRequest(3, 5, List.of((byte) 99));
        NegotiationOutcome o = negotiator.evaluate(req);
        log.info("[能力不识别] 设备 caps 含 99 且版本超界 -> {} unknown={}",
                o.status(), o.unknownCodes());
        assertEquals(NegotiationOutcome.Status.UNSUPPORTED_CAPABILITY, o.status());
        assertEquals(List.of((byte) 99), o.unknownCodes());
    }

    @Test
    void duplicateOfferedCodesAreDedupedKeepingOrder() throws GatewayException {
        NegotiationRequest parsed = NegotiationFrames.parseRequest(
                com.github.lisama569.devicegatewayjava.support.LoopbackClient
                        .negotiatePayload(1, 2, 1, 2, 1));
        assertEquals(List.of((byte) 1, (byte) 2), parsed.offeredCapabilityCodes());
    }

    @Test
    void malformedPayloadsRejected() {
        assertThrows(GatewayException.class, () -> NegotiationFrames.parseRequest(new byte[]{1}),
                "too short");
        GatewayException badRange = assertThrows(GatewayException.class,
                () -> NegotiationFrames.parseRequest(new byte[]{3, 1, 0}));
        assertEquals(GatewayErrorCode.MALFORMED_PAYLOAD, badRange.errorCode(), "min>max");
        GatewayException badCount = assertThrows(GatewayException.class,
                () -> NegotiationFrames.parseRequest(new byte[]{1, 2, 2, 1}));
        assertEquals(GatewayErrorCode.MALFORMED_PAYLOAD, badCount.errorCode(), "count mismatch");
        log.info("[负载畸形] 短包/min>max/计数不符 全部明确为 MALFORMED_PAYLOAD");
    }

    @Test
    void ackPayloadRoundTrip() {
        NegotiationOutcome o = NegotiationOutcome.agreed(2,
                List.of(Capability.COMPRESSION, Capability.BATCH));
        byte[] raw = NegotiationFrames.encodeAck(o);
        assertEquals(NegotiationOutcome.Status.AGREED, NegotiationFrames.parseAckStatus(raw));
        assertEquals(2, NegotiationFrames.parseAckSelectedVersion(raw));
        assertEquals(List.of((byte) 1, (byte) 2), NegotiationFrames.parseAckCapabilities(raw));
    }
}
