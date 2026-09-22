package com.github.lisama569.devicegatewayjava.protocol;

/**
 * Distinguishable external error codes grouped by category:
 * protocol / auth / session / resource.
 */
public enum ErrorCode {

    // protocol (4xxx)
    BAD_MAGIC(RejectReason.BAD_MAGIC),
    HEADER_CRC_MISMATCH(RejectReason.HEADER_CRC_MISMATCH),
    FRAME_TOO_LONG(RejectReason.FRAME_TOO_LONG),
    FRAME_LENGTH_IMPOSSIBLE(RejectReason.FRAME_LENGTH_IMPOSSIBLE),
    BODY_CRC_MISMATCH(RejectReason.BODY_CRC_MISMATCH),
    UNKNOWN_MESSAGE_TYPE(RejectReason.UNKNOWN_MESSAGE_TYPE),
    VERSION_MISMATCH(RejectReason.VERSION_MISMATCH),
    MALFORMED_PAYLOAD(RejectReason.MALFORMED_PAYLOAD),

    // auth (5xxx)
    CREDENTIAL_MISSING("auth", 5001),
    CREDENTIAL_INVALID("auth", 5002),
    CREDENTIAL_EXPIRED("auth", 5003),
    ALREADY_LOGGED_IN("auth", 5004),
    UNAUTHENTICATED("auth", 5005),

    // session (6xxx)
    DUPLICATE_SEQ("session", 6001),
    SEQ_OUT_OF_ORDER("session", 6002),
    SEQ_ROLLBACK("session", 6003),
    SESSION_CLOSED("session", 6004),
    SESSION_TERMINATED_RETRY_EXHAUSTED("session", 6005),

    // resource (7xxx)
    TOO_MANY_CONNECTIONS("resource", 7001),
    RECEIVE_BUFFER_EXCEEDED("resource", 7002),
    OUTBOUND_QUEUE_FULL("resource", 7003);

    private final String category;
    private final int code;

    ErrorCode(String category, int code) {
        this.category = category;
        this.code = code;
    }

    ErrorCode(RejectReason reason) {
        this(reason.category(), reason.code());
    }

    public String category() {
        return category;
    }

    public int code() {
        return code;
    }

    public static ErrorCode from(RejectReason reason) {
        return ErrorCode.valueOf(reason.name());
    }
}
