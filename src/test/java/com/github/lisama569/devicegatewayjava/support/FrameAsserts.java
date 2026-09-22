package com.github.lisama569.devicegatewayjava.support;

import com.github.lisama569.devicegatewayjava.protocol.Crc16;
import com.github.lisama569.devicegatewayjava.protocol.FrameConstants;
import com.github.lisama569.devicegatewayjava.protocol.ErrorFrames;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 从连接读取完整帧并做断言（不经过解码器）。 */
public final class FrameAsserts {
    private FrameAsserts() {}

    /** 读一整帧，校验 MAGIC/CRC，返回 (type, seq, payload)。 */
    public static RawFrame readFrame(LoopbackClient client) {
        byte[] header = client.readN(FrameConstants.HEADER_LEN);
        assertEquals(FrameConstants.MAGIC >>> 8, header[0] & 0xFF, "magic hi");
        assertEquals(FrameConstants.MAGIC & 0xFF, header[1] & 0xFF, "magic lo");
        int type = header[3] & 0xFF;
        long seq = ((long) (header[6] & 0xFF) << 24) | ((header[7] & 0xFF) << 16)
                | ((header[8] & 0xFF) << 8) | (header[9] & 0xFF);
        int len = ((header[10] & 0xFF) << 24) | ((header[11] & 0xFF) << 16)
                | ((header[12] & 0xFF) << 8) | (header[13] & 0xFF);
        byte[] rest = client.readN(len + 2);
        byte[] payload = new byte[len];
        System.arraycopy(rest, 0, payload, 0, len);
        // CRC 覆盖 header + payload
        byte[] body = new byte[FrameConstants.HEADER_LEN + len];
        System.arraycopy(header, 0, body, 0, header.length);
        System.arraycopy(payload, 0, body, header.length, len);
        int calc = Crc16.checksum(body, 0, body.length);
        int recv = ((rest[len] & 0xFF) << 8) | (rest[len + 1] & 0xFF);
        assertEquals(calc, recv, "frame crc");
        return new RawFrame(type, seq, payload);
    }

    public record RawFrame(int type, long seq, byte[] payload) {}

    public static ErrorFrames.Parsed readErrorFrame(LoopbackClient client) {
        RawFrame f = readFrame(client);
        assertEquals(0x7F, f.type(), "expected ERROR frame");
        return ErrorFrames.parsePayload(f.payload());
    }

    public static int readFrameType(LoopbackClient client) {
        return readFrame(client).type();
    }

    public static void assertReasonNoSecret(ErrorFrames.Parsed parsed, String secret) {
        assertTrue(secret == null || !parsed.reason().contains(secret),
                "error reason must not contain credential, got: " + parsed.reason());
    }
}
