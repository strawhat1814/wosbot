package dev.frostguard.engine.sharedlogin;

/**
 * Next action for one profile participating in a shared-login handshake.
 */
public record SharedLoginDirective(
        Action action,
        String targetCharacterName,
        String detail) {

    public enum Action {
        WAIT,
        PREP_RECONNECT,
        PREPARE_SWITCH_AND_COMMIT,
        ARM_RECONNECT_AND_COMMIT,
        VERIFY,
        DONE,
        FAILED
    }

    public static SharedLoginDirective wait(String detail) {
        return new SharedLoginDirective(Action.WAIT, null, detail);
    }

    public static SharedLoginDirective prepReconnect(String detail) {
        return new SharedLoginDirective(Action.PREP_RECONNECT, null, detail);
    }

    public static SharedLoginDirective prepareSwitch(String targetCharacterName, String detail) {
        return new SharedLoginDirective(Action.PREPARE_SWITCH_AND_COMMIT, targetCharacterName, detail);
    }

    public static SharedLoginDirective armReconnect(String detail) {
        return new SharedLoginDirective(Action.ARM_RECONNECT_AND_COMMIT, null, detail);
    }

    public static SharedLoginDirective verify(String detail) {
        return new SharedLoginDirective(Action.VERIFY, null, detail);
    }

    public static SharedLoginDirective done(String detail) {
        return new SharedLoginDirective(Action.DONE, null, detail);
    }

    public static SharedLoginDirective failed(String detail) {
        return new SharedLoginDirective(Action.FAILED, null, detail);
    }
}
