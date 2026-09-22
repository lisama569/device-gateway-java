package com.github.lisama569.devicegatewayjava.protocol;

/** Distinguishable reasons for rejecting an invalid frame/request. */
public enum RejectReason {
    BAD_MAGIC("protocol", 4001),
    HEADER_CRC_MISMATCH("protocol", 4002),
    FRAME_TOO_LONG("protocol", 4003),
    FRAME_LENGTH_IMPOSSIBLE("protocol", 4004),
    BODY_CRC_MISMATCH("protocol", 4005),
    UNKNOWN_MESSAGE_TYPE("protocol", 4006),
    VERSION_MISMATCH("protocol", 4007),
    MALFORMED_PAYLOAD("protocol", 4008);

    private final String category;
    private final int code;

    RejectReason(String category, int code) {
        this.category = category;
        this.code = code;
    }

    public String category() {
        return category;
    }

    public int code() {
        return code;
    }
}
