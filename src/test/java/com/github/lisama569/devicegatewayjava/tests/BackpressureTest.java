package com.github.lisama569.devicegatewayjava.tests;

import com.github.lisama569.devicegatewayjava.config.GatewayProperties;
import com.github.lisama569.devicegatewayjava.error.GatewayErrorCode;
import com.github.lisama569.devicegatewayjava.protocol.ErrorFrames;
import com.github.lisama569.devicegatewayjava.protocol.FrameConstants;
import com.github.lisama569.devicegatewayjava.protocol.FrameDecoder;
import com.github.lisama569.devicegatewayjava.protocol.MessageType;
import com.github.lisama569.devicegatewayjava.support.FrameAsserts;
import com.github.lisama569.devicegatewayjava.support.GatewayTestHarness;
import com.github.lisama569.devicegatewayjava.support.LoopbackClient;
import com.github.lisama569.devicegatewayjava.support.ManualTaskScheduler;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.Socket;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 资源上限：连接数超限拒绝；待发队列超限降级；接收残留受帧长约束。 */
class BackpressureTest {

    private static final Logger log = LoggerFactory.getLogger(BackpressureTest.class);

    private static GatewayPropsFactory props(int maxConn, int maxOutbound, int maxFrame) {
        return () -> {
            GatewayProperties p = GatewayTestHarness.defaultProps();
            p.setMaxConnections(maxConn);
            p.setMaxOutboundPerSession(maxOutbound);
            p.setMaxFrameLength(maxFrame);
            return p;
        };
    }

    private interface GatewayPropsFactory {
        GatewayProperties create();
    }

    /** 精确排空 socket 上的一整帧（读满帧头 -> 解析 LENGTH -> 读满 payload+CRC），不多读后续帧。 */
    private static void drainOneFrame(Socket socket) throws Exception {
        byte[] header = new byte[FrameConstants.HEADER_LEN];
        readExactly(socket, header);
        int len = ((header[10] & 0xFF) << 24) | ((header[11] & 0xFF) << 16)
                | ((header[12] & 0xFF) << 8) | (header[13] & 0xFF);
        readExactly(socket, new byte[len + 2]);
    }

    private static void readExactly(Socket socket, byte[] buf) throws Exception {
        int n = buf.length;
        int total = 0;
        while (total < n) {
            int r = socket.getInputStream().read(buf, total, n - total);
            if (r < 0) throw new IllegalStateException("EOF while draining " + n + " bytes");
            total += r;
        }
    }

    @Test
    void connectionOverLimitReceivesDistinctErrorAndServerSurvives() throws Exception {
        GatewayProperties p = GatewayTestHarness.defaultProps();
        p.setMaxConnections(2);
        ManualTaskScheduler clock = new ManualTaskScheduler();
        try (GatewayTestHarness h = new GatewayTestHarness(p, clock,
                Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC))) {

            List<Socket> sockets = new ArrayList<>();
            try {
                for (int i = 0; i < 2; i++) {
                    sockets.add(new Socket("127.0.0.1", h.port()));
                }
                Thread.sleep(150); // 等服务端登记许可

                // 第 3 条连接应收到 TOO_MANY_CONNECTIONS 后被关
                try (Socket rejected = new Socket("127.0.0.1", h.port())) {
                    byte[] hdr = new byte[FrameConstants.HEADER_LEN];
                    int total = 0;
                    while (total < hdr.length) {
                        int r = rejected.getInputStream().read(hdr, total, hdr.length - total);
                        if (r < 0) fail("expected error frame before close");
                        total += r;
                    }
                    assertEquals(0x7F, hdr[3] & 0xFF);
                    int len = ((hdr[10] & 0xFF) << 24) | ((hdr[11] & 0xFF) << 16)
                            | ((hdr[12] & 0xFF) << 8) | (hdr[13] & 0xFF);
                    byte[] rest = new byte[len + 2];
                    int t2 = 0;
                    while (t2 < rest.length) t2 += rejected.getInputStream().read(rest, t2, rest.length - t2);
                    byte[] payload = new byte[len];
                    System.arraycopy(rest, 0, payload, 0, len);
                    ErrorFrames.Parsed err = ErrorFrames.parsePayload(payload);
                    log.info("[连接上限] 第3条连接 -> code={} reason='{}'", err.code(), err.reason());
                    assertEquals(GatewayErrorCode.TOO_MANY_CONNECTIONS.code(), err.code());
                    assertTrue(rejected.isClosed() || rejected.getInputStream().read() < 0,
                            "rejected connection must be closed");
                }

                // 服务仍可正常处理：登录新连接。先关掉两条裸连接释放许可。
                sockets.get(0).close();
                sockets.get(1).close();
                sockets.clear();
                // 等待两条连接的读循环结束并归还许可（连接计数归 0）
                long dl = System.currentTimeMillis() + 2000;
                while (System.currentTimeMillis() < dl && h.connections.count() > 0) {
                    Thread.sleep(10);
                }
                try (LoopbackClient c = h.newClient()) {
                    c.negotiate(1); // 协商是登录前必经步骤
                    c.login("device-001", "secret-001", 1);
                    assertEquals(MessageType.ACK.code(), FrameAsserts.readFrameType(c));
                    log.info("[连接上限] 释放许可后新连接正常登录，服务未中断");
                }
            } finally {
                for (Socket s : sockets) s.close();
            }
        }
    }

    @Test
    void outboundQueueFullDegradesWithoutUnboundedGrowth() throws Exception {
        GatewayProperties p = GatewayTestHarness.defaultProps();
        p.setMaxOutboundPerSession(3);
        p.setMaxConnections(8);
        ManualTaskScheduler clock = new ManualTaskScheduler();
        try (GatewayTestHarness h = new GatewayTestHarness(p, clock,
                Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC))) {

            // 设备暂停读取且接收窗口很小 -> 服务端写线程很快阻塞在 flush，待发队列必然堆满
            Socket slow = new Socket();
            slow.setReceiveBufferSize(4096); // 必须在 connect 前设置，通告一个很小的接收窗口
            slow.connect(new java.net.InetSocketAddress("127.0.0.1", h.port()), 2000);
            // 先完成必经的协商：发 HELLO、按帧精确排空 HELLO_ACK（不多读，避免把登录 ACK 带走）
            slow.getOutputStream().write(LoopbackClient.helloFrame(1));
            slow.getOutputStream().flush();
            Thread.sleep(100);
            drainOneFrame(slow); // HELLO_ACK
            byte[] login = LoopbackClient.frame(MessageType.LOGIN, 0, 1,
                    LoopbackClient.loginPayload("device-001", "secret-001"));
            slow.getOutputStream().write(login);
            slow.getOutputStream().flush();
            Thread.sleep(100);
            // 精确排空 login ACK 一整帧（此后不再读取，下行帧将先塞满通告窗口再堆满待发队列）
            drainOneFrame(slow); // LOGIN ACK

            // 不读下行；用 1KB 负载持续投递，直到出现 4002（窗口+队列容量合计约数 KB，数十帧内必然触发）
            int attempts = 0;
            byte[] payload = new byte[1024];
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline
                    && h.metrics.errorCount(GatewayErrorCode.OUTBOUND_BUFFER_FULL.code()) == 0) {
                h.downlink.sendReliable("device-001", payload);
                attempts++;
            }
            // 可靠投递 offer 的首次发送若被 OutboundWriter 接受即认为 accepted；
            // 队列满后 sink 返回 false 时本次帧不入定时器（无资源堆积）
            long fullErrors = h.metrics.errorCount(GatewayErrorCode.OUTBOUND_BUFFER_FULL.code());
            log.info("[待发上限] 队列容量=3，attempts={} OUTBOUND_BUFFER_FULL计数={}", attempts, fullErrors);
            assertTrue(fullErrors > 0, "must observe OUTBOUND_BUFFER_FULL degradation");
            slow.close();
        }
    }
}
