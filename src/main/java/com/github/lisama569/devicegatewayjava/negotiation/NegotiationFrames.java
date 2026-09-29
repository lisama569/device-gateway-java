package com.github.lisama569.devicegatewayjava.negotiation;

import com.github.lisama569.devicegatewayjava.error.GatewayErrorCode;
import com.github.lisama569.devicegatewayjava.error.GatewayException;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * NEGOTIATE / NEGOTIATE_ACK 负载编解码（纯字节处理，无帧封装）。
 *
 * NEGOTIATE payload（设备 -> 网关）:
 *   minVer(1,uint) maxVer(1,uint) count(1,uint) capabilityCode(1) * count
 * NEGOTIATE_ACK payload（网关 -> 设备）:
 *   status(1: 0=AGREED 1=VERSION_NO_INTERSECTION 2=UNSUPPORTED_CAPABILITY)
 *   selectedVersion(1) count(1) capabilityCode(1) * count
 *
 * 线格式本身恒为帧头 VERSION=1；这里协商的是“会话协议版本”。
 * 解析失败统一抛 MALFORMED_PAYLOAD(1006)，调用方按协商失败处理（发 ERROR 后关连接）。
 */
public final class NegotiationFrames {

    public static final int STATUS_AGREED = 0;
    public static final int STATUS_VERSION_NO_INTERSECTION = 1;
    public static final int STATUS_UNSUPPORTED_CAPABILITY = 2;

    private NegotiationFrames() {}

    public static NegotiationRequest parseRequest(byte[] payload) throws GatewayException {
        if (payload == null || payload.length < 3) {
            throw new GatewayException(GatewayErrorCode.MALFORMED_PAYLOAD,
                    "negotiate payload too short: " + (payload == null ? 0 : payload.length));
        }
        int minVersion = payload[0] & 0xFF;
        int maxVersion = payload[1] & 0xFF;
        int count = payload[2] & 0xFF;
        if (minVersion == 0 || maxVersion == 0 || minVersion > maxVersion) {
            throw new GatewayException(GatewayErrorCode.MALFORMED_PAYLOAD,
                    "invalid version range min=" + minVersion + " max=" + maxVersion);
        }
        if (payload.length != 3 + count) {
            throw new GatewayException(GatewayErrorCode.MALFORMED_PAYLOAD,
                    "capability count=" + count + " but trailing bytes=" + (payload.length - 3));
        }
        if (count > 255) {
            throw new GatewayException(GatewayErrorCode.MALFORMED_PAYLOAD, "capability count out of range");
        }
        // 去重但保持上报顺序，保证后续判定依据稳定可复现
        Set<Byte> seen = new LinkedHashSet<>();
        for (int i = 0; i < count; i++) {
            seen.add(payload[3 + i]);
        }
        return new NegotiationRequest(minVersion, maxVersion, new ArrayList<>(seen));
    }

    public static byte[] encodeAck(NegotiationOutcome outcome) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        switch (outcome.status()) {
            case AGREED -> bos.write(STATUS_AGREED);
            case VERSION_NO_INTERSECTION -> bos.write(STATUS_VERSION_NO_INTERSECTION);
            case UNSUPPORTED_CAPABILITY -> bos.write(STATUS_UNSUPPORTED_CAPABILITY);
        }
        bos.write(outcome.selectedVersion() & 0xFF);
        bos.write(outcome.selectedCapabilities().size());
        for (Capability c : outcome.selectedCapabilities()) {
            bos.write(c.code() & 0xFF);
        }
        return bos.toByteArray();
    }

    /** 读取 ACK 负载中的状态码（测试可观测）。 */
    public static NegotiationOutcome.Status parseAckStatus(byte[] payload) {
        if (payload == null || payload.length < 1) {
            throw new IllegalArgumentException("empty negotiate-ack payload");
        }
        return switch (payload[0] & 0xFF) {
            case STATUS_AGREED -> NegotiationOutcome.Status.AGREED;
            case STATUS_VERSION_NO_INTERSECTION -> NegotiationOutcome.Status.VERSION_NO_INTERSECTION;
            case STATUS_UNSUPPORTED_CAPABILITY -> NegotiationOutcome.Status.UNSUPPORTED_CAPABILITY;
            default -> throw new IllegalArgumentException("unknown ack status " + (payload[0] & 0xFF));
        };
    }

    /** 读取 ACK 负载中选定的版本（测试可观测）。 */
    public static int parseAckSelectedVersion(byte[] payload) {
        if (payload == null || payload.length < 2) {
            throw new IllegalArgumentException("short negotiate-ack payload");
        }
        return payload[1] & 0xFF;
    }

    /** 读取 ACK 负载中确认的能力 code 列表（测试可观测）。 */
    public static List<Byte> parseAckCapabilities(byte[] payload) {
        if (payload == null || payload.length < 3) {
            throw new IllegalArgumentException("short negotiate-ack payload");
        }
        int count = payload[2] & 0xFF;
        if (payload.length != 3 + count) {
            throw new IllegalArgumentException("capability count mismatch");
        }
        List<Byte> codes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            codes.add(payload[3 + i]);
        }
        return codes;
    }
}
