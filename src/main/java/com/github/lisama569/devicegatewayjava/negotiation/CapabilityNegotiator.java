package com.github.lisama569.devicegatewayjava.negotiation;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 协商决策（纯函数，可单测）：
 * 在设备声明的版本区间与网关支持区间内选出版本，并校验能力项是否全部可识别且被支持。
 * 三种结论明确区分：谈成 / 版本区间无交集 / 存在不识别或不支持的能力项。
 */
public final class CapabilityNegotiator {

    public sealed interface Result {
        record Ok(NegotiatedProfile profile) implements Result {}
        record VersionIncompatible(int deviceMin, int deviceMax, int gatewayMin, int gatewayMax)
                implements Result {}
        record UnsupportedCapabilities(List<String> unsupported) implements Result {}
    }

    private final int gatewayMinVersion;
    private final int gatewayMaxVersion;
    private final Set<String> supportedCapabilities;

    public CapabilityNegotiator(int gatewayMinVersion, int gatewayMaxVersion,
                                Set<String> supportedCapabilities) {
        if (gatewayMinVersion <= 0 || gatewayMinVersion > gatewayMaxVersion) {
            throw new IllegalArgumentException("invalid gateway version range: "
                    + gatewayMinVersion + ".." + gatewayMaxVersion);
        }
        this.gatewayMinVersion = gatewayMinVersion;
        this.gatewayMaxVersion = gatewayMaxVersion;
        this.supportedCapabilities = Set.copyOf(supportedCapabilities);
    }

    public Result negotiate(HelloPayload.Hello hello) {
        int lo = Math.max(hello.minVersion(), gatewayMinVersion);
        int hi = Math.min(hello.maxVersion(), gatewayMaxVersion);
        if (lo > hi) {
            return new Result.VersionIncompatible(hello.minVersion(), hello.maxVersion(),
                    gatewayMinVersion, gatewayMaxVersion);
        }
        // 先收集全部不识别/不支持的能力项，一次性告知
        List<String> unsupported = hello.capabilities().stream()
                .filter(c -> Capability.fromWireName(c).isEmpty() || !supportedCapabilities.contains(c))
                .distinct()
                .toList();
        if (!unsupported.isEmpty()) {
            return new Result.UnsupportedCapabilities(unsupported);
        }
        // 选双方区间内的最高版本；能力集合即设备请求集合（均已确认支持）
        return new Result.Ok(new NegotiatedProfile(hi, new LinkedHashSet<>(hello.capabilities())));
    }

    public int gatewayMinVersion() { return gatewayMinVersion; }
    public int gatewayMaxVersion() { return gatewayMaxVersion; }
    public Set<String> supportedCapabilities() { return supportedCapabilities; }
}
