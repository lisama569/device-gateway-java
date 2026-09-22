package com.github.lisama569.devicegatewayjava.protocol;

/**
 * Encodes frames to wire bytes.
 *
 * <pre>
 *  offset  size  field
 *  0       2     magic 0x4A 0x57
 *  2       1     protocol version
 *  3       1     message type
 *  4       1     flags
 *  5       4     sequence (unsigned 32-bit, big-endian)
 *  9       4     payload length n (unsigned 32-bit, big-endian)
 *  13      2     header CRC (CRC over bytes 0..12)
 *  15      n     payload
 *  15+n    2     body CRC (CRC over payload)
 * </pre>
 */
public final class FrameEncoder {

    private FrameEncoder() {
    }

    public static byte[] encode(Frame frame) {
        return encode(frame, 1);
    }

    public static byte[] encode(Frame frame, int version) {
        byte[] payload = frame.payload() == null ? new byte[0] : frame.payload();
        if (payload.length > Frame.MAX_HARD_PAYLOAD) {
            throw new IllegalArgumentException("payload exceeds hard limit: " + payload.length);
        }
        byte[] out = new byte[Frame.HEADER_LEN + payload.length + Frame.CRC_LEN];
        out[0] = (byte) Frame.MAGIC0;
        out[1] = (byte) Frame.MAGIC1;
        out[2] = (byte) version;
        out[3] = frame.type();
        out[4] = frame.flags();
        writeUInt32(out, 5, frame.seq());
        writeUInt32(out, 9, payload.length);
        int headerCrc = Crc16.crc(out, 0, 13);
        writeUInt16(out, 13, headerCrc);
        System.arraycopy(payload, 0, out, Frame.HEADER_LEN, payload.length);
        int bodyCrc = Crc16.crc(payload, 0, payload.length);
        writeUInt16(out, Frame.HEADER_LEN + payload.length, bodyCrc);
        return out;
    }

    static void writeUInt16(byte[] b, int off, int v) {
        b[off] = (byte) ((v >>> 8) & 0xFF);
        b[off + 1] = (byte) (v & 0xFF);
    }

    static void writeUInt32(byte[] b, int off, long v) {
        b[off] = (byte) ((v >>> 24) & 0xFF);
        b[off + 1] = (byte) ((v >>> 16) & 0xFF);
        b[off + 2] = (byte) ((v >>> 8) & 0xFF);
        b[off + 3] = (byte) (v & 0xFF);
    }
}
