package com.github.lisama569.devicegatewayjava.tests;

import com.github.lisama569.devicegatewayjava.protocol.MessageType;
import com.github.lisama569.devicegatewayjava.support.FrameAsserts;
import com.github.lisama569.devicegatewayjava.support.GatewayTestHarness;
import com.github.lisama569.devicegatewayjava.support.LoopbackClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 多连接并发：多设备同时登录/上报，业务状态各自连续、无串帧串号、无半更新。 */
@Timeout(10)
class ConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyTest.class);

    @Test
    void manyDevicesConcurrentKeepIndependentOrderedState() throws Exception {
        try (GatewayTestHarness h = new GatewayTestHarness()) {
            // 注册 12 个设备
            int devices = 12;
            for (int i = 0; i < devices; i++) {
                h.credentials.register(new com.github.lisama569.devicegatewayjava.auth.DeviceCredential(
                        "dev-" + i, "pass-" + i, 0L));
            }
            int messagesPerDevice = 20;
            ExecutorService pool = Executors.newFixedThreadPool(devices);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger failures = new AtomicInteger();
            ConcurrentLinkedQueue<String> errors = new ConcurrentLinkedQueue<>();

            for (int d = 0; d < devices; d++) {
                final int dev = d;
                pool.submit(() -> {
                    try {
                        start.await();
                        try (LoopbackClient c = h.newClient()) {
                            c.login("dev-" + dev, "pass-" + dev, 1);
                            if (FrameAsserts.readFrameType(c) != MessageType.ACK.code()) {
                                failures.incrementAndGet();
                                errors.add("dev-" + dev + " login no ack");
                            }
                            for (long s = 1; s <= messagesPerDevice; s++) {
                                byte[] payload = ("dev" + dev + "-msg" + s).getBytes();
                                c.sendRaw(LoopbackClient.frame(MessageType.DATA, 0, s, payload));
                                int type = FrameAsserts.readFrameType(c);
                                if (type != MessageType.ACK.code()) {
                                    failures.incrementAndGet();
                                    errors.add("dev-" + dev + " seq=" + s + " got type=" + type);
                                }
                            }
                        }
                    } catch (Exception e) {
                        failures.incrementAndGet();
                        errors.add("dev-" + dev + " ex: " + e.getMessage());
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));

            log.info("[并发] {} 设备 x {} 消息全部完成, failures={}", devices, messagesPerDevice, failures.get());
            assertEquals(0, failures.get(), () -> "errors: " + errors);

            // 每台设备状态独立且恰好生效 messagesPerDevice 次，负载属于自己（无串号）
            for (int d = 0; d < devices; d++) {
                var st = h.states.get("dev-" + d);
                assertEquals(messagesPerDevice, st.appliedCount(), "dev-" + d + " applied count");
                assertEquals(messagesPerDevice, st.lastAppliedSeq(), "dev-" + d + " last seq");
                String lastPayload = new String(st.lastPayload());
                assertEquals("dev" + d + "-msg" + messagesPerDevice, lastPayload,
                        "dev-" + d + " last payload must be its own, no cross-talk");
            }
            log.info("[并发] 各设备 appliedCount={} 且最后负载各自匹配，无串帧/半更新",
                    messagesPerDevice);
        }
    }

    @Test
    void interleavedFramesOnSharedConnectionRemainFramed() throws Exception {
        // 同一条连接上快速灌入 31 帧（随机 1~7 字节分片），响应必须逐帧可读、不串帧。
        // 调大待发队列以容纳突发（队列超限时的降级行为由 BackpressureTest 专门覆盖）。
        com.github.lisama569.devicegatewayjava.config.GatewayProperties props =
                GatewayTestHarness.defaultProps();
        props.setMaxOutboundPerSession(64);
        try (GatewayTestHarness h = new GatewayTestHarness(props,
                new com.github.lisama569.devicegatewayjava.support.ManualTaskScheduler(),
                java.time.Clock.fixed(java.time.Instant.parse("2026-09-01T00:00:00Z"),
                        java.time.ZoneOffset.UTC))) {
            h.credentials.register(new com.github.lisama569.devicegatewayjava.auth.DeviceCredential(
                    "dev-x", "pass-x", 0L));
            try (LoopbackClient c = h.newClient()) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                bos.write(LoopbackClient.frame(MessageType.NEGOTIATE, 0, 999,
                        LoopbackClient.negotiatePayload(1, 1, 1, 2)));
                bos.write(LoopbackClient.frame(MessageType.LOGIN, 0, 1000,
                        LoopbackClient.loginPayload("dev-x", "pass-x")));
                for (long s = 1; s <= 30; s++) {
                    bos.write(LoopbackClient.frame(MessageType.DATA, 0, s,
                            ("m" + s).getBytes()));
                }
                c.sendRandomChunks(bos.toByteArray(), 42L);
                assertEquals(MessageType.NEGOTIATE_ACK.code(), FrameAsserts.readFrameType(c),
                        "response frame 0 (negotiate)");
                for (int i = 1; i <= 31; i++) {
                    int t = FrameAsserts.readFrameType(c);
                    assertEquals(MessageType.ACK.code(), t, "response frame " + i);
                }
                assertEquals(30, h.states.get("dev-x").appliedCount());
                log.info("[粘包并发] 31 帧以随机 1~7 字节分片灌入，全部逐帧响应且生效");
            }
        }
    }
}
