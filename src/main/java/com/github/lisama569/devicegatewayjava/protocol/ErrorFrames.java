package com.github.lisama569.devicegatewayjava.protocol;

import java.nio.charset.StandardCharsets;

/** ERROR 帧：payload = code(4,BE) + refSeq(4,BE) + UTF-8(reason)。绝不携带凭证原文。 */
public final class ErrorFrames {
    private ErrorFrames() {}

    public static byte[] encode(com.github.lisama569.devicegatewayjava.error.GatewayErrorCode code,
                                long refSeq) {
        return encode(code, code.description(), refSeq);
    }

    public static byte[] encode(com.github.lisama569.devicegatewayjava.error.GatewayErrorCode code,
                                String detail, long refSeq) {
        byte[] reason = detail.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[8 + reason.length];
        int c = code.code();
        payload[0] = (byte) (c >>> 24);
        payload[1] = (byte) (c >>> 16);
        payload[2] = (byte) (c >>> 8);
        payload[3] = (byte) c;
        payload[4] = (byte) (refSeq >>> 24);
        payload[5] = (byte) (refSeq >>> 16);
        payload[6] = (byte) (refSeq >>> 8);
        payload[7] = (byte) refSeq;
        System.arraycopy(reason, 0, payload, 8, reason.length);
        return FrameCodec.encode(MessageType.ERROR, (byte) 0, refSeq, payload);
    }

    public record Parsed(int code, long refSeq, String reason) {}

    public static Parsed parsePayload(byte[] payload) {
        int code = ((payload[0] & 0xFF) << 24) | ((payload[1] & 0xFF) << 16)
                | ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
        long refSeq = ((long) (payload[4] & 0xFF) << 24) | ((payload[5] & 0xFF) << 16)
                | ((payload[6] & 0xFF) << 8) | (payload[7] & 0xFF);
        String reason = new String(payload, 8, payload.length - 8, StandardCharsets.UTF_8);
        return new Parsed(code, refSeq, reason);
    }
}
