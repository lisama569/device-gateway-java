package com.github.lisama569.devicegatewayjava.negotiation;

import com.github.lisama569.devicegatewayjava.protocol.Frame;
import com.github.lisama569.devicegatewayjava.protocol.FrameConstants;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/** 可协商能力项。每个能力对应帧上的一类扩展用法，未协商即使用会被拒绝。 */
public enum Capability {

    /** 允许在帧上使用 FLAG_PRIORITY 标志位。 */
    PRIORITY("priority"),
    /** 允许帧头保留字节非零（扩展字段）。 */
    HEADER_EXTENSION("header-extension");

    private final String wireName;

    Capability(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() { return wireName; }

    public static Optional<Capability> fromWireName(String name) {
        for (Capability c : values()) {
            if (c.wireName.equals(name)) return Optional.of(c);
        }
        return Optional.empty();
    }

    /** 该帧实际用到、因而必须已协商的能力集合。 */
    public static Set<Capability> requiredBy(Frame frame) {
        Set<Capability> required = new LinkedHashSet<>();
        if ((frame.flags() & FrameConstants.FLAG_PRIORITY) != 0) {
            required.add(PRIORITY);
        }
        if (frame.reserved() != 0) {
            required.add(HEADER_EXTENSION);
        }
        return required;
    }
}
