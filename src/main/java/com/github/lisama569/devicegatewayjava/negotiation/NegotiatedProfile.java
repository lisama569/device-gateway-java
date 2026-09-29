package com.github.lisama569.devicegatewayjava.negotiation;

import java.util.Set;

/** 协商确定后会话必须遵守的硬约束：选定的协议版本 + 双方一致的能力集合。 */
public record NegotiatedProfile(int version, Set<String> capabilities) {

    public NegotiatedProfile {
        capabilities = Set.copyOf(capabilities);
    }

    public boolean allows(Capability capability) {
        return capabilities.contains(capability.wireName());
    }
}
