package com.github.lisama569.devicegatewayjava.session;

import com.github.lisama569.devicegatewayjava.protocol.ErrorCode;

/** Deterministic decision for an inbound device sequence number. */
public enum SeqDecision {
    ACCEPT,           // fresh, exactly lastApplied+1
    DUPLICATE,        // equals lastApplied (re-delivery of the last one)
    OUT_OF_ORDER,     // behind lastApplied and not duplicate, or ahead by >1
    ROLLBACK;         // smaller than the persisted high-water mark on reconnect

    public ErrorCode errorCode() {
        return switch (this) {
            case DUPLICATE -> ErrorCode.DUPLICATE_SEQ;
            case ROLLBACK -> ErrorCode.SEQ_ROLLBACK;
            default -> ErrorCode.SEQ_OUT_OF_ORDER;
        };
    }
}
