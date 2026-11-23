package dev.frostguard.engine.emulator;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmulatorInstanceEscalateRecoveryTest {

    @Test
    void escalateQuitsLaunchesAndSucceedsWhenProbePassesAfterLaunch() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        AtomicInteger launches = new AtomicInteger();
        AtomicBoolean ready = new AtomicBoolean(false);
        EscalateHarness harness = new EscalateHarness(closes, launches, ready);

        assertTrue(harness.escalateEmulatorAndAwaitAdb("3", 0L, 20L, 500L));
        assertEquals(1, closes.get());
        assertEquals(1, launches.get());
        assertTrue(ready.get());
    }

    private static final class EscalateHarness extends EmulatorInstance {
        private final AtomicInteger closes;
        private final AtomicInteger launches;
        private final AtomicBoolean readyAfterLaunch;

        EscalateHarness(AtomicInteger closes, AtomicInteger launches, AtomicBoolean readyAfterLaunch) {
            super("unused");
            this.closes = closes;
            this.launches = launches;
            this.readyAfterLaunch = readyAfterLaunch;
        }

        @Override
        protected void initBridge() {
            // Skip real ddmlib init.
        }

        @Override
        protected String getDeviceSerial(String idx) {
            return "127.0.0.1:5561";
        }

        @Override
        public void launchEmulator(String idx) {
            launches.incrementAndGet();
            readyAfterLaunch.set(true);
        }

        @Override
        public void closeEmulator(String idx) {
            closes.incrementAndGet();
            readyAfterLaunch.set(false);
        }

        @Override
        public boolean isRunning(String idx) {
            return true;
        }

        @Override
        boolean probe(String idx) {
            return readyAfterLaunch.get();
        }
    }
}
