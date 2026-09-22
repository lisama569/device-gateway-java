package com.github.lisama569.devicegatewayjava.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class Crc16Test {

    private static final Logger log = LoggerFactory.getLogger(Crc16Test.class);

    @Test
    void knownVectors() {
        // CRC-16/CCITT-FALSE check value for "123456789" is 0x29B1.
        byte[] ascii = "123456789".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int crc = Crc16.crc(ascii);
        log.info("input='123456789' -> crc=0x{} basis=init 0xFFFF", Integer.toHexString(crc));
        assertEquals(0x29B1, crc);
        assertEquals(Crc16.INIT, Crc16.crc(new byte[0]));
    }
}
