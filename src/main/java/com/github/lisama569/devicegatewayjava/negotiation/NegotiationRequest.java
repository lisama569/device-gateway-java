package com.github.lisama569.devicegatewayjava.negotiation;

import java.util.List;

/** 解析后的 NEGOTIATE 请求：设备声称支持的协议版本闭区间 + 去重后的能力 code 列表（保持上报顺序）。 */
public record NegotiationRequest(int minVersion, int maxVersion, List<Byte> offeredCapabilityCodes) {
}
