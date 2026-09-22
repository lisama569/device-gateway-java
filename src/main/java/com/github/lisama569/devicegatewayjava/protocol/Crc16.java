package com.github.lisama569.devicegatewayjava.protocol;

/** CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF, no reflect, no xorout). */
public final class Crc16 {
    public static final int INIT = 0xFFFF;

    private Crc16() {
    }

    public static int crc(byte[] data) {
        return crc(data, 0, data == null ? 0 : data.length);
    }

    public static int crc(byte[] data, int offset, int length) {
        if (data == null || length <= 0) {
            return INIT;
        }
        int crc = INIT;
        int end = offset + length;
        for (int i = offset; i < end; i++) {
            crc ^= (data[i] & 0xFF) << 8;
            for (int b = 0; b < 8; b++) {
                if ((crc & 0x8000) != 0) {
                    crc = ((crc << 1) ^ 0x1021) & 0xFFFF;
                } else {
                    crc = (crc << 1) & 0xFFFF;
                }
            }
        }
        return crc & 0xFFFF;
    }
}
