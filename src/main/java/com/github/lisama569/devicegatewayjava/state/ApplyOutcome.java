package com.github.lisama569.devicegatewayjava.state;

/** 上行序号判定结论。 */
public enum ApplyOutcome {
    APPLIED,
    DUPLICATE,
    EXPIRED,
    OUT_OF_ORDER
}
