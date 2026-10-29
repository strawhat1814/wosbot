package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.frostguard.engine.service.StaminaService;

class StaminaHelperHudReadTest {

    @Test
    void hudReadPersistsTheBadgeValue() {
        StaminaService service = StaminaService.getServices();
        RecordingHud ui = new RecordingHud(224);
        StaminaHelper helper = new StaminaHelper(service, 9401L, ui);

        helper.updateStaminaFromHud();

        assertEquals(1, ui.reads);
        assertEquals(5, ui.attempts);
        assertEquals(200L, ui.retryMs);
        assertEquals(224, service.findCurrentStamina(9401L).orElseThrow());
    }

    @Test
    void unreadableBadgeLeavesTheStoredValueUntouched() {
        StaminaService service = StaminaService.getServices();
        service.setStamina(9402L, 80);
        StaminaHelper helper = new StaminaHelper(service, 9402L, new RecordingHud(null));

        helper.updateStaminaFromHud();

        assertEquals(80, service.findCurrentStamina(9402L).orElseThrow());
    }

    @Test
    void readExceptionLeavesAnUnknownAccountUnknown() {
        StaminaService service = StaminaService.getServices();
        RecordingHud ui = new RecordingHud(null);
        ui.failRead = true;
        StaminaHelper helper = new StaminaHelper(service, 9403L, ui);

        helper.updateStaminaFromHud();

        assertTrue(service.findCurrentStamina(9403L).isEmpty());
    }

    private static final class RecordingHud implements StaminaHelper.HudStaminaUi {
        private final Integer reading;
        private boolean failRead;
        private int reads;
        private int attempts;
        private long retryMs;

        private RecordingHud(Integer reading) {
            this.reading = reading;
        }

        @Override
        public Integer readStamina(int attempts, long retryMs) {
            this.reads++;
            this.attempts = attempts;
            this.retryMs = retryMs;
            if (failRead) {
                throw new IllegalStateException("simulated OCR failure");
            }
            return reading;
        }
    }
}
