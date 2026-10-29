package dev.frostguard.engine.sharedlogin;

/**
 * Local screen observation reported by one side of a shared-login handshake.
 */
public record SharedLoginObservation(
        boolean reconnectVisible,
        boolean homeVisible,
        String liveCharacterName) {

    public boolean connected() {
        return homeVisible && !reconnectVisible;
    }

    /** Home or Reconnect is visible — safe to use for role assignment. */
    public boolean conclusive() {
        return reconnectVisible || homeVisible;
    }
}
