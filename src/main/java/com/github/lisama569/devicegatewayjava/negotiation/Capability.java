package com.github.lisama569.devicegatewayjava.negotiation;

import java.util.Optional;

/**
 * 网关可协商的可选能力项（全仓枚举，只有这里列出的才“可识别”）。
 * code 用于协商负载；dataFlag 是该能力在已协商会话中允许出现在 DATA 帧 FLAGS 上的位。
 */
public enum Capability {
    COMPRESSION((byte) 1, (byte) 0x10),
    BATCH((byte) 2, (byte) 0x20);

    private final byte code;
    private final byte dataFlag;

    Capability(byte code, byte dataFlag) {
        this.code = code;
        this.dataFlag = dataFlag;
    }

    public byte code() { return code; }
    public byte dataFlag() { return dataFlag; }

    /** 识别协商负载中的能力 code；无法识别返回 empty（对应 1007）。 */
    public static Optional<Capability> fromCode(byte code) {
        for (Capability c : values()) {
            if (c.code == code) return Optional.of(c);
        }
        return Optional.empty();
    }

    /** DATA 帧 FLAGS 上所有属于“可协商能力”的位（其余 FLAGS 位一律不允许上行使用）。 */
    public static byte allDataFlags() {
        int bits = 0;
        for (Capability c : values()) {
            bits |= (c.dataFlag & 0xFF);
        }
        return (byte) bits;
    }
}
