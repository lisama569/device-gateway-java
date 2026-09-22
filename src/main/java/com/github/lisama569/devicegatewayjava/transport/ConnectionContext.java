package com.github.lisama569.devicegatewayjava.transport;

import com.github.lisama569.devicegatewayjava.reliability.ReliableDownlink;
import com.github.lisama569.devicegatewayjava.session.GatewaySession;

import java.io.IOException;

/** 连接处理上下文：会话、有界写帧、可靠投递、请求关闭。 */
public interface ConnectionContext {

    GatewaySession session();

    ReliableDownlink reliableDownlink();

    /** 发送一帧（入有界待发队列）；队列满返回 false。 */
    boolean writeFrame(byte[] frame) throws IOException;

    /** 请求关闭连接并记录原因。 */
    void requestClose(String reason);

    /**
     * 排空后终止：把“关 socket”排到写线程待发队列末尾，
     * 保证此前已入队的帧（如最后一份重传）先送达，再终止连接。
     */
    void terminateAfterDrain(String reason);

    /**
     * 优雅关闭（两阶段）：先把通知帧入有界待发队列，再半关闭读方向唤醒读循环；
     * 保证通知帧先于 socket 关闭被排空。
     */
    void beginShutdown(byte[] notifyFrame, String reason);

    String connectionId();
}
