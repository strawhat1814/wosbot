package dev.frostguard.engine.sharedlogin;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import dev.frostguard.api.domain.AccountDescriptor;

/**
 * Pure role assignment for the shared-login handshake.
 * Live character on the connected device decides who switches and who waits.
 */
final class SharedLoginHandshakeDecision {

    enum Role {
        SWITCHER,
        WAITER
    }

    record Assignment(
            long switcherProfileId,
            long waiterProfileId,
            String switchTargetName,
            String reason) {
    }

    private SharedLoginHandshakeDecision() {
    }

    static Assignment assign(
            SharedLoginPair pair,
            long connectedProfileId,
            String liveCharacterName) {
        AccountDescriptor connected = pair.self(connectedProfileId);
        AccountDescriptor other = pair.peerOf(connectedProfileId);

        boolean liveIsConnectedOwn = nameMatches(liveCharacterName, connected.getCharacterName());
        boolean liveIsPeerOwn = nameMatches(liveCharacterName, other.getCharacterName());

        if (liveIsConnectedOwn && !liveIsPeerOwn) {
            // Live = connected profile's character → the peer must switch to its own character.
            return new Assignment(
                    other.getId(),
                    connected.getId(),
                    requiredName(other),
                    "Live character matches " + connected.getName()
                            + "; " + other.getName() + " switches to " + requiredName(other));
        }
        if (liveIsPeerOwn && !liveIsConnectedOwn) {
            // Live = peer's character on the connected device → connected profile switches to its own.
            return new Assignment(
                    connected.getId(),
                    other.getId(),
                    requiredName(connected),
                    "Live character matches " + other.getName()
                            + "; " + connected.getName() + " switches to " + requiredName(connected));
        }
        return null;
    }

    public static boolean nameMatches(String ocrText, String expected) {
        if (ocrText == null || ocrText.isBlank() || expected == null || expected.isBlank()) {
            return false;
        }
        String a = ocrText.trim().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        String b = expected.trim().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        return Pattern.compile(Pattern.quote(b), Pattern.CASE_INSENSITIVE).matcher(a).find()
                || Objects.equals(a, b);
    }

    private static String requiredName(AccountDescriptor profile) {
        String name = profile.getCharacterName();
        if (name == null || name.isBlank()) {
            throw new IllegalStateException("Profile " + profile.getName() + " has no character name");
        }
        return name.trim();
    }
}
