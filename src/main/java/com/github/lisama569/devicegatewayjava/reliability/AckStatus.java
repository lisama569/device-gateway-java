package com.github.lisama569.devicegatewayjava.reliability;

/** 设备 ACK 的判定结论。 */
public enum AckStatus {
    ACKED,
    DUPLICATE_ACK,
    UNKNOWN_SEQ
}
