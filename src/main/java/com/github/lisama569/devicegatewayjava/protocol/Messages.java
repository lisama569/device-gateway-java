package com.github.lisama569.devicegatewayjava.protocol;

import java.nio.charset.StandardCharsets;

/**
 * Payload codecs for typed message bodies. All integer fields are big-endian.
 * Strings are length-prefixed UTF-8. Parsers never throw on bad input: they
 * throw {@link PayloadException} carrying {@link RejectReason#MALFORMED_PAYLOAD},
 * which the connection layer maps to a typed error frame.
 */
public final class Messages {

    private Messages() {
    }

    public static final class PayloadException extends RuntimeException {
        public PayloadException(String message) {
            super(message);
        }
    }

    // ---- typed payload holders ----

    public record Hello(int minVersion, int maxVersion) {
    }

    public record HelloAck(int negotiatedVersion, long sessionId) {
    }

    public record Login(String deviceId, String token, long timestamp) {
    }

    public record LoginAck(boolean accepted, int code, String reason) {
    }

    public record UplinkData(String key, long value) {
    }

    public record Ack(long ackedSeq, boolean accepted, int code, String reason) {
    }

    public record DownlinkData(String command, String arg) {
    }

    public record ErrorBody(int code, String category, String reason, long refSeq) {
    }

    // ---- Hello ----

    public static byte[] hello(int minV, int maxV) {
        byte[] out = new byte[8];
        Writer w = new Writer(out);
        w.u32(minV);
        w.u32(maxV);
        return out;
    }

    public static Hello parseHello(byte[] p) {
        Reader r = new Reader(p);
        int min = r.u32();
        int max = r.u32();
        r.end();
        return new Hello(min, max);
    }

    // ---- HelloAck ----

    public static byte[] helloAck(int v, long sessionId) {
        byte[] out = new byte[12];
        Writer w = new Writer(out);
        w.u32(v);
        w.u64(sessionId);
        return out;
    }

    public static HelloAck parseHelloAck(byte[] p) {
        Reader r = new Reader(p);
        int v = r.u32();
        long id = r.u64();
        r.end();
        return new HelloAck(v, id);
    }

    // ---- Login ----

    public static byte[] login(String deviceId, String token, long ts) {
        byte[] id = utf8(deviceId == null ? "" : deviceId);
        byte[] tok = utf8(token == null ? "" : token);
        byte[] out = new byte[4 + id.length + 4 + tok.length + 8];
        Writer w = new Writer(out);
        w.bytes(id);
        w.bytes(tok);
        w.u64(ts);
        return out;
    }

    public static Login parseLogin(byte[] p) {
        Reader r = new Reader(p);
        String id = r.str();
        String token = r.str();
        long ts = r.u64();
        r.end();
        return new Login(id, token, ts);
    }

    // ---- LoginAck ----

    public static byte[] loginAck(boolean accepted, int code, String reason) {
        byte[] why = utf8(reason == null ? "" : reason);
        byte[] out = new byte[1 + 4 + 4 + why.length];
        out[0] = (byte) (accepted ? 1 : 0);
        Writer w = new Writer(out, 1);
        w.u32(code);
        w.bytes(why);
        return out;
    }

    public static LoginAck parseLoginAck(byte[] p) {
        if (p == null || p.length < 5) {
            throw new PayloadException("login ack too short: " + len(p));
        }
        boolean accepted = p[0] == 1;
        Reader r = new Reader(p, 1);
        int code = r.u32();
        String reason = r.str();
        r.end();
        return new LoginAck(accepted, code, reason);
    }

    // ---- Uplink ----

    public static byte[] uplink(String key, long value) {
        byte[] k = utf8(key == null ? "" : key);
        byte[] out = new byte[4 + k.length + 8];
        Writer w = new Writer(out);
        w.bytes(k);
        w.u64(value);
        return out;
    }

    public static UplinkData parseUplink(byte[] p) {
        Reader r = new Reader(p);
        String key = r.str();
        long value = r.u64();
        r.end();
        return new UplinkData(key, value);
    }

    // ---- Ack (uplink ack or downlink ack; reason may be empty) ----

    public static byte[] ack(long ackedSeq, boolean accepted, int code, String reason) {
        byte[] why = utf8(reason == null ? "" : reason);
        byte[] out = new byte[8 + 1 + 4 + 4 + why.length];
        Writer w = new Writer(out);
        w.u64(ackedSeq);
        out[w.pos++] = (byte) (accepted ? 1 : 0);
        w.u32(code);
        w.bytes(why);
        return out;
    }

    public static Ack parseAck(byte[] p) {
        Reader r = new Reader(p);
        long seq = r.u64();
        boolean accepted = r.flag();
        int code = r.u32();
        String reason = r.str();
        r.end();
        return new Ack(seq, accepted, code, reason);
    }

    // ---- Downlink ----

    public static byte[] downlink(String command, String arg) {
        byte[] c = utf8(command == null ? "" : command);
        byte[] a = utf8(arg == null ? "" : arg);
        byte[] out = new byte[4 + c.length + 4 + a.length];
        Writer w = new Writer(out);
        w.bytes(c);
        w.bytes(a);
        return out;
    }

    public static DownlinkData parseDownlink(byte[] p) {
        Reader r = new Reader(p);
        String command = r.str();
        String arg = r.str();
        r.end();
        return new DownlinkData(command, arg);
    }

    // ---- Error ----

    public static byte[] error(int code, String category, String reason, long refSeq) {
        byte[] cat = utf8(category == null ? "" : category);
        byte[] why = utf8(reason == null ? "" : reason);
        byte[] out = new byte[4 + 4 + cat.length + 4 + why.length + 8];
        Writer w = new Writer(out);
        w.u32(code);
        w.bytes(cat);
        w.bytes(why);
        w.u64(refSeq);
        return out;
    }

    public static ErrorBody parseError(byte[] p) {
        Reader r = new Reader(p);
        int code = r.u32();
        String cat = r.str();
        String why = r.str();
        long ref = r.u64();
        r.end();
        return new ErrorBody(code, cat, why, ref);
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static int len(byte[] p) {
        return p == null ? 0 : p.length;
    }

    // ---- little internal writer/reader ----

    private static final class Writer {
        final byte[] b;
        int pos;

        Writer(byte[] b) {
            this(b, 0);
        }

        Writer(byte[] b, int start) {
            this.b = b;
            this.pos = start;
        }

        void u32(int v) {
            b[pos++] = (byte) (v >>> 24);
            b[pos++] = (byte) (v >>> 16);
            b[pos++] = (byte) (v >>> 8);
            b[pos++] = (byte) v;
        }

        void u64(long v) {
            b[pos++] = (byte) (v >>> 56);
            b[pos++] = (byte) (v >>> 48);
            b[pos++] = (byte) (v >>> 40);
            b[pos++] = (byte) (v >>> 32);
            b[pos++] = (byte) (v >>> 24);
            b[pos++] = (byte) (v >>> 16);
            b[pos++] = (byte) (v >>> 8);
            b[pos++] = (byte) v;
        }

        void bytes(byte[] src) {
            u32(src.length);
            System.arraycopy(src, 0, b, pos, src.length);
            pos += src.length;
        }
    }

    private static final class Reader {
        final byte[] b;
        int pos;

        Reader(byte[] b) {
            this(b, 0);
        }

        Reader(byte[] b, int start) {
            if (b == null) {
                throw new PayloadException("payload is null");
            }
            this.b = b;
            this.pos = start;
        }

        int u32() {
            need(4);
            int v = ((b[pos] & 0xFF) << 24) | ((b[pos + 1] & 0xFF) << 16)
                    | ((b[pos + 2] & 0xFF) << 8) | (b[pos + 3] & 0xFF);
            pos += 4;
            return v;
        }

        long u64() {
            need(8);
            long v = 0;
            for (int i = 0; i < 8; i++) {
                v = (v << 8) | (b[pos++] & 0xFF);
            }
            return v;
        }

        boolean flag() {
            need(1);
            return b[pos++] == 1;
        }

        String str() {
            int n = u32();
            if (n < 0) {
                throw new PayloadException("negative string length");
            }
            need(n);
            String s = new String(b, pos, n, StandardCharsets.UTF_8);
            pos += n;
            return s;
        }

        void end() {
            if (pos != b.length) {
                throw new PayloadException("trailing " + (b.length - pos) + " byte(s) in payload");
            }
        }

        private void need(int n) {
            if (pos + n > b.length) {
                throw new PayloadException("payload truncated at offset " + pos
                        + ", need " + n + " byte(s), have " + (b.length - pos));
            }
        }
    }
}
