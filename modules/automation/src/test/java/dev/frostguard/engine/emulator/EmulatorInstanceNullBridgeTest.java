package dev.frostguard.engine.emulator;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class EmulatorInstanceNullBridgeTest {

    @Test
    void nullBridgeScanDoesNotNpeAndRequestsRecoveryOnce() {
        AtomicInteger recoverCalls = new AtomicInteger();
        NullBridgeHarness harness = new NullBridgeHarness();
        harness.circuitBreaker().overrideDrainMillisForTest(0L);
        harness.circuitBreaker().configure(
                () -> {},
                () -> {},
                java.util.Collections::emptyList,
                (indexes, onDeviceProbed) -> {
                    recoverCalls.incrementAndGet();
                    java.util.Map<String, Boolean> ok = new java.util.LinkedHashMap<>();
                    for (String idx : indexes) {
                        ok.put(idx, true);
                        onDeviceProbed.accept(idx, true);
                    }
                    if (ok.isEmpty()) {
                        ok.put("0", true);
                        onDeviceProbed.accept("0", true);
                    }
                    return new AdbRecoveryReport(ok);
                });

        assertNull(assertDoesNotThrow(() -> harness.scan("127.0.0.1:5555")));
        assertEquals(1, recoverCalls.get());

        // Still null after a no-op recover — must not NPE on the next scan either.
        assertNull(assertDoesNotThrow(() -> harness.scan("127.0.0.1:5555")));
        assertTrue(recoverCalls.get() >= 1);
    }

    private static final class NullBridgeHarness extends EmulatorInstance {
        NullBridgeHarness() {
            super("unused");
            this.bridge = null;
        }

        @Override
        protected void initBridge() {
            // Skip real ddmlib init so unit tests do not touch host ADB.
        }

        @Override
        protected String getDeviceSerial(String idx) {
            return "127.0.0.1:5555";
        }

        @Override
        public void launchEmulator(String idx) {
        }

        @Override
        public void closeEmulator(String idx) {
        }

        @Override
        public boolean isRunning(String idx) {
            return true;
        }
    }
}
