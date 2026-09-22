package com.github.lisama569.devicegatewayjava.protocol;

/** CRC-16/CCITT-FALSE（poly 0x1021, init 0xFFFF）。 */
public final class Crc16 {
    private Crc16() {}

    public static int checksum(byte[] data, int offset, int length) {
        int crc = 0xFFFF;
        for (int i = 0; i < length; i++) {
            crc ^= (data[offset + i] & 0xFF) << 8;
            for (int b = 0; b < 8; b++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
                crc &= 0xFFFF;
            }
        }
        return crc;
    }
}
