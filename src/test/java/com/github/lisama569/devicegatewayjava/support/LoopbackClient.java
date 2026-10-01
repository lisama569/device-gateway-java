package com.github.lisama569.devicegatewayjava.support;

import com.github.lisama569.devicegatewayjava.negotiation.HelloPayload;
import com.github.lisama569.devicegatewayjava.protocol.Frame;
import com.github.lisama569.devicegatewayjava.protocol.FrameCodec;
import com.github.lisama569.devicegatewayjava.protocol.MessageType;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

/** 确定性回环 TCP 客户端：支持任意分片、任意字节写入、按帧读取。 */
public final class LoopbackClient implements AutoCloseable {

    private final Socket socket;
    private final OutputStream out;
    private final InputStream in;

    public LoopbackClient(int port) {
        try {
            this.socket = new Socket();
            this.socket.connect(new InetSocketAddress("127.0.0.1", port), 2000);
            this.socket.setTcpNoDelay(true);
            this.socket.setSoTimeout(3000);
            this.out = socket.getOutputStream();
            this.in = socket.getInputStream();
        } catch (IOException e) {
            throw new IllegalStateException("connect 127.0.0.1:" + port + " failed", e);
        }
    }

    public Socket socket() { return socket; }

    public void sendRaw(byte[] data) {
        sendRaw(data, 0, data.length);
    }

    public void sendRaw(byte[] data, int off, int len) {
        try {
            out.write(data, off, len);
            out.flush();
        } catch (IOException e) {
            throw new IllegalStateException("write failed", e);
        }
    }

    /** 逐字节发送，模拟极端半包。 */
    public void sendByteByByte(byte[] data) {
        for (byte b : data) {
            sendRaw(new byte[]{b});
            try { Thread.sleep(1); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    /** 按 cuts 给定的分片边界依次发送。 */
    public void sendInPieces(byte[] data, int... cuts) {
        int prev = 0;
        for (int c : cuts) {
            sendRaw(data, prev, c - prev);
            prev = c;
        }
        if (prev < data.length) sendRaw(data, prev, data.length - prev);
    }

    /** 随机分片发送（种子固定，结果可复现）。 */
    public void sendRandomChunks(byte[] data, long seed) {
        Random rnd = new Random(seed);
        int pos = 0;
        while (pos < data.length) {
            int n = 1 + rnd.nextInt(Math.min(7, data.length - pos));
            sendRaw(data, pos, n);
            pos += n;
        }
    }

    /** 从输入流精确读取 n 字节，EOF 抛异常。 */
    public byte[] readN(int n) {
        byte[] buf = new byte[n];
        int total = 0;
        while (total < n) {
            int r;
            try {
                r = in.read(buf, total, n - total);
            } catch (IOException e) {
                throw new IllegalStateException("read failed at " + total + "/" + n, e);
            }
            if (r < 0) throw new IllegalStateException("EOF while expecting " + n + " bytes, got " + total);
            total += r;
        }
        return buf;
    }

    public void shutdownOutput() {
        try { socket.shutdownOutput(); } catch (IOException ignored) { }
    }

    @Override
    public void close() {
        try { socket.close(); } catch (IOException ignored) { }
    }

    // ---- 高层便捷方法 ----

    public static byte[] loginPayload(String deviceId, String secret) {
        byte[] id = deviceId.getBytes(StandardCharsets.UTF_8);
        byte[] sec = secret.getBytes(StandardCharsets.UTF_8);
        byte[] p = new byte[4 + id.length + sec.length];
        p[0] = (byte) (id.length >>> 8);
        p[1] = (byte) id.length;
        System.arraycopy(id, 0, p, 2, id.length);
        int o = 2 + id.length;
        p[o] = (byte) (sec.length >>> 8);
        p[o + 1] = (byte) sec.length;
        System.arraycopy(sec, 0, p, o + 2, sec.length);
        return p;
    }

    public static byte[] frame(MessageType type, int flags, long seq, byte[] payload) {
        return FrameCodec.encode(new Frame((byte) 1, type, (byte) flags, seq, payload));
    }

    /** 构造 HELLO 帧（指定版本区间与能力集）。 */
    public static byte[] helloFrame(int minVersion, int maxVersion, List<String> caps, long seq) {
        return FrameCodec.encode(MessageType.HELLO, (byte) 0, seq,
                HelloPayload.encodeHello(minVersion, maxVersion, caps));
    }

    /** 标准协商帧：版本区间 1..1、无能力项。 */
    public static byte[] helloFrame(long seq) {
        return helloFrame(1, 1, List.of(), seq);
    }

    /**
     * 发送标准 HELLO 并读取 HELLO_ACK（协商是 LOGIN 前的必经步骤）。
     * 若收到的不是 HELLO_ACK（例如被拒绝），断言失败信息会带上实际帧类型。
     */
    public void negotiate(long seq) {
        sendRaw(helloFrame(seq));
        int type = FrameAsserts.readFrameType(this);
        if (type != (MessageType.HELLO_ACK.code() & 0xFF)) {
            throw new IllegalStateException("expected HELLO_ACK after HELLO, got frame type 0x"
                    + Integer.toHexString(type));
        }
    }

    public void login(String deviceId, String secret, long seq) {
        sendRaw(frame(MessageType.LOGIN, 0, seq, loginPayload(deviceId, secret)));
    }
}
