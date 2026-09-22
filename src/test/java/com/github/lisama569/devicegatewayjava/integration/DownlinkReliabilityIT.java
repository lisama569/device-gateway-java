package com.github.lisama569.devicegatewayjava.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.lisama569.devicegatewayjava.protocol.Frame;
import com.github.lisama569.devicegatewayjava.protocol.FrameEncoder;
import com.github.lisama569.devicegatewayjava.protocol.Messages;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reliable downlink over real loopback sockets, but with retransmission timers
 * driven explicitly via {@link ManualRetryScheduler}: retry/timeout convergence
 * is therefore race-free and needs no real sleeps.
 */
class DownlinkReliabilityIT {

    private static final Logger log = LoggerFactory.getLogger(DownlinkReliabilityIT.class);

    private TestGateway gw;

    @AfterEach
    void tearDown() throws InterruptedException {
        if (gw != null) {
            gw.stop();
        }
        Thread.sleep(50);
    }

    private void sendDownlinkAck(TestGateway.DeviceClient c, long downSeq, boolean accepted)
            throws IOException {
        c.sendRaw(FrameEncoder.encode(new Frame(Frame.T_DOWNLINK_ACK, (byte) 0, downSeq,
                Messages.ack(downSeq, accepted, accepted ? 0 : 6099,
                        accepted ? "OK" : "DEVICE_REJECTED"))));
    }

    private long interval() {
        return gw.props.getRetryIntervalMillis();
    }

    @Test
    void ackCompletesDownlinkWithZeroRetransmits() throws IOException, InterruptedException {
        gw = TestGateway.start();
        try (TestGateway.DeviceClient c = gw.loginClient("dev-001", "tok_valid_001")) {
            assertTrue(gw.server.sendDownlink("dev-001", 100L, "ping", "x"));
            Frame down = c.readFrame();
            assertEquals(Frame.T_DOWNLINK, down.type());
            assertEquals(0, down.flags() & Frame.FLAG_DUP);

            sendDownlinkAck(c, 100L, true);
            assertTrue(Wait.until(2000, () -> gw.metrics.get("downlink.acked") == 1));

            // fire every scheduled retry point: none should retransmit
            gw.retryScheduler.advance(interval() * 5);
            List<Frame> extra = c.drainFrames(150);
            assertTrue(extra.isEmpty());
            assertEquals(0, gw.metrics.get("downlink.retransmit"));
            log.info("judgement: downSeq=100 ACKed; even after firing 5 retry points, retransmissions=0");
        }
    }

    @Test
    void duplicateAckIsIgnored() throws IOException, InterruptedException {
        gw = TestGateway.start();
        try (TestGateway.DeviceClient c = gw.loginClient("dev-001", "tok_valid_001")) {
            gw.server.sendDownlink("dev-001", 101L, "ping", "x");
            c.readFrame();
            sendDownlinkAck(c, 101L, true);
            assertTrue(Wait.until(2000, () -> gw.metrics.get("downlink.acked") == 1));
            sendDownlinkAck(c, 101L, true);
            assertTrue(Wait.until(2000, () -> gw.metrics.get("downlink.duplicate-ack") == 1));
            log.info("judgement: first ACK completes downSeq=101, duplicate ACK counted & ignored (no state change)");
        }
    }

    @Test
    void lostAckConvergesWithBoundedRetriesThenTerminatesSession()
            throws IOException, InterruptedException {
        gw = TestGateway.start();
        try (TestGateway.DeviceClient c = gw.loginClient("dev-001", "tok_valid_001")) {
            gw.server.sendDownlink("dev-001", 102L, "config", "v1");
            Frame first = c.readFrame();
            assertEquals(0, first.flags() & Frame.FLAG_DUP);

            int maxRetries = gw.props.getMaxRetries();
            // Fire retry points; device never ACKs: exactly maxRetries retransmits.
            for (int i = 1; i <= maxRetries; i++) {
                gw.retryScheduler.advance(interval());
                Frame re = c.readFrame();
                assertEquals(Frame.T_DOWNLINK, re.type());
                assertEquals(Frame.FLAG_DUP, re.flags() & Frame.FLAG_DUP);
                assertEquals(102L, re.seq());
                log.info("judgement: retransmit {}/{} of downSeq=102 received (DUP flag set)",
                        i, maxRetries);
            }
            assertEquals(maxRetries, gw.metrics.get("downlink.retransmit"));

            // One more interval: budget exhausted -> error frame, session terminated.
            gw.retryScheduler.advance(interval());
            Frame err = c.readFrame();
            assertEquals(Frame.T_ERROR, err.type());
            Messages.ErrorBody body = Messages.parseError(err.payload());
            assertEquals(6005, body.code());
            assertEquals("session", body.category());
            log.info("judgement: after 1 original + {} retransmits unacked -> SESSION_TERMINATED_RETRY_EXHAUSTED(6005) reason='{}'",
                    maxRetries, body.reason());

            // orderly marker then closes the socket once the error is flushed
            byte[] drain = new byte[8192];
            boolean eof = false;
            long deadline = System.currentTimeMillis() + 2000;
            while (System.currentTimeMillis() < deadline) {
                int r = c.in.read(drain);
                if (r == -1) {
                    eof = true;
                    break;
                }
            }
            assertTrue(eof, "terminated session socket must close after error flush");
            assertEquals(1, gw.metrics.get("errors.code.SESSION_TERMINATED_RETRY_EXHAUSTED"));
        }
    }

    @Test
    void ackAfterOneRetransmitFreezesAllFurtherRetransmits()
            throws IOException, InterruptedException {
        gw = TestGateway.start();
        try (TestGateway.DeviceClient c = gw.loginClient("dev-001", "tok_valid_001")) {
            gw.server.sendDownlink("dev-001", 103L, "config", "v2");
            c.readFrame();

            gw.retryScheduler.advance(interval());
            Frame retry = c.readFrame();
            assertEquals(Frame.FLAG_DUP, retry.flags() & Frame.FLAG_DUP);
            assertEquals(1, gw.metrics.get("downlink.retransmit"));

            sendDownlinkAck(c, 103L, true);
            assertTrue(Wait.until(2000, () -> gw.metrics.get("downlink.acked") == 1));

            gw.retryScheduler.advance(interval() * 3);
            List<Frame> extra = c.drainFrames(150);
            assertTrue(extra.isEmpty(), "ACK must cancel every further retransmit");
            assertEquals(1, gw.metrics.get("downlink.retransmit"));
            log.info("judgement: exactly 1 retransmit before ACK and 0 after; convergence once");
        }
    }
}
