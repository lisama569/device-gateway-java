package com.github.lisama569.devicegatewayjava.auth;

import com.github.lisama569.devicegatewayjava.error.GatewayErrorCode;
import com.github.lisama569.devicegatewayjava.error.GatewayException;

import java.nio.charset.StandardCharsets;

/**
 * LOGIN 帧负载：deviceIdLen(2,BE) UTF8(deviceId) secretLen(2,BE) UTF8(secret)。
 * secret 只存在于对象生命周期内，日志与错误帧绝不打印。
 */
public record LoginPayload(String deviceId, String secret) {

    public static LoginPayload parse(byte[] p) {
        if (p == null || p.length < 4) {
            throw new GatewayException(GatewayErrorCode.MALFORMED_PAYLOAD, "login payload too short");
        }
        int idLen = ((p[0] & 0xFF) << 8) | (p[1] & 0xFF);
        if (p.length < 2 + idLen + 2) {
            throw new GatewayException(GatewayErrorCode.MALFORMED_PAYLOAD, "truncated device id");
        }
        String deviceId = new String(p, 2, idLen, StandardCharsets.UTF_8);
        int secretOff = 2 + idLen;
        int secretLen = ((p[secretOff] & 0xFF) << 8) | (p[secretOff + 1] & 0xFF);
        if (p.length != secretOff + 2 + secretLen) {
            throw new GatewayException(GatewayErrorCode.MALFORMED_PAYLOAD, "secret length mismatch");
        }
        String secret = new String(p, secretOff + 2, secretLen, StandardCharsets.UTF_8);
        return new LoginPayload(deviceId, secret);
    }

    public byte[] encode() {
        byte[] id = deviceId.getBytes(StandardCharsets.UTF_8);
        byte[] sec = secret.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[2 + id.length + 2 + sec.length];
        out[0] = (byte) (id.length >>> 8);
        out[1] = (byte) id.length;
        System.arraycopy(id, 0, out, 2, id.length);
        int o = 2 + id.length;
        out[o] = (byte) (sec.length >>> 8);
        out[o + 1] = (byte) sec.length;
        System.arraycopy(sec, 0, out, o + 2, sec.length);
        return out;
    }
}
