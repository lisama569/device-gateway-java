package com.github.lisama569.devicegatewayjava.state;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/** deviceId -> DeviceState；设备重连复用同一状态，跨连接结论一致。 */
public final class DeviceStateStore {

    private final Map<String, DeviceState> states = new ConcurrentHashMap<>();
    private final long window;

    public DeviceStateStore(long window) {
        this.window = window;
    }

    public DeviceState get(String deviceId) {
        return states.computeIfAbsent(deviceId, id -> new DeviceState(id, window));
    }

    public int trackedDevices() {
        return states.size();
    }
}
