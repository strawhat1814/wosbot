package dev.frostguard.engine.emulator;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import dev.frostguard.engine.error.ADBConnectionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmulatorInstanceCooldownRecoveryTest {

    @Test
    void withRetriesDoesNotCloseOrRelaunchEmulatorWhileBreakerInCooldown() {
        AtomicInteger closes = new AtomicInteger();
        AtomicInteger launches = new AtomicInteger();
        CooldownHarness harness = new CooldownHarness(closes, launches);
        harness.circuitBreaker().overrideDrainMillisForTest(0L);
        harness.circuitBreaker().configure(
                () -> {},
                () -> {},
                java.util.Collections::emptyList,
                (indexes, onDeviceProbed) -> AdbRecoveryReport.empty());
        assertTrue(!harness.circuitBreaker().recoverNow("force cooldown"));
        assertEquals(AdbCircuitBreaker.State.COOLDOWN, harness.circuitBreaker().state());

        ADBConnectionException thrown = assertThrows(
                ADBConnectionException.class,
                () -> harness.invokeWithRetries("0", "installed"));

        assertTrue(thrown.getMessage().contains("skipping emulator reboot"));
        assertEquals(0, closes.get(), "must not close LDPlayer during ADB cooldown");
        assertEquals(0, launches.get(), "must not relaunch LDPlayer during ADB cooldown");
    }

    private static final class CooldownHarness extends EmulatorInstance {
        private final AtomicInteger closes;
        private final AtomicInteger launches;

        CooldownHarness(AtomicInteger closes, AtomicInteger launches) {
            super("unused");
            this.closes = closes;
            this.launches = launches;
            this.bridge = null;
        }

        @Override
        protected void initBridge() {
        }

        @Override
        protected String getDeviceSerial(String idx) {
            return "127.0.0.1:5555";
        }

        @Override
        public void launchEmulator(String idx) {
            launches.incrementAndGet();
        }

        @Override
        public void closeEmulator(String idx) {
            closes.incrementAndGet();
        }

        @Override
        public boolean isRunning(String idx) {
            return true;
        }

        void invokeWithRetries(String idx, String tag) {
            withRetries(idx, device -> Boolean.TRUE, tag);
        }
    }
}
