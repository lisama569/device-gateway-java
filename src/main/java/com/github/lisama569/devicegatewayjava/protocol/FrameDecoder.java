package com.github.lisama569.devicegatewayjava.protocol;

import com.github.lisama569.devicegatewayjava.error.GatewayErrorCode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 字节流帧解码器（有界缓冲）。
 *
 * 不变量：
 * - 缓冲只保留“疑似帧头”起的数据；任何不在 MAGIC 上的字节都会被统计后丢弃并重同步；
 * - 声明长度超过 maxPayloadLength 立即报 FRAME_TOO_LONG 且不信任该长度（只跳过 MAGIC 两字节后重新扫描）；
 * - CRC 不通过同样不信任边界，按 MAGIC 重同步；因此粘包/半包/跨包分片/非法帧都不会错位或越界；
 * - 非法帧只产生 FrameError，后续合法帧照常解析。
 */
public final class FrameDecoder {

    /** 解码结论。 */
    public sealed interface Result {
        record FrameOk(Frame frame) implements Result {}
        record FrameError(GatewayErrorCode code, String reason, long discardedBytes) implements Result {}
        record NeedMoreData() implements Result {}
    }

    private final int maxPayloadLength;
    private byte[] buf = new byte[256];
    private int size;

    public FrameDecoder(int maxPayloadLength) {
        this.maxPayloadLength = maxPayloadLength;
    }

    /** 喂入一段字节，返回本次可确定的全部结论（按流顺序）；无完整帧时末尾给 NeedMoreData。 */
    public List<Result> feed(byte[] chunk, int offset, int length) {
        append(chunk, offset, length);
        List<Result> results = new ArrayList<>();
        while (true) {
            if (size < 2) break;

            if (!isMagicAt(0)) {
                int next = findMagic(1);
                long discarded = (next < 0) ? size - 1L : next;
                // 找不到 MAGIC 时保留最后 1 字节（可能是 MAGIC 高字节）
                int keepFrom = (next < 0) ? size - 1 : next;
                discardPrefix(keepFrom);
                results.add(new Result.FrameError(
                        GatewayErrorCode.MAGIC_MISMATCH,
                        "bytes outside frame boundary skipped before resync",
                        discarded));
                continue;
            }

            if (size < FrameConstants.HEADER_LEN) break; // 半包：等更多字节

            long declared = unsignedIntAt(10);
            if (declared > maxPayloadLength) {
                // 超长声明：长度不可信，只跳过 MAGIC 两字节再找下一帧
                discardPrefix(2);
                results.add(new Result.FrameError(
                        GatewayErrorCode.FRAME_TOO_LONG,
                        "declared payload length " + declared + " exceeds limit " + maxPayloadLength,
                        2));
                continue;
            }

            int frameLen = FrameConstants.HEADER_LEN + (int) declared + FrameConstants.CRC_LEN;
            if (size < frameLen) break; // 跨包分片：等齐再校验

            int crcOffset = FrameConstants.HEADER_LEN + (int) declared;
            int calc = Crc16.checksum(buf, 0, crcOffset);
            int recv = ((buf[crcOffset] & 0xFF) << 8) | (buf[crcOffset + 1] & 0xFF);
            if (calc != recv) {
                discardPrefix(2);
                results.add(new Result.FrameError(
                        GatewayErrorCode.CRC_MISMATCH,
                        "crc calc=0x" + Integer.toHexString(calc) + " recv=0x" + Integer.toHexString(recv),
                        2));
                continue;
            }

            byte version = buf[2];
            byte typeCode = buf[3];
            byte flags = buf[4];
            long seq = unsignedIntAt(6);

            if (version != FrameConstants.PROTOCOL_VERSION) {
                results.add(new Result.FrameError(
                        GatewayErrorCode.VERSION_MISMATCH,
                        "version=" + (version & 0xFF) + " supported=" + FrameConstants.PROTOCOL_VERSION,
                        frameLen));
                discardPrefix(frameLen);
                continue;
            }
            var typeOpt = MessageType.fromCode(typeCode);
            if (typeOpt.isEmpty()) {
                results.add(new Result.FrameError(
                        GatewayErrorCode.UNKNOWN_MESSAGE_TYPE,
                        "type=0x" + Integer.toHexString(typeCode & 0xFF),
                        frameLen));
                discardPrefix(frameLen);
                continue;
            }

            byte[] payload = Arrays.copyOfRange(buf, FrameConstants.HEADER_LEN, crcOffset);
            discardPrefix(frameLen);
            results.add(new Result.FrameOk(new Frame(version, typeOpt.get(), flags, seq, payload)));
        }
        if (results.isEmpty()) results.add(new Result.NeedMoreData());
        return results;
    }

    /** 当前残留字节数（测试可观测）。 */
    public int bufferedBytes() {
        return size;
    }

    private boolean isMagicAt(int idx) {
        return (buf[idx] & 0xFF) == (FrameConstants.MAGIC >>> 8)
                && (buf[idx + 1] & 0xFF) == (FrameConstants.MAGIC & 0xFF);
    }

    /** 从 from 开始寻找 MAGIC；找不到返回 -1。要求保留位至少 2 字节可判。 */
    private int findMagic(int from) {
        for (int i = from; i + 1 < size; i++) {
            if ((buf[i] & 0xFF) == (FrameConstants.MAGIC >>> 8)
                    && (buf[i + 1] & 0xFF) == (FrameConstants.MAGIC & 0xFF)) {
                return i;
            }
        }
        return -1;
    }

    private long unsignedIntAt(int off) {
        return ((long) (buf[off] & 0xFF) << 24)
                | ((buf[off + 1] & 0xFF) << 16)
                | ((buf[off + 2] & 0xFF) << 8)
                | (buf[off + 3] & 0xFF);
    }

    private void append(byte[] in, int off, int len) {
        ensure(size + len);
        System.arraycopy(in, off, buf, size, len);
        size += len;
    }

    private void discardPrefix(int n) {
        if (n <= 0) return;
        int remain = size - n;
        if (remain > 0) System.arraycopy(buf, n, buf, 0, remain);
        size = remain;
    }

    private void ensure(int cap) {
        if (cap <= buf.length) return;
        int newCap = Math.max(cap, buf.length * 2);
        buf = Arrays.copyOf(buf, newCap);
    }
}
