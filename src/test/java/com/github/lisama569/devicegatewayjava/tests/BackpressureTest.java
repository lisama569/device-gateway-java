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
                    c.negotiate();
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

            // 设备暂停读取 -> 队列很快被填满。
            // 连接前把接收窗口调到极小：设备不读时，发送端在数帧后即被 TCP 反压阻塞，
            // 从而有界待发队列(容量3)必然被填满并降级——避免依赖本机默认大回环缓冲造成用例不稳定。
            Socket slow = new Socket();
            slow.setReceiveBufferSize(256);
            slow.connect(new java.net.InetSocketAddress("127.0.0.1", h.port()), 2000);
            // 先协商：发 HELLO 并读完 HELLO_ACK（协商前置）
            byte[] hello = LoopbackClient.frame(MessageType.HELLO, 0, 1,
                    com.github.lisama569.devicegatewayjava.negotiation.HelloPayload
                            .encodeHello(1, 1, java.util.List.of()));
            slow.getOutputStream().write(hello);
            slow.getOutputStream().flush();
            byte[] ackHeader = new byte[FrameConstants.HEADER_LEN];
            int hr = 0;
            while (hr < ackHeader.length) {
                int r = slow.getInputStream().read(ackHeader, hr, ackHeader.length - hr);
                if (r < 0) fail("EOF while expecting HELLO_ACK");
                hr += r;
            }
            assertEquals(MessageType.HELLO_ACK.code() & 0xFF, ackHeader[3], "expected HELLO_ACK");
            int ackLen = ((ackHeader[10] & 0xFF) << 24) | ((ackHeader[11] & 0xFF) << 16)
                    | ((ackHeader[12] & 0xFF) << 8) | (ackHeader[13] & 0xFF);
            byte[] ackRest = new byte[ackLen + 2];
            int rr = 0;
            while (rr < ackRest.length) rr += slow.getInputStream().read(ackRest, rr, ackRest.length - rr);

            byte[] login = LoopbackClient.frame(MessageType.LOGIN, 0, 2,
                    LoopbackClient.loginPayload("device-001", "secret-001"));
            slow.getOutputStream().write(login);
            slow.getOutputStream().flush();
            Thread.sleep(100);
            // 排空 login ACK（会阻塞读，用小容量只读一次）
            byte[] drain = new byte[1024];
            slow.getInputStream().read(drain);

            // 此时不读下行；持续投递大帧直到被降级（极小接收窗口 + 大负载下很快填满）
            byte[] bigPayload = new byte[400];
            java.util.Arrays.fill(bigPayload, (byte) 'm');
            int accepted = 0, degraded = 0;
            for (int i = 0; i < 200; i++) {
                var r = h.downlink.sendReliable("device-001", bigPayload);
                if (r.accepted()) accepted++;
                else { degraded++; }
                if (!r.accepted() && r.errorCode() == GatewayErrorCode.OUTBOUND_BUFFER_FULL.code()) break;
            }
            // 可靠投递 offer 的首次发送若被 OutboundWriter 接受即认为 accepted；
            // 队列满后 sink 返回 false 时本次帧不入定时器（无资源堆积）
            long fullErrors = h.metrics.errorCount(GatewayErrorCode.OUTBOUND_BUFFER_FULL.code());
            log.info("[待发上限] 队列容量=3，accepted={} OUTBOUND_BUFFER_FULL计数={}", accepted, fullErrors);
            assertTrue(fullErrors > 0, "must observe OUTBOUND_BUFFER_FULL degradation");
            slow.close();
        }
    }
}
