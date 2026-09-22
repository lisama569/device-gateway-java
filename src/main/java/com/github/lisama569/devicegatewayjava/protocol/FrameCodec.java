package com.github.lisama569.devicegatewayjava.protocol;

/**
 * 帧编码器：
 * MAGIC(2) VERSION(1) TYPE(1) FLAGS(1) RSV(1) SEQ(4,BE) LEN(4,BE) PAYLOAD[LEN] CRC16(2,BE)
 * CRC 覆盖除 CRC 自身外的全部字节。
 */
public final class FrameCodec {
    private FrameCodec() {}

    public static byte[] encode(Frame frame) {
        byte[] payload = frame.payload() == null ? new byte[0] : frame.payload();
        int total = FrameConstants.HEADER_LEN + payload.length + FrameConstants.CRC_LEN;
        byte[] out = new byte[total];

        out[0] = (byte) (FrameConstants.MAGIC >>> 8);
        out[1] = (byte) FrameConstants.MAGIC;
        out[2] = frame.version();
        out[3] = frame.type().code();
        out[4] = frame.flags();
        out[5] = 0;
        long seq = frame.seq() & 0xFFFFFFFFL;
        out[6] = (byte) (seq >>> 24);
        out[7] = (byte) (seq >>> 16);
        out[8] = (byte) (seq >>> 8);
        out[9] = (byte) seq;
        int len = payload.length;
        out[10] = (byte) (len >>> 24);
        out[11] = (byte) (len >>> 16);
        out[12] = (byte) (len >>> 8);
        out[13] = (byte) len;
        System.arraycopy(payload, 0, out, FrameConstants.HEADER_LEN, len);

        int crc = Crc16.checksum(out, 0, FrameConstants.HEADER_LEN + len);
        out[total - 2] = (byte) (crc >>> 8);
        out[total - 1] = (byte) crc;
        return out;
    }

    public static byte[] encode(MessageType type, byte flags, long seq, byte[] payload) {
        return encode(new Frame(FrameConstants.PROTOCOL_VERSION, type, flags, seq, payload));
    }
}
