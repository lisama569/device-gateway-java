package com.github.lisama569.devicegatewayjava.protocol;

/** 解析后的帧（payload 为帧内负载的拷贝，不含 CRC）。 */
public final class Frame {
    private final byte version;
    private final MessageType type;
    private final byte flags;
    private final long seq;
    private final byte[] payload;

    public Frame(byte version, MessageType type, byte flags, long seq, byte[] payload) {
        this.version = version;
        this.type = type;
        this.flags = flags;
        this.seq = seq;
        this.payload = payload;
    }

    public byte version() { return version; }
    public MessageType type() { return type; }
    public byte flags() { return flags; }
    public long seq() { return seq; }
    public byte[] payload() { return payload; }
    public boolean reliable() { return (flags & FrameConstants.FLAG_RELIABLE) != 0; }
}
