package com.github.lisama569.devicegatewayjava.negotiation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 纯协商策略：无状态、无 IO、可单测。
 *
 * 规则（三种结论必须可明确区分）：
 * 1. 设备列出的能力 code 先逐项过“网关可识别且支持”这一关；任何一项不认识 ->
 *    UNSUPPORTED_CAPABILITY，unknownCodes 给出全部不可识别项（判定依据可复现）；
 * 2. 否则求双方版本闭区间交集；无交集 -> VERSION_NO_INTERSECTION；
 * 3. 否则在交集中选双方都能跑的最高版本，能力取交集（设备上报顺序），AGREED。
 *
 * 优先级刻意定为“能力不识别 > 版本无交集”：带未知能力的请求即使版本也不沾边，
 * 也应告诉设备“先去掉你那套我不懂的东西”，结论不暧昧。
 */
public final class Negotiator {

    private static final Logger log = LoggerFactory.getLogger(Negotiator.class);

    private final int gatewayMinVersion;
    private final int gatewayMaxVersion;

    public Negotiator(int gatewayMinVersion, int gatewayMaxVersion) {
        if (gatewayMinVersion <= 0 || gatewayMaxVersion < gatewayMinVersion) {
            throw new IllegalArgumentException("bad gateway version range");
        }
        this.gatewayMinVersion = gatewayMinVersion;
        this.gatewayMaxVersion = gatewayMaxVersion;
    }

    public int gatewayMinVersion() { return gatewayMinVersion; }
    public int gatewayMaxVersion() { return gatewayMaxVersion; }

    public NegotiationOutcome evaluate(NegotiationRequest request) {
        // 1) 能力识别关：保留设备上报顺序，便于日志/ACK 给出确定依据
        List<Byte> unknown = new ArrayList<>();
        List<Capability> recognized = new ArrayList<>();
        for (Byte code : request.offeredCapabilityCodes()) {
            Optional<Capability> c = Capability.fromCode(code);
            if (c.isEmpty()) {
                unknown.add(code);
            } else if (!recognized.contains(c.get())) {
                recognized.add(c.get());
            }
        }
        if (!unknown.isEmpty()) {
            log.info("negotiation -> unsupported capability offered={}", unsigned(unknown));
            return NegotiationOutcome.unsupportedCapability(unknown);
        }

        // 2) 版本交集关
        int lo = Math.max(gatewayMinVersion, request.minVersion());
        int hi = Math.min(gatewayMaxVersion, request.maxVersion());
        if (lo > hi) {
            log.info("negotiation -> no version intersection device=[{},{}] gateway=[{},{}]",
                    request.minVersion(), request.maxVersion(), gatewayMinVersion, gatewayMaxVersion);
            return NegotiationOutcome.versionNoIntersection();
        }

        // 3) 交集中取最高版本；能力是枚举全集（当前网关支持全部已识别能力）
        int selected = hi;
        log.info("negotiation -> agreed version={} deviceRange=[{},{}] caps={}",
                selected, request.minVersion(), request.maxVersion(),
                recognized.stream().map(Enum::name).toList());
        return NegotiationOutcome.agreed(selected, recognized);
    }

    private static List<Integer> unsigned(List<Byte> codes) {
        return codes.stream().map(b -> b & 0xFF).toList();
    }
}
