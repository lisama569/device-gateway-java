package com.github.lisama569.devicegatewayjava.tests;

import com.github.lisama569.devicegatewayjava.protocol.ErrorFrames;
import com.github.lisama569.devicegatewayjava.protocol.FrameConstants;
import com.github.lisama569.devicegatewayjava.protocol.MessageType;
import com.github.lisama569.devicegatewayjava.support.FrameAsserts;
import com.github.lisama569.devicegatewayjava.support.GatewayTestHarness;
import com.github.lisama569.devicegatewayjava.support.LoopbackClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.ConnectException;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.*;

/** 优雅关闭：已提交状态不丢、在途连接收到通知后关闭、关闭后不再接受连接。 */
@Timeout(15)
class ShutdownTest {

    private static final Logger log = LoggerFactory.getLogger(ShutdownTest.class);

    @Test
    void committedStateSurvivesAndConnectionsCloseOnShutdown() throws Exception {
        GatewayTestHarness h = new GatewayTestHarness();
        LoopbackClient c = h.newClient();
        c.login("device-001", "secret-001", 1);
        FrameAsserts.readFrameType(c);

        for (long s = 1; s <= 5; s++) {
            c.sendRaw(LoopbackClient.frame(MessageType.DATA, 0, s, ("v" + s).getBytes()));
            assertEquals(MessageType.ACK.code(), FrameAsserts.readFrameType(c));
        }
        long appliedBefore = h.states.get("device-001").appliedCount();
        assertEquals(5, appliedBefore);

        h.close(); // 触发优雅关闭

        // 已提交状态仍在（state store 不随连接销毁）
        long appliedAfter = h.states.get("device-001").appliedCount();
        assertEquals(appliedBefore, appliedAfter, "committed state must survive shutdown");
        assertEquals("v5", new String(h.states.get("device-001").lastPayload()));

        // 在途连接应收到 SERVER_SHUTTING_DOWN 错误帧或被关闭
        byte[] header = c.readN(FrameConstants.HEADER_LEN);
        if ((header[3] & 0xFF) == 0x7F) {
            int len = ((header[10] & 0xFF) << 24) | ((header[11] & 0xFF) << 16)
                    | ((header[12] & 0xFF) << 8) | (header[13] & 0xFF);
            byte[] rest = c.readN(len + 2);
            byte[] payload = new byte[len];
            System.arraycopy(rest, 0, payload, 0, len);
            ErrorFrames.Parsed err = ErrorFrames.parsePayload(payload);
            log.info("[优雅关闭] 在途连接收到 code={} reason='{}'", err.code(), err.reason());
            assertEquals(4004, err.code());
        } else {
            log.info("[优雅关闭] 在途连接直接关闭 type={}", header[3] & 0xFF);
        }
        c.close();

        // 关闭后新连接被拒绝（端口不再监听）
        assertThrows(ConnectException.class,
                () -> new Socket("127.0.0.1", h.port()));
        log.info("[优雅关闭] appliedCount 关闭前后均={}，端口停止监听，结论一致", appliedAfter);
    }
}
