package dev.frostguard.engine.sharedlogin;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import dev.frostguard.api.domain.AccountDescriptor;

/**
 * Validated pair of profiles that share one game login on two emulators.
 * Each profile owns one character identity; the peer pointer must be mutual.
 */
public final class SharedLoginPair {

    private final AccountDescriptor first;
    private final AccountDescriptor second;

    private SharedLoginPair(AccountDescriptor first, AccountDescriptor second) {
        this.first = first;
        this.second = second;
    }

    public AccountDescriptor first() {
        return first;
    }

    public AccountDescriptor second() {
        return second;
    }

    public AccountDescriptor peerOf(long profileId) {
        if (Objects.equals(first.getId(), profileId)) {
            return second;
        }
        if (Objects.equals(second.getId(), profileId)) {
            return first;
        }
        throw new IllegalArgumentException("Profile " + profileId + " is not in this pair");
    }

    public AccountDescriptor self(long profileId) {
        if (Objects.equals(first.getId(), profileId)) {
            return first;
        }
        if (Objects.equals(second.getId(), profileId)) {
            return second;
        }
        throw new IllegalArgumentException("Profile " + profileId + " is not in this pair");
    }

    public String sessionKey() {
        long a = first.getId();
        long b = second.getId();
        return Math.min(a, b) + ":" + Math.max(a, b);
    }

    public boolean contains(long profileId) {
        return Objects.equals(first.getId(), profileId) || Objects.equals(second.getId(), profileId);
    }

    /** True when OCR text matches either profile's configured character name. */
    public boolean matchesEitherCharacterName(String liveName) {
        return SharedLoginHandshakeDecision.nameMatches(liveName, first.getCharacterName())
                || SharedLoginHandshakeDecision.nameMatches(liveName, second.getCharacterName());
    }

    public static Optional<SharedLoginPair> resolve(
            AccountDescriptor self,
            Function<Long, AccountDescriptor> lookup) {
        if (self == null || self.getId() == null) {
            return Optional.empty();
        }
        Long peerId = self.getSharedLoginPeerProfileId();
        if (peerId == null || peerId <= 0L) {
            return Optional.empty();
        }
        AccountDescriptor peer = lookup.apply(peerId);
        if (peer == null || peer.getId() == null) {
            return Optional.empty();
        }
        if (!Objects.equals(peer.getSharedLoginPeerProfileId(), self.getId())) {
            return Optional.empty();
        }
        if (!Boolean.TRUE.equals(self.getEnabled()) || !Boolean.TRUE.equals(peer.getEnabled())) {
            return Optional.empty();
        }
        if (sameEmulator(self, peer)) {
            return Optional.empty();
        }
        if (!hasCharacterIdentity(self) || !hasCharacterIdentity(peer)) {
            return Optional.empty();
        }
        AccountDescriptor first = self.getId() < peer.getId() ? self : peer;
        AccountDescriptor second = self.getId() < peer.getId() ? peer : self;
        return Optional.of(new SharedLoginPair(first, second));
    }

    private static boolean sameEmulator(AccountDescriptor a, AccountDescriptor b) {
        String left = a.getEmulatorNumber() == null ? "" : a.getEmulatorNumber().trim();
        String right = b.getEmulatorNumber() == null ? "" : b.getEmulatorNumber().trim();
        return !left.isEmpty() && left.equals(right);
    }

    private static boolean hasCharacterIdentity(AccountDescriptor profile) {
        String name = profile.getCharacterName();
        String id = profile.getCharacterId();
        return (name != null && !name.isBlank()) || (id != null && !id.isBlank());
    }
}
