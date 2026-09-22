package com.github.lisama569.devicegatewayjava.protocol;

/** Outcome of feeding bytes into a FrameDecoder. */
public sealed interface DecodeResult {

    record FrameOk(Frame frame) implements DecodeResult {
    }

    record FrameRejected(RejectReason reason, long consumedBytes, String detail) implements DecodeResult {
    }

    record NeedMoreData() implements DecodeResult {
    }
}
