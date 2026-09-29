package com.github.lisama569.devicegatewayjava.negotiation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * HELLO 上行负载：minVersion(1) | maxVersion(1) | capCount(1) | [capLen(1) capBytes]*
 * HELLO_ACK 下行负载：selectedVersion(1) | capCount(1) | [capLen(1) capBytes]*
 */
public final class HelloPayload {
    private HelloPayload() {}

    public record Hello(int minVersion, int maxVersion, List<String> capabilities) {}

    /** 解析设备 HELLO；格式不合法返回 null（调用方映射为 MALFORMED_PAYLOAD）。 */
    public static Hello parseHello(byte[] payload) {
        if (payload == null || payload.length < 3) return null;
        int min = payload[0] & 0xFF;
        int max = payload[1] & 0xFF;
        int count = payload[2] & 0xFF;
        if (min == 0 || min > max) return null;
        List<String> caps = new ArrayList<>();
        int off = 3;
        for (int i = 0; i < count; i++) {
            if (off >= payload.length) return null;
            int len = payload[off] & 0xFF;
            off++;
            if (len == 0 || off + len > payload.length) return null;
            caps.add(new String(payload, off, len, StandardCharsets.UTF_8));
            off += len;
        }
        if (off != payload.length) return null; // 尾部多余字节视为格式错误
        return new Hello(min, max, List.copyOf(caps));
    }

    public static byte[] encodeHello(int minVersion, int maxVersion, List<String> capabilities) {
        return encodeHelloRange(minVersion, maxVersion, capabilities);
    }

    public static byte[] encodeHelloAck(int selectedVersion, List<String> granted) {
        return encode(selectedVersion, granted);
    }

    /** 解析 HELLO_ACK（测试/客户端侧用）。 */
    public record HelloAck(int selectedVersion, List<String> grantedCapabilities) {}

    public static HelloAck parseHelloAck(byte[] payload) {
        if (payload == null || payload.length < 2) return null;
        int version = payload[0] & 0xFF;
        int count = payload[1] & 0xFF;
        List<String> caps = new ArrayList<>();
        int off = 2;
        for (int i = 0; i < count; i++) {
            if (off >= payload.length) return null;
            int len = payload[off] & 0xFF;
            off++;
            if (off + len > payload.length) return null;
            caps.add(new String(payload, off, len, StandardCharsets.UTF_8));
            off += len;
        }
        return new HelloAck(version, List.copyOf(caps));
    }

    private static byte[] encode(int version, List<String> capabilities) {
        int total = 2;
        List<byte[]> encoded = new ArrayList<>();
        for (String cap : capabilities) {
            byte[] b = cap.getBytes(StandardCharsets.UTF_8);
            if (b.length > 255) throw new IllegalArgumentException("capability name too long: " + cap);
            encoded.add(b);
            total += 1 + b.length;
        }
        byte[] out = new byte[total];
        out[0] = (byte) version;
        out[1] = (byte) encoded.size();
        int off = 2;
        for (byte[] b : encoded) {
            out[off++] = (byte) b.length;
            System.arraycopy(b, 0, out, off, b.length);
            off += b.length;
        }
        return out;
    }

    /** HELLO 上行编码（含版本区间）。 */
    public static byte[] encodeHelloRange(int minVersion, int maxVersion, List<String> capabilities) {
        byte[] rest = encode(maxVersion, capabilities);
        byte[] out = new byte[1 + rest.length];
        out[0] = (byte) minVersion;
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }
}
