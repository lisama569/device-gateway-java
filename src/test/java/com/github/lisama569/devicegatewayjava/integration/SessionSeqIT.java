package com.github.lisama569.devicegatewayjava.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.github.lisama569.devicegatewayjava.protocol.Frame;
import com.github.lisama569.devicegatewayjava.protocol.FrameEncoder;
import com.github.lisama569.devicegatewayjava.protocol.Messages;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class SessionSeqIT {

    private static final Logger log = LoggerFactory.getLogger(SessionSeqIT.class);

    private TestGateway gw;

    @AfterEach
    void tearDown() throws InterruptedException {
        if (gw != null) {
            gw.stop();
        }
        Thread.sleep(50);
    }

    @Test
    void duplicateOutOfOrderAndRollbackOverRealConnection() throws IOException, InterruptedException {
        gw = TestGateway.start();
        try (TestGateway.DeviceClient c = gw.loginClient("dev-001", "tok_valid_001")) {
            // seq=1 accepted
            Messages.Ack a1 = Messages.parseAck(c.uplink("k1", 1).payload());
            assertEquals(true, a1.accepted());

            // re-send seq=1 (duplicate) by crafting an explicit frame
            c.sendRaw(FrameEncoder.encode(new Frame(Frame.T_UPLINK, (byte) Frame.FLAG_DUP, 1,
                    Messages.uplink("k1", 1))));
            Messages.Ack dup = Messages.parseAck(c.readFrame().payload());
            assertFalse(dup.accepted());
            assertEquals(6001, dup.code());

            // gap: seq=3 before 2
            c.sendRaw(FrameEncoder.encode(new Frame(Frame.T_UPLINK, (byte) 0, 3,
                    Messages.uplink("k3", 3))));
            Messages.Ack gap = Messages.parseAck(c.readFrame().payload());
            assertEquals(6002, gap.code());

            // seq=1 while highWater is still 1 -> DUPLICATE (last message re-delivery)
            c.sendRaw(FrameEncoder.encode(new Frame(Frame.T_UPLINK, (byte) 0, 1,
                    Messages.uplink("k1", 1))));
            Messages.Ack dup2 = Messages.parseAck(c.readFrame().payload());
            assertEquals(6001, dup2.code());

            // seq=2 accepted, closes the gap
            Messages.Ack a2 = Messages.parseAck(c.uplink("k2", 2).payload());
            assertEquals(true, a2.accepted(), "seq=2 must now be accepted");

            // seq=1 now older than the water mark -> ROLLBACK
            c.sendRaw(FrameEncoder.encode(new Frame(Frame.T_UPLINK, (byte) 0, 1,
                    Messages.uplink("k1", 1))));
            Messages.Ack rollback = Messages.parseAck(c.readFrame().payload());
            assertEquals(6003, rollback.code());

            assertEquals(2, gw.deviceStates.get("dev-001").keyCount(),
                    "only 1 and 2 applied, exactly once each");
            log.info("judgement: dup=6001, gap=6002, dup-again=6001, seq=2 ACCEPTED, then rollback=6003; applied keys={}",
                    gw.deviceStates.get("dev-001").keyCount());
        }
    }

    @Test
    void reconnectResumesHighWaterAndRejectsReplay() throws IOException, InterruptedException {
        gw = TestGateway.start();
        try (TestGateway.DeviceClient c1 = gw.loginClient("dev-001", "tok_valid_001")) {
            c1.uplink("a", 1);
            c1.uplink("b", 2);
        }
        Thread.sleep(100); // allow server cleanup of first session
        assertEquals(0, gw.sessions.activeCount());

        try (TestGateway.DeviceClient c2 = gw.loginClient("dev-001", "tok_valid_001")) {
            // replay an old seq after reconnect
            c2.sendRaw(FrameEncoder.encode(new Frame(Frame.T_UPLINK, (byte) 0, 1,
                    Messages.uplink("a", 1))));
            Messages.Ack rollback = Messages.parseAck(c2.readFrame().payload());
            assertEquals(6003, rollback.code());

            // fresh seq 3 applies
            c2.sendRaw(FrameEncoder.encode(new Frame(Frame.T_UPLINK, (byte) 0, 3,
                    Messages.uplink("c", 3))));
            Messages.Ack a3 = Messages.parseAck(c2.readFrame().payload());
            assertEquals(true, a3.accepted());
            assertEquals(3, gw.deviceStates.get("dev-001").keyCount(),
                    "replayed business keys must not duplicate");
            log.info("judgement: reconnect resumes highWater=2: replay seq=1 -> 6003, seq=3 ACCEPTED, keys={}",
                    gw.deviceStates.get("dev-001").keyCount());
        }
    }

    @Test
    void deviceReconnectImmediatelyAfterCloseIsAllowed() throws IOException, InterruptedException {
        gw = TestGateway.start();
        try (TestGateway.DeviceClient c1 = gw.loginClient("dev-001", "tok_valid_001")) {
            c1.uplink("x", 1);
        }
        Thread.sleep(100);
        try (TestGateway.DeviceClient c2 = gw.loginClient("dev-001", "tok_valid_001")) {
            c2.sendRaw(FrameEncoder.encode(new Frame(Frame.T_UPLINK, (byte) 0, 2,
                    Messages.uplink("y", 2))));
            Messages.Ack a2 = Messages.parseAck(c2.readFrame().payload());
            assertEquals(true, a2.accepted());
            log.info("judgement: device reconnect after disconnect accepted and continues from seq=2");
        }
    }
}
