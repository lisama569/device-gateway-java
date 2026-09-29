package com.github.lisama569.devicegatewayjava.protocol;

/** 解析后的帧（payload 为帧内负载的拷贝，不含 CRC）。 */
public final class Frame {
    private final byte version;
    private final MessageType type;
    private final byte flags;
    private final byte reserved;
    private final long seq;
    private final byte[] payload;

    public Frame(byte version, MessageType type, byte flags, long seq, byte[] payload) {
        this(version, type, flags, (byte) 0, seq, payload);
    }

    public Frame(byte version, MessageType type, byte flags, byte reserved, long seq, byte[] payload) {
        this.version = version;
        this.type = type;
        this.flags = flags;
        this.reserved = reserved;
        this.seq = seq;
        this.payload = payload;
    }

    public byte version() { return version; }
    /** 头部保留字节：非零即视为扩展字段，需已协商 header-extension 能力。 */
    public byte reserved() { return reserved; }
    public MessageType type() { return type; }
    public byte flags() { return flags; }
    public long seq() { return seq; }
    public byte[] payload() { return payload; }
    public boolean reliable() { return (flags & FrameConstants.FLAG_RELIABLE) != 0; }
}
