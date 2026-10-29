package dev.frostguard.engine.schedule.preempt;

import java.time.Clock;

import dev.frostguard.api.configs.BearTrapMode;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.schedule.BearTrapVisualProtection;

/**
 * Detects the Bear Trap running indicator and always registers temporary
 * rally protection. It preempts into Bear participation only when the
 * profile explicitly enables icon fallback.
 */
public class BearTrapPreemptionRule implements PreemptionRule {

    private final Clock clock;
    private volatile TpDailyTaskEnum resolvedTask = TpDailyTaskEnum.BEAR_TRAP;

    public BearTrapPreemptionRule() {
        this(Clock.systemUTC());
    }

    BearTrapPreemptionRule(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean shouldPreempt(EmulatorController controller,
                                 AccountDescriptor profile,
                                 RawImageData screenshot) {
        if (profile == null || profile.getEmulatorNumber() == null) {
            return false;
        }
        if (BearTrapVisualProtection.releaseAt(profile.getId(), clock).isPresent()) {
            return false;
        }
        try {
            ImageSearchResultData match = controller.locatePattern(
                    profile.getEmulatorNumber(), screenshot,
                    TemplatesEnum.BEAR_HUNT_IS_RUNNING, 90);
            if (!match.isFound()) {
                return false;
            }
            BearTrapVisualProtection.markDetected(profile.getId(), clock);
            if (!iconMayStartParticipation(profile)) {
                return false;
            }
            resolvedTask = participationTaskFor(profile);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean iconMayStartParticipation(AccountDescriptor profile) {
        return Boolean.TRUE.equals(profile.getConfig(ConfigurationKeyEnum.BEAR_TRAP_EVENT_BOOL, Boolean.class))
                && Boolean.TRUE.equals(profile.getConfig(
                        ConfigurationKeyEnum.BEAR_TRAP_ICON_PARTICIPATION_FALLBACK_BOOL,
                        Boolean.class));
    }

    @Override
    public TpDailyTaskEnum getTaskToExecute() {
        return resolvedTask;
    }

    /**
     * Resolve the participation task for the profile's Bear mode.
     * Mode B starts the Own Rally ticket (which owns prep) instead of the monolith.
     */
    public static TpDailyTaskEnum participationTaskFor(AccountDescriptor profile) {
        if (profile != null && BearTrapMode.isModeB(
                profile.getConfig(ConfigurationKeyEnum.BEAR_TRAP_MODE_STRING, String.class))) {
            return TpDailyTaskEnum.BEAR_TRAP_OWN_RALLY;
        }
        return TpDailyTaskEnum.BEAR_TRAP;
    }

    @Override
    public String getRuleName() { return "BearTrapActive"; }
}
