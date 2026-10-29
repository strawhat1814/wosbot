package dev.frostguard.tasks.pets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class PetSkillsCooldownPolicyTest {

    @Test
    void acceptsPlausibleOcrCooldown() {
        assertEquals(
                Duration.ofHours(21).plusMinutes(24),
                PetSkillsRoutine.resolveTrackedCooldown(
                        Duration.ofHours(21).plusMinutes(24),
                        false));
    }

    @Test
    void rejectsSubMinuteOcrWhileSkillIsAlreadyOnCooldown() {
        assertNull(PetSkillsRoutine.resolveTrackedCooldown(Duration.ofSeconds(9), false));
        assertNull(PetSkillsRoutine.resolveTrackedCooldown(null, false));
    }

    @Test
    void usesFiveMinuteFallbackAfterUseWhenOcrIsMissingOrImplausible() {
        assertEquals(
                Duration.ofMinutes(5),
                PetSkillsRoutine.resolveTrackedCooldown(null, true));
        assertEquals(
                Duration.ofMinutes(5),
                PetSkillsRoutine.resolveTrackedCooldown(Duration.ofSeconds(9), true));
    }
}
