package com.github.lisama569.devicegatewayjava.tests;

import com.github.lisama569.devicegatewayjava.error.GatewayErrorCode;
import com.github.lisama569.devicegatewayjava.protocol.Frame;
import com.github.lisama569.devicegatewayjava.protocol.FrameCodec;
import com.github.lisama569.devicegatewayjava.protocol.FrameDecoder;
import com.github.lisama569.devicegatewayjava.protocol.MessageType;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 解码器：粘包/半包/跨包分片/超长声明/CRC 失败/字节垃圾后的重同步。 */
class FrameDecoderTest {

    private static final Logger log = LoggerFactory.getLogger(FrameDecoderTest.class);

    private Frame f(MessageType type, long seq, byte[] payload) {
        return new Frame((byte) 1, type, (byte) 0, seq, payload);
    }

    @Test
    void stickyPacketsTwoFramesInOneChunk() {
        byte[] a = FrameCodec.encode(f(MessageType.LOGIN, 1, "AA".getBytes()));
        byte[] b = FrameCodec.encode(f(MessageType.DATA, 2, "BBBB".getBytes()));
        byte[] both = new byte[a.length + b.length];
        System.arraycopy(a, 0, both, 0, a.length);
        System.arraycopy(b, 0, both, a.length, b.length);

        FrameDecoder d = new FrameDecoder(1024);
        List<FrameDecoder.Result> r = d.feed(both, 0, both.length);
        log.info("[粘包] 输入 {} 字节(两帧粘连) -> 结论数={}", both.length, r.size());

        assertEquals(2, r.size());
        Frame fa = ((FrameDecoder.Result.FrameOk) r.get(0)).frame();
        Frame fb = ((FrameDecoder.Result.FrameOk) r.get(1)).frame();
        assertEquals(1, fa.seq());
        assertEquals(2, fb.seq());
        assertEquals("BBBB", new String(fb.payload()));
    }

    @Test
    void halfPacketAcrossChunksAndByteByByte() {
        byte[] a = FrameCodec.encode(f(MessageType.DATA, 7, "HELLO".getBytes()));
        FrameDecoder d = new FrameDecoder(1024);

        // 先只给 3 字节
        List<FrameDecoder.Result> r1 = d.feed(a, 0, 3);
        log.info("[半包] 前 3 字节 -> {}", r1.get(0).getClass().getSimpleName());
        assertInstanceOf(FrameDecoder.Result.NeedMoreData.class, r1.get(0));

        // 再补齐
        List<FrameDecoder.Result> r2 = d.feed(a, 3, a.length - 3);
        log.info("[半包] 补齐剩余 {} 字节 -> {}", a.length - 3,
                r2.get(0).getClass().getSimpleName());
        Frame frame = ((FrameDecoder.Result.FrameOk) r2.get(0)).frame();
        assertEquals(7, frame.seq());
        assertEquals("HELLO", new String(frame.payload()));

        // 极端：逐字节喂入
        FrameDecoder d2 = new FrameDecoder(1024);
        FrameDecoder.Result last = null;
        for (int i = 0; i < a.length; i++) {
            List<FrameDecoder.Result> rr = d2.feed(a, i, 1);
            last = rr.get(rr.size() - 1);
        }
        log.info("[跨包分片] 逐字节喂入共 {} 次 -> {}", a.length, last.getClass().getSimpleName());
        assertInstanceOf(FrameDecoder.Result.FrameOk.class, last);
    }

    @Test
    void crcFailureResyncsAndNextValidFrameStillParses() {
        byte[] a = FrameCodec.encode(f(MessageType.DATA, 9, "BAD".getBytes()));
        a[a.length - 1] ^= (byte) 0xFF; // 破坏 CRC
        byte[] b = FrameCodec.encode(f(MessageType.DATA, 10, "GOOD".getBytes()));
        byte[] stream = new byte[a.length + b.length];
        System.arraycopy(a, 0, stream, 0, a.length);
        System.arraycopy(b, 0, stream, a.length, b.length);

        FrameDecoder d = new FrameDecoder(1024);
        List<FrameDecoder.Result> r = d.feed(stream, 0, stream.length);
        log.info("[CRC失败] 输入坏帧+好帧 -> 结论: {}",
                r.stream().map(x -> x.getClass().getSimpleName()
                        + (x instanceof FrameDecoder.Result.FrameError e ? "(" + e.code().code() + ")" : ""))
                        .toList());

        boolean crcErr = r.stream().anyMatch(x -> x instanceof FrameDecoder.Result.FrameError e
                && e.code() == GatewayErrorCode.CRC_MISMATCH);
        assertTrue(crcErr, "must report CRC_MISMATCH");
        Frame good = (Frame) r.stream()
                .filter(x -> x instanceof FrameDecoder.Result.FrameOk)
                .map(x -> ((FrameDecoder.Result.FrameOk) x).frame())
                .findFirst().orElseThrow();
        assertEquals(10, good.seq());
        assertEquals("GOOD", new String(good.payload()));
    }

    @Test
    void overlongDeclaredLengthRejectedAndResyncs() {
        // 手工构造：MAGIC + v1 + type + flags + rsv + seq + 声明长度 1_000_000
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.writeBytes(new byte[]{(byte) 0xA5, 0x5A, 1, 0x10, 0, 0, 0, 0, 0, 11});
        bos.writeBytes(new byte[]{0, 0x0F, 0x42, 0x40}); // len=1_000_000
        byte[] junk = bos.toByteArray();
        byte[] good = FrameCodec.encode(f(MessageType.DATA, 12, "X".getBytes()));
        byte[] stream = new byte[junk.length + good.length];
        System.arraycopy(junk, 0, stream, 0, junk.length);
        System.arraycopy(good, 0, stream, junk.length, good.length);

        FrameDecoder d = new FrameDecoder(64);
        List<FrameDecoder.Result> r = d.feed(stream, 0, stream.length);
        log.info("[超长声明] declared=1000000 limit=64 -> 结论: {}",
                r.stream().map(x -> x instanceof FrameDecoder.Result.FrameError e
                        ? "ERR(" + e.code().code() + ")" : x.getClass().getSimpleName()).toList());

        assertTrue(r.stream().anyMatch(x -> x instanceof FrameDecoder.Result.FrameError e
                && e.code() == GatewayErrorCode.FRAME_TOO_LONG));
        assertTrue(r.stream().anyMatch(x -> x instanceof FrameDecoder.Result.FrameOk ok
                && ok.frame().seq() == 12), "valid frame after overlong header must parse");
    }

    @Test
    void garbageBytesThenFrameReportedMagicMismatchThenRecovers() {
        byte[] garbage = new byte[]{0x00, 0x11, 0x22, 0x33};
        byte[] good = FrameCodec.encode(f(MessageType.LOGIN, 1, new byte[0]));
        byte[] stream = new byte[garbage.length + good.length];
        System.arraycopy(garbage, 0, stream, 0, garbage.length);
        System.arraycopy(good, 0, stream, garbage.length, good.length);

        FrameDecoder d = new FrameDecoder(1024);
        List<FrameDecoder.Result> r = d.feed(stream, 0, stream.length);
        log.info("[垃圾字节] 4 字节噪声+合法帧 -> {}",
                r.stream().map(x -> x instanceof FrameDecoder.Result.FrameError e
                        ? "ERR(" + e.code().code() + ",discarded=" + e.discardedBytes() + ")"
                        : x.getClass().getSimpleName()).toList());

        assertTrue(r.stream().anyMatch(x -> x instanceof FrameDecoder.Result.FrameError e
                && e.code() == GatewayErrorCode.MAGIC_MISMATCH));
        assertTrue(r.stream().anyMatch(x -> x instanceof FrameDecoder.Result.FrameOk));
    }

    @Test
    void versionMismatchAndUnknownTypeAreDistinguishable() {
        byte[] badVer = FrameCodec.encode(new Frame((byte) 9, MessageType.DATA, (byte) 0, 1, new byte[0]));
        byte[] badType = FrameCodec.encode(new Frame((byte) 1, MessageType.DATA, (byte) 0, 2, new byte[0]));
        badType[3] = 0x66;
        // 重算坏类型帧的 CRC，使其到达“类型未知”判定而非 CRC 判定
        int crcOff = badType.length - 2;
        int crc = com.github.lisama569.devicegatewayjava.protocol.Crc16.checksum(badType, 0, crcOff);
        badType[crcOff] = (byte) (crc >>> 8);
        badType[crcOff + 1] = (byte) crc;

        FrameDecoder d = new FrameDecoder(1024);
        List<FrameDecoder.Result> r1 = d.feed(badVer, 0, badVer.length);
        List<FrameDecoder.Result> r2 = d.feed(badType, 0, badType.length);
        log.info("[版本不匹配] -> {} / [未知类型] -> {}",
                ((FrameDecoder.Result.FrameError) r1.get(0)).code(),
                ((FrameDecoder.Result.FrameError) r2.get(0)).code());
        assertEquals(GatewayErrorCode.VERSION_MISMATCH,
                ((FrameDecoder.Result.FrameError) r1.get(0)).code());
        assertEquals(GatewayErrorCode.UNKNOWN_MESSAGE_TYPE,
                ((FrameDecoder.Result.FrameError) r2.get(0)).code());
    }
}
