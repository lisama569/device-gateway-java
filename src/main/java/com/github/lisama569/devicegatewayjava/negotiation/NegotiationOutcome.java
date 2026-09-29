package com.github.lisama569.devicegatewayjava.negotiation;

import java.util.List;

/**
 * 协商结论（纯值，无 IO）：
 * - AGREED：selectedVersion/selectedCapabilities 有效；
 * - VERSION_NO_INTERSECTION：设备版本区间与网关无交集；
 * - UNSUPPORTED_CAPABILITY：offered 中存在无法识别或不支持的能力项，unknownCodes 给出依据。
 */
public record NegotiationOutcome(Status status,
                                 int selectedVersion,
                                 List<Capability> selectedCapabilities,
                                 List<Byte> unknownCodes) {

    public enum Status { AGREED, VERSION_NO_INTERSECTION, UNSUPPORTED_CAPABILITY }

    public static NegotiationOutcome agreed(int version, List<Capability> caps) {
        return new NegotiationOutcome(Status.AGREED, version, List.copyOf(caps), List.of());
    }

    public static NegotiationOutcome versionNoIntersection() {
        return new NegotiationOutcome(Status.VERSION_NO_INTERSECTION, 0, List.of(), List.of());
    }

    public static NegotiationOutcome unsupportedCapability(List<Byte> unknownCodes) {
        return new NegotiationOutcome(Status.UNSUPPORTED_CAPABILITY, 0, List.of(),
                List.copyOf(unknownCodes));
    }
}
