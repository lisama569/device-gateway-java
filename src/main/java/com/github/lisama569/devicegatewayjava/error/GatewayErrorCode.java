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
    /** 协商请求中带了网关无法识别/不支持的能力项。 */
    UNSUPPORTED_CAPABILITY(1007, "unrecognized or unsupported capability offered"),
    NOT_AUTHENTICATED(2001, "session not authenticated"),
    SESSION_CONFLICT(2002, "duplicate login on another connection"),
    SESSION_CLOSED(2003, "session closed"),
    DUPLICATE_MESSAGE(2004, "duplicate sequence, idempotent replay"),
    SEQ_OUT_OF_ORDER(2005, "sequence out of order or rolled back beyond window"),
    SEQ_OVERFLOW(2006, "sequence number overflow"),
    RETRY_EXHAUSTED(2007, "downlink retry count exhausted, session terminated"),
    /** 协商未完成就发业务帧（连接保留，允许其回头完成协商）。 */
    NEGOTIATION_REQUIRED(2008, "capability negotiation required before any business frame"),
    /** 已协商会话使用了未谈拢的能力/扩展（连接保留，单帧拒绝）。 */
    CAPABILITY_VIOLATION(2009, "frame uses a capability/extension not agreed in this session"),
    /** 协商已定稿后再次发起协商/试图改写版本与能力。 */
    NEGOTIATION_SETTLED(2010, "negotiation already settled for this session"),
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
