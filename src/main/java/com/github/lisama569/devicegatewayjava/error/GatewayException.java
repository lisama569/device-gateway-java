package com.github.lisama569.devicegatewayjava.error;

/** 网关处理过程中可映射为对外错误帧的异常。 */
public class GatewayException extends RuntimeException {
    private final GatewayErrorCode errorCode;

    public GatewayException(GatewayErrorCode errorCode) {
        super(errorCode.description());
        this.errorCode = errorCode;
    }

    public GatewayException(GatewayErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
    }

    public GatewayErrorCode errorCode() { return errorCode; }
}
