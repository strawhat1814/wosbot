package dev.frostguard.engine.schedule.preempt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;

class BearTrapPreemptionRuleTest {

    @Test
    void iconStartsParticipationOnlyWhenAutomationAndFallbackAreEnabled() {
        AccountDescriptor profile = new AccountDescriptor(1L);
        profile.setConfig(ConfigurationKeyEnum.BEAR_TRAP_EVENT_BOOL, true);

        assertFalse(BearTrapPreemptionRule.iconMayStartParticipation(profile));

        profile.setConfig(ConfigurationKeyEnum.BEAR_TRAP_ICON_PARTICIPATION_FALLBACK_BOOL, true);
        assertTrue(BearTrapPreemptionRule.iconMayStartParticipation(profile));

        profile.setConfig(ConfigurationKeyEnum.BEAR_TRAP_EVENT_BOOL, false);
        assertFalse(BearTrapPreemptionRule.iconMayStartParticipation(profile));
    }

    @Test
    void modeBIconParticipationStartsOwnRallyTicket() {
        AccountDescriptor profile = new AccountDescriptor(1L);
        profile.setConfig(ConfigurationKeyEnum.BEAR_TRAP_MODE_STRING, "B");
        assertEquals(TpDailyTaskEnum.BEAR_TRAP_OWN_RALLY,
                BearTrapPreemptionRule.participationTaskFor(profile));

        profile.setConfig(ConfigurationKeyEnum.BEAR_TRAP_MODE_STRING, "A");
        assertEquals(TpDailyTaskEnum.BEAR_TRAP,
                BearTrapPreemptionRule.participationTaskFor(profile));
    }
}
