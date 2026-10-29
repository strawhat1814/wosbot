package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.frostguard.engine.helper.CharacterSwitchHelper.CharacterVerifyResult;
import dev.frostguard.engine.helper.CharacterSwitchHelper.LiveCharacter;

class CharacterSwitchHelperIdentityTest {

    @Test
    void nameOnlyRejectsGarbageOcrThatDoesNotContainConfiguredName() {
        assertFalse(CharacterSwitchHelper.matchesConfiguredIdentity(
                "Strawhat", null, "nn", null));
        assertFalse(CharacterSwitchHelper.matchesConfiguredIdentity(
                "Strawhat", "", "ee", "null"));
        assertFalse(CharacterSwitchHelper.matchesConfiguredIdentity(
                "Strawhat", null, "KUMNami", "279277402"));
    }

    @Test
    void nameOnlyAcceptsAlliancePrefixedLiveName() {
        assertTrue(CharacterSwitchHelper.matchesConfiguredIdentity(
                "Strawhat", null, "LFGStrawHat", "1275459670"));
        assertTrue(CharacterSwitchHelper.matchesConfiguredIdentity(
                "Nami", null, "KUMNami", "279277402"));
    }

    @Test
    void unsetIdMustNotCountAsMatch() {
        // Regression: blank wantId used to make idOk=true and skip the name check.
        assertFalse(CharacterSwitchHelper.matchesConfiguredIdentity(
                "Strawhat", "", "nn", null));
    }

    @Test
    void idOnlyMatchesExactId() {
        assertTrue(CharacterSwitchHelper.matchesConfiguredIdentity(
                null, "1275459670", "whatever", "1275459670"));
        assertFalse(CharacterSwitchHelper.matchesConfiguredIdentity(
                null, "1275459670", "LFGStrawHat", "279277402"));
    }

    @Test
    void bothConfiguredAcceptEitherEvidence() {
        assertTrue(CharacterSwitchHelper.matchesConfiguredIdentity(
                "Strawhat", "1275459670", "LFGStrawHat", null));
        assertTrue(CharacterSwitchHelper.matchesConfiguredIdentity(
                "Strawhat", "1275459670", "nn", "1275459670"));
        assertFalse(CharacterSwitchHelper.matchesConfiguredIdentity(
                "Strawhat", "1275459670", "KUMNami", "279277402"));
    }

    @Test
    void readyPathClassifiesJunkOcrAsInconclusiveNotMismatch() {
        assertEquals(CharacterVerifyResult.INCONCLUSIVE,
                CharacterSwitchHelper.classifyLiveAgainstConfigured(
                        "Nami", null, new LiveCharacter("nt", null)));
        assertEquals(CharacterVerifyResult.INCONCLUSIVE,
                CharacterSwitchHelper.classifyLiveAgainstConfigured(
                        "Strawhat", null, new LiveCharacter("Oe", null)));
        assertEquals(CharacterVerifyResult.INCONCLUSIVE,
                CharacterSwitchHelper.classifyLiveAgainstConfigured(
                        "Strawhat", null, null));
    }

    @Test
    void readyPathClassifiesAlliancePrefixedNameAsMatch() {
        assertEquals(CharacterVerifyResult.MATCH,
                CharacterSwitchHelper.classifyLiveAgainstConfigured(
                        "Nami", null, new LiveCharacter("KUMNami", "279277402")));
        assertEquals(CharacterVerifyResult.MATCH,
                CharacterSwitchHelper.classifyLiveAgainstConfigured(
                        "Strawhat", null, new LiveCharacter("LFGStrawHat", "275459670")));
    }

    @Test
    void readyPathClassifiesSolidWrongCharacterAsMismatch() {
        assertEquals(CharacterVerifyResult.MISMATCH,
                CharacterSwitchHelper.classifyLiveAgainstConfigured(
                        "Strawhat", null, new LiveCharacter("KUMNami", "279277402")));
    }
}
