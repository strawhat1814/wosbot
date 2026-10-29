package dev.frostguard.engine.sharedlogin;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Process-wide registry of active shared-login handshake sessions.
 */
public final class SharedLoginHandshakeCoordinator {

    private static final SharedLoginHandshakeCoordinator INSTANCE = new SharedLoginHandshakeCoordinator();

    private final ConcurrentMap<String, SharedLoginHandshakeSession> sessionsByPair = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, String> pairKeyByProfile = new ConcurrentHashMap<>();

    private SharedLoginHandshakeCoordinator() {
    }

    public static SharedLoginHandshakeCoordinator shared() {
        return INSTANCE;
    }

    /** Test-only reset. */
    public void clear() {
        sessionsByPair.clear();
        pairKeyByProfile.clear();
    }

    public SharedLoginHandshakeSession join(SharedLoginPair pair) {
        String key = pair.sessionKey();
        SharedLoginHandshakeSession session = sessionsByPair.compute(key, (ignored, existing) -> {
            if (existing != null && !existing.isTerminal()) {
                return existing;
            }
            return new SharedLoginHandshakeSession(pair);
        });
        pairKeyByProfile.put(pair.first().getId(), key);
        pairKeyByProfile.put(pair.second().getId(), key);
        return session;
    }

    public Optional<SharedLoginHandshakeSession> sessionFor(long profileId) {
        String key = pairKeyByProfile.get(profileId);
        if (key == null) {
            return Optional.empty();
        }
        SharedLoginHandshakeSession session = sessionsByPair.get(key);
        if (session == null || !session.contains(profileId)) {
            return Optional.empty();
        }
        return Optional.of(session);
    }

    public boolean isHandshakeActive(long profileId) {
        return sessionFor(profileId).map(session -> !session.isTerminal()).orElse(false);
    }

    public void finish(SharedLoginHandshakeSession session) {
        if (session == null) {
            return;
        }
        String key = session.pair().sessionKey();
        sessionsByPair.remove(key, session);
        pairKeyByProfile.remove(session.pair().first().getId(), key);
        pairKeyByProfile.remove(session.pair().second().getId(), key);
    }
}
