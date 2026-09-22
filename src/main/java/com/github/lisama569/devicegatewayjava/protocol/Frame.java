package com.github.lisama569.devicegatewayjava.protocol;

/** Immutable decoded frame. Header is 15 bytes; body = payload + 2 byte body CRC. */
public record Frame(byte type, byte flags, long seq, byte[] payload) {

    public static final int MAGIC0 = 0x4A;
    public static final int MAGIC1 = 0x57;
    public static final int HEADER_LEN = 15;
    public static final int CRC_LEN = 2;

    public static final int MAX_HARD_PAYLOAD = 1 << 20; // 1 MiB hard ceiling

    // frame types
    public static final byte T_HELLO = 0x01;
    public static final byte T_HELLO_ACK = 0x02;
    public static final byte T_LOGIN = 0x03;
    public static final byte T_LOGIN_ACK = 0x04;
    public static final byte T_UPLINK = 0x05;
    public static final byte T_UPLINK_ACK = 0x06;
    public static final byte T_DOWNLINK = 0x07;
    public static final byte T_DOWNLINK_ACK = 0x08;
    public static final byte T_ERROR = 0x09;
    public static final byte T_LOGOUT = 0x0A;
    public static final byte T_HEARTBEAT = 0x0B;

    public static final int FLAG_DUP = 0x01;
}
