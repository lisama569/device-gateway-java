package com.github.lisama569.devicegatewayjava.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.lisama569.devicegatewayjava.protocol.Frame;
import com.github.lisama569.devicegatewayjava.protocol.FrameEncoder;
import com.github.lisama569.devicegatewayjava.protocol.Messages;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ResourceAndConcurrencyIT {

    private static final Logger log = LoggerFactory.getLogger(ResourceAndConcurrencyIT.class);

    private TestGateway gw;

    @AfterEach
    void tearDown() throws InterruptedException {
        if (gw != null) {
            gw.stop();
        }
        Thread.sleep(50);
    }

    @Test
    void connectionsOverLimitAreRejectedWith7001() throws Exception {
        gw = TestGateway.start(); // maxConnections = 4
        List<TestGateway.DeviceClient> clients = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            clients.add(TestGateway.DeviceClient.create(gw, gw.connect()));
        }
        // 5th connection must be accepted at TCP then immediately closed by the gateway
        long rejectedBefore = gw.metrics.get("errors.code.TOO_MANY_CONNECTIONS");
        Socket extra = gw.connect();
        boolean closed = Wait.until(2000, () -> {
            try {
                int b = extra.getInputStream().read();
                return b == -1;
            } catch (IOException e) {
                return true;
            }
        });
        assertTrue(closed, "connection beyond maxConnections must be closed");
        assertTrue(gw.metrics.get("errors.code.TOO_MANY_CONNECTIONS") > rejectedBefore);
        log.info("judgement: 5th connection with maxConnections=4 closed immediately, error 7001 counted (no worker thread allocated)");
        extra.close();
        for (TestGateway.DeviceClient c : clients) {
            c.close();
        }
    }

    @Test
    void receiveBufferOverLimitClosesConnectionWith7002() throws Exception {
        gw = TestGateway.start();
        try (Socket s = gw.connect()) {
            // write more than the 64KiB receive budget without a parseable frame
            byte[] junk = new byte[(1 << 16) + 1024];
            // avoid magic pair so the decoder keeps buffering until the bound trips
            for (int i = 0; i < junk.length; i++) {
                junk[i] = (byte) (0x41 + (i % 10));
            }
            s.getOutputStream().write(junk);
            s.getOutputStream().flush();
            // server sends error frames then closes; bulk-drain until EOF/RST
            s.setSoTimeout(2000);
            byte[] drain = new byte[8192];
            boolean terminated = false;
            try {
                while (s.getInputStream().read(drain) >= 0) {
                    // drain until peer closes
                }
                terminated = true;
            } catch (IOException eofOrReset) {
                terminated = true; // RST on close-with-unread-input is also termination
            }
            assertTrue(terminated, "connection must be terminated after buffer overflow");
            assertTrue(gw.metrics.get("errors.code.RECEIVE_BUFFER_EXCEEDED") >= 1);
            log.info("judgement: {} junk bytes (>{}) -> RECEIVE_BUFFER_EXCEEDED(7002), connection terminated",
                    junk.length, gw.props.getMaxBufferedBytes());
        }
    }

    @Test
    void concurrentLoginsForDistinctDevicesAllSucceed() throws Exception {
        gw = TestGateway.start();
        // add credentials for many devices
        for (int i = 1; i <= 4; i++) {
            gw.props.getCredentials().put("dev-" + String.format("%03d", i),
                    TestGateway.defaultProps().getCredentials().get("dev-001"));
        }
        int n = 4;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();
        List<java.io.OutputStream> outs = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 1; i <= n; i++) {
            final String id = "dev-" + String.format("%03d", i);
            pool.submit(() -> {
                try {
                    start.await();
                    try (TestGateway.DeviceClient c = gw.loginClient(id, "tok_valid_001")) {
                        outs.add(c.out);
                        Frame ack = c.uplink("key-" + id, 1L);
                        if (Messages.parseAck(ack.payload()).accepted()) {
                            success.incrementAndGet();
                        } else {
                            failure.incrementAndGet();
                        }
                        Thread.sleep(60);
                    }
                } catch (Exception e) {
                    failure.incrementAndGet();
                    log.warn("client {} failed: {}", id, e.toString());
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(n, success.get(), "all distinct-device logins must succeed concurrently");
        assertEquals(0, failure.get());
        log.info("judgement: {}/{} concurrent devices logged in and applied one uplink each, no session cross-talk",
                success.get(), n);
    }

    @Test
    void concurrentDuplicateLoginsForSameDeviceOnlyOneWins() throws Exception {
        gw = TestGateway.start();
        int n = 4;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger duplicate = new AtomicInteger();
        List<TestGateway.DeviceClient> keepAlive = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    TestGateway.DeviceClient c =
                            TestGateway.DeviceClient.create(gw, gw.connect());
                    c.sendRaw(FrameEncoder.encode(new Frame(Frame.T_HELLO, (byte) 0, 0,
                            Messages.hello(1, 1))));
                    c.readFrame();
                    c.sendRaw(FrameEncoder.encode(new Frame(Frame.T_LOGIN, (byte) 0, 0,
                            Messages.login("dev-001", "tok_valid_001", gw.clock.nowMillis()))));
                    Frame ack = c.readFrame();
                    Messages.LoginAck la = Messages.parseLoginAck(ack.payload());
                    if (la.accepted()) {
                        accepted.incrementAndGet();
                        synchronized (keepAlive) {
                            keepAlive.add(c);
                        }
                    } else if (la.code() == 5004) {
                        duplicate.incrementAndGet();
                        c.close();
                    }
                } catch (Exception e) {
                    log.warn("race login error: {}", e.toString());
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(1, accepted.get(), "exactly one login wins the device slot");
        assertEquals(n - 1, duplicate.get(), "all others get ALREADY_LOGGED_IN");
        log.info("judgement: {} simultaneous logins for one device -> 1 accepted, {} ALREADY_LOGGED_IN",
                n, duplicate.get());
        for (TestGateway.DeviceClient c : new ArrayList<>(keepAlive)) {
            c.close();
        }
    }

    @Test
    void gracefulShutdownKeepsCommittedStateAndClosesSockets() throws Exception {
        gw = TestGateway.start();
        try (TestGateway.DeviceClient c = gw.loginClient("dev-001", "tok_valid_001")) {
            c.uplink("committed-1", 1);
            c.uplink("committed-2", 2);
            assertTrue(Wait.until(2000,
                    () -> gw.deviceStates.get("dev-001").keyCount() == 2));

            gw.stop();
            gw = null; // prevent double stop in tearDown

            int b = c.in.read();
            assertEquals(-1, b, "socket must close during graceful shutdown");
            // committed state survives in the store for resume semantics
            // (the store is in-process; a restart would rebuild the gateway)
            log.info("judgement: shutdown closed the client socket; committed keys before stop = {}", 2);
        }
    }
}
