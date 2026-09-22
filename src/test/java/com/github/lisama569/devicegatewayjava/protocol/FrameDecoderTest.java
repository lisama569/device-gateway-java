package com.github.lisama569.devicegatewayjava.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class FrameDecoderTest {

    private static final Logger log = LoggerFactory.getLogger(FrameDecoderTest.class);

    private Frame frame(byte type, long seq, byte[] payload) {
        return new Frame(type, (byte) 0, seq, payload);
    }

    private List<DecodeResult> feedAll(FrameDecoder decoder, byte[] data, int chunkSize) {
        List<DecodeResult> events = new ArrayList<>();
        for (int off = 0; off < data.length; off += chunkSize) {
            int n = Math.min(chunkSize, data.length - off);
            assertTrue(decoder.append(data, off, n),
                    "append must succeed within buffer bound");
            log.info("feed chunk size={}: {}", n, hex(data, off, n));
            DecodeResult r;
            while (!((r = decoder.decode()) instanceof DecodeResult.NeedMoreData)) {
                events.add(r);
            }
        }
        return events;
    }

    private List<DecodeResult.FrameOk> oks(List<DecodeResult> events) {
        return events.stream()
                .filter(e -> e instanceof DecodeResult.FrameOk)
                .map(e -> (DecodeResult.FrameOk) e)
                .toList();
    }

    private List<DecodeResult.FrameRejected> rejected(List<DecodeResult> events) {
        return events.stream()
                .filter(e -> e instanceof DecodeResult.FrameRejected)
                .map(e -> (DecodeResult.FrameRejected) e)
                .toList();
    }

    @Test
    void stickyAndHalfAndCrossPacketSlicesAllParse() {
        byte[] a = FrameEncoder.encode(frame(Frame.T_HELLO, 1, Messages.hello(1, 1)));
        byte[] b = FrameEncoder.encode(frame(Frame.T_LOGIN, 2,
                Messages.login("dev", "tok", 123L)));
        byte[] c = FrameEncoder.encode(frame(Frame.T_UPLINK, 3, Messages.uplink("k", 7L)));
        byte[] all = concat(a, b, c);

        for (int chunk : new int[]{1, 2, 3, 7, 15, 16, 100}) {
            FrameDecoder decoder = new FrameDecoder(4096, 1 << 20);
            List<DecodeResult> events = feedAll(decoder, all, chunk);
            List<DecodeResult.FrameOk> got = oks(events);
            log.info("chunkSize={} -> decoded {} frame(s), {} rejection(s)",
                    chunk, got.size(), rejected(events).size());
            assertEquals(3, got.size(), "chunk=" + chunk);
            assertEquals(0, rejected(events).size(), "chunk=" + chunk);
            assertEquals(Frame.T_HELLO, got.get(0).frame().type());
            assertEquals(2, got.get(1).frame().seq());
            assertEquals("k", Messages.parseUplink(got.get(2).frame().payload()).key());
        }
    }

    @Test
    void garbagePrefixIsRejectedBadMagicThenStreamResyncs() {
        byte[] garbage = new byte[]{0x00, 0x11, 0x22};
        byte[] good = FrameEncoder.encode(frame(Frame.T_HEARTBEAT, 5, new byte[0]));
        FrameDecoder decoder = new FrameDecoder(4096, 1 << 20);
        List<DecodeResult> events = feedAll(decoder, concat(garbage, good), 4);
        List<DecodeResult.FrameRejected> rejects = rejected(events);
        assertFalse(rejects.isEmpty(), "garbage must be rejected");
        assertEquals(RejectReason.BAD_MAGIC, rejects.get(0).reason());
        List<DecodeResult.FrameOk> got = oks(events);
        assertEquals(1, got.size(), "valid frame after garbage must still parse");
        assertEquals(5, got.get(0).frame().seq());
        log.info("judgement: garbage rejected BAD_MAGIC consumed={}, following frame seq={} parsed OK",
                rejects.get(0).consumedBytes(), got.get(0).frame().seq());
    }

    @Test
    void corruptedBodyCrcRejectedThenNextFrameParses() {
        byte[] a = FrameEncoder.encode(frame(Frame.T_UPLINK, 1, Messages.uplink("k1", 1L)));
        byte[] corrupted = a.clone();
        corrupted[Frame.HEADER_LEN] ^= 0xFF; // flip payload byte -> body crc mismatch
        byte[] b = FrameEncoder.encode(frame(Frame.T_UPLINK, 2, Messages.uplink("k2", 2L)));
        FrameDecoder decoder = new FrameDecoder(4096, 1 << 20);
        List<DecodeResult> events = feedAll(decoder, concat(corrupted, b), 32);
        List<DecodeResult.FrameRejected> rejects = rejected(events);
        assertEquals(1, rejects.size());
        assertEquals(RejectReason.BODY_CRC_MISMATCH, rejects.get(0).reason());
        List<DecodeResult.FrameOk> got = oks(events);
        assertEquals(1, got.size(), "stream must realign after bad body crc");
        assertEquals(2, got.get(0).frame().seq());
        log.info("judgement: corrupted frame seq=1 BODY_CRC_MISMATCH consumed={}, frame seq=2 still parsed",
                rejects.get(0).consumedBytes());
    }

    @Test
    void corruptedHeaderCrcRejectedAndResyncsAtNextMagic() {
        byte[] a = FrameEncoder.encode(frame(Frame.T_UPLINK, 1, Messages.uplink("k1", 1L)));
        byte[] corrupted = a.clone();
        corrupted[3] ^= 0x01; // break type byte -> header crc mismatch
        byte[] b = FrameEncoder.encode(frame(Frame.T_UPLINK, 2, Messages.uplink("k2", 2L)));
        FrameDecoder decoder = new FrameDecoder(4096, 1 << 20);
        List<DecodeResult> events = feedAll(decoder, concat(corrupted, b), 32);
        RejectReason reason = rejected(events).get(0).reason();
        assertTrue(reason == RejectReason.HEADER_CRC_MISMATCH
                        || reason == RejectReason.BAD_MAGIC,
                "expected header resync event, got " + reason);
        assertEquals(1, oks(events).size());
        assertEquals(2, oks(events).get(0).frame().seq());
        log.info("judgement: corrupted header rejected {} and stream resynced to seq=2", reason);
    }

    @Test
    void overlongDeclaredFrameIsRejectedAsTooLongAndStreamKeepsAligned() {
        // craft a valid-looking frame whose declared length exceeds configured 4096,
        // with a correct body crc so it is classified as FRAME_TOO_LONG.
        int n = 5000;
        byte[] payload = new byte[n];
        for (int i = 0; i < n; i++) {
            payload[i] = (byte) i;
        }
        byte[] over = FrameEncoder.encode(frame(Frame.T_UPLINK, 9, payload));
        byte[] good = FrameEncoder.encode(frame(Frame.T_HEARTBEAT, 10, new byte[0]));
        FrameDecoder decoder = new FrameDecoder(4096, 1 << 20);
        List<DecodeResult> events = feedAll(decoder, concat(over, good), 8192);
        List<DecodeResult.FrameRejected> rejects = rejected(events);
        assertEquals(1, rejects.size());
        assertEquals(RejectReason.FRAME_TOO_LONG, rejects.get(0).reason());
        assertEquals(1, oks(events).size(), "declared-length skip must keep alignment");
        assertEquals(10, oks(events).get(0).frame().seq());
        log.info("judgement: declared payload {} > 4096 -> FRAME_TOO_LONG consumed={}, next frame parsed",
                n, rejects.get(0).consumedBytes());
    }

    @Test
    void impossibleLengthIsFatal() {
        byte[] f = FrameEncoder.encode(frame(Frame.T_UPLINK, 1, new byte[0]));
        // tamper the declared length to exceed the hard ceiling, fix header crc
        long impossible = Frame.MAX_HARD_PAYLOAD + 1L;
        f[9] = (byte) (impossible >>> 24);
        f[10] = (byte) (impossible >>> 16);
        f[11] = (byte) (impossible >>> 8);
        f[12] = (byte) impossible;
        int crc = Crc16.crc(f, 0, 13);
        f[13] = (byte) (crc >>> 8);
        f[14] = (byte) crc;
        FrameDecoder decoder = new FrameDecoder(4096, 1 << 20);
        assertTrue(decoder.append(f, 0, f.length));
        DecodeResult r = decoder.decode();
        assertInstanceOf(DecodeResult.FrameRejected.class, r);
        assertEquals(RejectReason.FRAME_LENGTH_IMPOSSIBLE,
                ((DecodeResult.FrameRejected) r).reason());
        assertTrue(decoder.isFatal());
        log.info("judgement: declared length {} > hard ceiling {} -> FRAME_LENGTH_IMPOSSIBLE (fatal)",
                impossible, Frame.MAX_HARD_PAYLOAD);
    }

    @Test
    void bufferBoundRejectsFurtherAppend() {
        FrameDecoder decoder = new FrameDecoder(4096, 64);
        byte[] big = new byte[65];
        assertFalse(decoder.append(big, 0, 65));
        log.info("judgement: 65 bytes into 64-byte receive bound -> append rejected (RECEIVE_BUFFER)");
    }

    private static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] a : arrays) {
            total += a.length;
        }
        byte[] out = new byte[total];
        int off = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, out, off, a.length);
            off += a.length;
        }
        return out;
    }

    private static String hex(byte[] data, int off, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = off; i < off + len; i++) {
            sb.append(String.format("%02x ", data[i] & 0xFF));
        }
        return sb.toString().trim();
    }
}
