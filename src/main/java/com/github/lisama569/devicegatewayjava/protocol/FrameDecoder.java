package com.github.lisama569.devicegatewayjava.protocol;

import java.util.Arrays;

/**
 * Stateful streaming frame decoder.
 *
 * <p>Contract:
 * <ul>
 *   <li>Never throws on corrupt input; problems are reported as
 *       {@link DecodeResult.FrameRejected} with a distinguishable reason.</li>
 *   <li>After a rejected frame the stream is resynchronised: a bad magic / bad
 *       header CRC causes a one-byte rescan, while a structurally parseable but
 *       invalid frame (too long, bad body CRC) is skipped by its declared
 *       length so later valid frames on the same connection still parse.</li>
 *   <li>Memory is bounded by maxBufferedBytes; {@link #append} returns false
 *       once the bound would be exceeded (resource exhaustion, caller decides
 *       the policy). Declared lengths above {@link Frame#MAX_HARD_PAYLOAD} are
 *       fatal ({@link #isFatal()}): stream position cannot be recovered safely.</li>
 * </ul>
 */
public final class FrameDecoder {

    private final int maxPayload;
    private final int maxBufferedBytes;

    private byte[] buf;
    private int size;

    private boolean fatal;
    private boolean fatalReported;
    private RejectReason fatalReason;
    private long unparsedBytes;

    public FrameDecoder(int maxPayload, int maxBufferedBytes) {
        if (maxPayload <= 0 || maxPayload > Frame.MAX_HARD_PAYLOAD) {
            throw new IllegalArgumentException("invalid maxPayload: " + maxPayload);
        }
        if (maxBufferedBytes < Frame.HEADER_LEN + Frame.CRC_LEN) {
            throw new IllegalArgumentException("maxBufferedBytes too small");
        }
        this.maxPayload = maxPayload;
        this.maxBufferedBytes = maxBufferedBytes;
        this.buf = new byte[Math.min(256, maxBufferedBytes)];
    }

    public boolean isFatal() {
        return fatal;
    }

    /** Force fatal state externally (e.g. receive-budget exhaustion on this stream). */
    public void markFatal(RejectReason reason) {
        this.fatal = true;
        this.fatalReason = reason;
        this.fatalReported = false;
    }

    public RejectReason fatalReason() {
        return fatalReason;
    }

    /** Append inbound bytes. Returns false (and stores nothing) if the buffer bound is exceeded. */
    public boolean append(byte[] data, int offset, int length) {
        if (fatal) {
            return false;
        }
        if (length < 0) {
            throw new IllegalArgumentException("length < 0");
        }
        if (length == 0) {
            return true;
        }
        if (size + length > maxBufferedBytes) {
            return false;
        }
        ensureCapacity(size + length);
        System.arraycopy(data, offset, buf, size, length);
        size += length;
        return true;
    }

    public int bufferedBytes() {
        return size;
    }

    /**
     * Attempts to decode exactly one frame. Call repeatedly until it returns
     * {@link DecodeResult.NeedMoreData}.
     */
    public DecodeResult decode() {
        if (fatal) {
            // Fatal is terminal; report the reason exactly once, then make no
            // further stream progress (NeedMoreData) to avoid hot reject loops.
            if (fatalReported) {
                return new DecodeResult.NeedMoreData();
            }
            fatalReported = true;
            return new DecodeResult.FrameRejected(fatalReason, 0, "fatal framing error");
        }

        // 1. Locate magic, dropping everything before the first valid magic pair.
        int pos = findMagic();
        if (pos < 0) {
            // No magic pair in the buffer. Drop all but at most one trailing
            // byte (it may be the first half of a magic pair completed by the
            // next read). Only surface a rejection when bytes were actually
            // consumed; a single dangling byte means NeedMoreData to avoid a
            // hot reject loop with no stream progress.
            int remaining = size;
            if (remaining > 1) {
                unparsedBytes += remaining - 1;
                byte maybeFirst = buf[size - 1];
                System.arraycopy(buf, size - 1, buf, 0, 1);
                size = 1;
                return new DecodeResult.FrameRejected(RejectReason.BAD_MAGIC,
                        remaining - 1,
                        "no frame magic; discarded " + (remaining - 1) + " byte(s)"
                                + " (total unparsed " + unparsedBytes + ")");
            }
            return new DecodeResult.NeedMoreData();
        }
        if (pos > 0) {
            discardPrefix(pos);
            unparsedBytes = 0; // resynchronised onto a real frame start
            return new DecodeResult.FrameRejected(RejectReason.BAD_MAGIC, pos,
                    "skipped " + pos + " byte(s) before frame magic");
        }

        // 2. Need the full 15-byte header.
        if (size < Frame.HEADER_LEN) {
            return new DecodeResult.NeedMoreData();
        }

        // 3. Validate header CRC. A mismatch makes the header untrustworthy:
        //    resync by scanning for the next magic one byte later.
        int headerCrc = readUInt16(buf, 13);
        if (Crc16.crc(buf, 0, 13) != headerCrc) {
            int nextMagic = findMagicFrom(1);
            int consumed = nextMagic > 0 ? nextMagic : 1;
            discardPrefix(consumed);
            return new DecodeResult.FrameRejected(RejectReason.HEADER_CRC_MISMATCH, consumed,
                    "header crc=0x" + Integer.toHexString(headerCrc)
                            + " does not match computed crc");
        }

        long declared = readUInt32(buf, 9) & 0xFFFFFFFFL;

        // 4. Structural impossibility / unbounded declaration => fatal.
        if (declared > Frame.MAX_HARD_PAYLOAD) {
            fatal = true;
            fatalReported = true;
            fatalReason = RejectReason.FRAME_LENGTH_IMPOSSIBLE;
            return new DecodeResult.FrameRejected(RejectReason.FRAME_LENGTH_IMPOSSIBLE, 0,
                    "declared payload length " + declared + " exceeds hard ceiling "
                            + Frame.MAX_HARD_PAYLOAD);
        }
        int totalNeeded = Frame.HEADER_LEN + (int) declared + Frame.CRC_LEN;
        if (size < totalNeeded) {
            return new DecodeResult.NeedMoreData();
        }

        // 5. Configured per-frame limit: reject but keep stream aligned by
        //    skipping exactly the declared frame.
        if (declared > maxPayload) {
            discardPrefix(totalNeeded);
            return new DecodeResult.FrameRejected(RejectReason.FRAME_TOO_LONG, totalNeeded,
                    "declared payload length " + declared + " exceeds configured limit "
                            + maxPayload);
        }

        // 6. Validate body CRC.
        int bodyCrc = readUInt16(buf, Frame.HEADER_LEN + (int) declared);
        if (Crc16.crc(buf, Frame.HEADER_LEN, (int) declared) != bodyCrc) {
            discardPrefix(totalNeeded);
            return new DecodeResult.FrameRejected(RejectReason.BODY_CRC_MISMATCH, totalNeeded,
                    "body crc mismatch for declared length " + declared);
        }

        // 7. Valid frame.
        int type = buf[3] & 0xFF;
        int flags = buf[4] & 0xFF;
        long seq = readUInt32(buf, 5) & 0xFFFFFFFFL;
        byte[] payload = Arrays.copyOfRange(buf, Frame.HEADER_LEN, Frame.HEADER_LEN + (int) declared);
        discardPrefix(totalNeeded);
        unparsedBytes = 0;
        return new DecodeResult.FrameOk(new Frame((byte) type, (byte) flags, seq, payload));
    }

    private int findMagic() {
        return findMagicFrom(0);
    }

    private int findMagicFrom(int from) {
        for (int i = from; i < size - 1; i++) {
            if ((buf[i] & 0xFF) == Frame.MAGIC0 && (buf[i + 1] & 0xFF) == Frame.MAGIC1) {
                return i;
            }
        }
        return -1;
    }

    private void discardPrefix(int n) {
        int remain = size - n;
        if (remain > 0) {
            System.arraycopy(buf, n, buf, 0, remain);
        }
        size = remain;
    }

    private void ensureCapacity(int needed) {
        if (needed <= buf.length) {
            return;
        }
        int newCap = Math.max(buf.length, 1);
        while (newCap < needed) {
            newCap <<= 1;
        }
        newCap = Math.min(newCap, maxBufferedBytes);
        buf = Arrays.copyOf(buf, newCap);
    }

    static int readUInt16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    static long readUInt32(byte[] b, int off) {
        return ((long) (b[off] & 0xFF) << 24)
                | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8)
                | (b[off + 3] & 0xFF);
    }
}
