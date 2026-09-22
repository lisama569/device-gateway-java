package com.github.lisama569.devicegatewayjava.protocol;

import java.util.Optional;

/** 协议消息类型（版本 1）。 */
public enum MessageType {
    LOGIN((byte) 0x01, true),
    LOGOUT((byte) 0x02, true),
    DATA((byte) 0x10, true),
    DOWNLINK((byte) 0x20, false),
    ACK((byte) 0x21, true),
    ERROR((byte) 0x7F, false);

    private final byte code;
    private final boolean uplink;

    MessageType(byte code, boolean uplink) {
        this.code = code;
        this.uplink = uplink;
    }

    public byte code() { return code; }

    public boolean uplink() { return uplink; }

    public static Optional<MessageType> fromCode(byte code) {
        for (MessageType t : values()) {
            if (t.code == code) return Optional.of(t);
        }
        return Optional.empty();
    }
}
