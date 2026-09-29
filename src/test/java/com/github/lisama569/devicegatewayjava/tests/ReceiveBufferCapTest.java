package com.github.lisama569.devicegatewayjava.tests;

import com.github.lisama569.devicegatewayjava.error.GatewayErrorCode;
import com.github.lisama569.devicegatewayjava.protocol.FrameDecoder;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 接收侧按连接残留硬上限：与单帧长度分开配置，残留超限给唯一 BufferOverflow 结论。 */
class ReceiveBufferCapTest {

    private static final Logger log = LoggerFactory.getLogger(ReceiveBufferCapTest.class);

    @Test
    void residualOverCapProducesDistinctOverflow() {
        // 单帧上限很宽（512），但连接残留硬上限只有 32：半包挂住即触发
        FrameDecoder d = new FrameDecoder(512, 32);
        byte[] header = new byte[]{(byte) 0xA5, 0x5A, 1, 0x10, 0, 0, 0, 0, 0, 0,
                0, 0, 0x01, 0x00}; // 声明 payload=256，合法（<=512）
        List<FrameDecoder.Result> r = d.feed(header, 0, header.length);
        assertInstanceOf(FrameDecoder.Result.NeedMoreData.class, r.get(0),
                "头收齐、体没齐 -> 继续等");

        byte[] body = new byte[40]; // 累计残留 14+40=54 > 32
        List<FrameDecoder.Result> r2 = d.feed(body, 0, body.length);
        FrameDecoder.Result last = r2.get(r2.size() - 1);
        log.info("[残留超限] 单帧上限=512 残留上限=32 残留=54 -> {}",
                last.getClass().getSimpleName());
        assertInstanceOf(FrameDecoder.Result.BufferOverflow.class, last);
        FrameDecoder.Result.BufferOverflow over = (FrameDecoder.Result.BufferOverflow) last;
        assertEquals(54, over.bufferedBytes());
        assertEquals(32, over.limit());
    }

    @Test
    void completedFrameNeverTripsResidualCap() {
        // 残留上限 128，发一个 payload=64 的完整帧（总长 80 < 128），正常解析
        FrameDecoder d = new FrameDecoder(512, 128);
        byte[] frame = com.github.lisama569.devicegatewayjava.protocol.FrameCodec.encode(
                new com.github.lisama569.devicegatewayjava.protocol.Frame(
                        (byte) 1,
                        com.github.lisama569.devicegatewayjava.protocol.MessageType.DATA,
                        (byte) 0, 1, new byte[64]));
        List<FrameDecoder.Result> r = d.feed(frame, 0, frame.length);
        assertInstanceOf(FrameDecoder.Result.FrameOk.class, r.get(0));
        assertEquals(0, d.bufferedBytes());
        log.info("[完整帧] 帧长={} 未触碰残留上限，解析后残留=0", frame.length);
    }

    @Test
    void singleFrameLimitAndResidualCapAreIndependent() {
        // 声明 payload=1000 > 单帧上限 512 -> FRAME_TOO_LONG（与残留上限无关），连接可继续
        FrameDecoder d = new FrameDecoder(512, 4096);
        byte[] overlong = new byte[]{(byte) 0xA5, 0x5A, 1, 0x10, 0, 0, 0, 0, 0, 0,
                0, 0, 0x03, (byte) 0xE8}; // len=1000
        List<FrameDecoder.Result> r = d.feed(overlong, 0, overlong.length);
        FrameDecoder.Result e = r.get(0);
        assertInstanceOf(FrameDecoder.Result.FrameError.class, e);
        assertEquals(GatewayErrorCode.FRAME_TOO_LONG, ((FrameDecoder.Result.FrameError) e).code());
        assertFalse(r.stream().anyMatch(x -> x instanceof FrameDecoder.Result.BufferOverflow));
        log.info("[两道上限独立] 超长声明 -> FRAME_TOO_LONG，不产生 BufferOverflow");
    }
}
