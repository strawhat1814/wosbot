package dev.frostguard.engine.sharedlogin;

import java.util.Optional;
import java.util.function.Function;

import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.service.ProfileService;

/**
 * Resolves a mutual shared-login pair and opens (or reuses) a handshake session
 * so mid-day reconnects can reuse the same Connect/Reconnect coordination as startup.
 */
public final class SharedLoginRecovery {

    private SharedLoginRecovery() {
    }

    public static Optional<SharedLoginPair> resolvePair(AccountDescriptor profile) {
        return resolvePair(profile, id -> ProfileService.obtain().fetchAllAccounts().stream()
                .filter(account -> id.equals(account.getId()))
                .findFirst()
                .orElse(null));
    }

    public static Optional<SharedLoginPair> resolvePair(
            AccountDescriptor profile,
            Function<Long, AccountDescriptor> lookup) {
        return SharedLoginPair.resolve(profile, lookup);
    }

    /**
     * Ensures an active handshake session exists for the pair.
     *
     * @return true when the profile is paired and a session is ready
     */
    public static boolean begin(AccountDescriptor profile) {
        Optional<SharedLoginPair> pair = resolvePair(profile);
        if (pair.isEmpty()) {
            return false;
        }
        SharedLoginHandshakeCoordinator.shared().join(pair.get());
        return true;
    }

    public static SharedLoginHandshakeSession begin(SharedLoginPair pair) {
        return SharedLoginHandshakeCoordinator.shared().join(pair);
    }
}
