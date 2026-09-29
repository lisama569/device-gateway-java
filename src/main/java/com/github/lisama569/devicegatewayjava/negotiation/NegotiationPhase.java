package com.github.lisama569.devicegatewayjava.negotiation;

/** 会话协商阶段：NEW（连接刚建立）-> SETTLED（协商定稿）；失败由直接关连接表达，不进入状态机。 */
public enum NegotiationPhase {
    NEW,
    SETTLED
}
