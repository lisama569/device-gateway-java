package com.github.lisama569.devicegatewayjava.error;

/** 对外可区分的错误码。类别前缀：1=协议 2=会话 3=认证 4=资源。 */
public enum GatewayErrorCode {
    OK(0, "OK"),
    MAGIC_MISMATCH(1001, "magic mismatch, resynchronized"),
    VERSION_MISMATCH(1002, "unsupported protocol version"),
    UNKNOWN_MESSAGE_TYPE(1003, "unknown message type"),
    FRAME_TOO_LONG(1004, "declared frame length exceeds limit"),
    CRC_MISMATCH(1005, "crc16 checksum mismatch"),
    MALFORMED_PAYLOAD(1006, "malformed payload"),
    NEGOTIATION_VERSION_INCOMPATIBLE(1007, "no common protocol version with gateway"),
    NEGOTIATION_CAPABILITY_UNSUPPORTED(1008, "requested capability not recognized or not supported"),
    CAPABILITY_NOT_NEGOTIATED(1009, "frame uses capability or extension field not negotiated for this session"),
    NOT_AUTHENTICATED(2001, "session not authenticated"),
    SESSION_CONFLICT(2002, "duplicate login on another connection"),
    SESSION_CLOSED(2003, "session closed"),
    DUPLICATE_MESSAGE(2004, "duplicate sequence, idempotent replay"),
    SEQ_OUT_OF_ORDER(2005, "sequence out of order or rolled back beyond window"),
    SEQ_OVERFLOW(2006, "sequence number overflow"),
    RETRY_EXHAUSTED(2007, "downlink retry count exhausted, session terminated"),
    NEGOTIATION_NOT_ALLOWED(2008, "negotiation not allowed at current session state"),
    CREDENTIAL_MISSING(3001, "device credentials missing"),
    CREDENTIAL_INVALID(3002, "invalid credentials"),
    CREDENTIAL_EXPIRED(3003, "credentials expired"),
    ALREADY_AUTHENTICATED(3004, "duplicate login on same connection"),
    TOO_MANY_CONNECTIONS(4001, "connection limit reached"),
    OUTBOUND_BUFFER_FULL(4002, "per-session outbound buffer full"),
    RECEIVE_BUFFER_FULL(4003, "receive buffer full"),
    SERVER_SHUTTING_DOWN(4004, "server shutting down");

    private final int code;
    private final String description;

    GatewayErrorCode(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int code() { return code; }

    public String description() { return description; }
}
