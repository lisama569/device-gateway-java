package com.github.lisama569.devicegatewayjava.protocol;

import java.util.Optional;

/** 协议消息类型（版本 1）。 */
public enum MessageType {
    LOGIN((byte) 0x01, true),
    LOGOUT((byte) 0x02, true),
    /** 能力协商请求：连接建立后、LOGIN 之前的唯一首帧（上行）。 */
    NEGOTIATE((byte) 0x03, true),
    /** 能力协商确认：网关选出共同版本与能力后下发（下行）。 */
    NEGOTIATE_ACK((byte) 0x04, false),
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
