package com.github.lisama569.devicegatewayjava.protocol;

/** 二进制帧常量：
 * 帧格式: MAGIC(2)=0xA55A | VERSION(1) | TYPE(1) | FLAGS(1) | RESERVED(1) | SEQ(4,BE) | LENGTH(4,BE) | PAYLOAD | CRC16(2,BE) */
public final class FrameConstants {
    private FrameConstants() {}

    public static final int MAGIC = 0xA55A;
    public static final byte PROTOCOL_VERSION = 0x01;

    public static final int MAGIC_LEN = 2;
    public static final int HEADER_LEN = 14;
    public static final int CRC_LEN = 2;
    public static final int MIN_FRAME_LEN = HEADER_LEN + CRC_LEN;

    /** 标志位：下行消息需要设备确认 */
    public static final byte FLAG_RELIABLE = 0x01;
}
