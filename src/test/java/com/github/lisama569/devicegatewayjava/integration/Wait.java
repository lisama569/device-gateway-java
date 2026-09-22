package com.github.lisama569.devicegatewayjava.integration;

import java.util.function.BooleanSupplier;

/** Tiny real-time wait helper: deterministically short, used only to let the
 *  server's real I/O threads observe bytes the test just wrote. */
final class Wait {

    private Wait() {
    }

    static boolean until(long timeoutMillis, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(5);
        }
        return condition.getAsBoolean();
    }
}
