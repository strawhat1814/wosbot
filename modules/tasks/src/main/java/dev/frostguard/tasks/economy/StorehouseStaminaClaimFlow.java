package dev.frostguard.tasks.economy;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

import dev.frostguard.api.domain.ImageSearchResultData;

final class StorehouseStaminaClaimFlow {

    interface Actions {
        boolean isRewardDialogVisible();

        ImageSearchResultData findClaimButton();

        boolean isTooltipVisible();

        void pressBack();

        void waitAfterBack();

        void waitForNextPoll();
    }

    record Result(ImageSearchResultData claimButton, boolean rewardDialogSeen) {
    }

    private StorehouseStaminaClaimFlow() {
    }

    /**
     * Waits for the Claim control after the stamina can was tapped. Tooltip is
     * dismissed once when confirmed. Claim is accepted even when the reward-title
     * template misses — live dialogs sometimes open without a title match.
     */
    static Result awaitClaimButton(Actions actions, Duration timeout, LongSupplier nanoTime) {
        Objects.requireNonNull(actions, "actions");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(nanoTime, "nanoTime");

        long deadline = nanoTime.getAsLong() + timeout.toNanos();
        boolean tooltipDismissed = false;
        boolean rewardDialogSeen = false;
        while (nanoTime.getAsLong() < deadline) {
            if (!tooltipDismissed && actions.isTooltipVisible()) {
                actions.pressBack();
                tooltipDismissed = true;
                actions.waitAfterBack();
                continue;
            }

            if (actions.isRewardDialogVisible()) {
                rewardDialogSeen = true;
            }

            ImageSearchResultData claimButton = actions.findClaimButton();
            if (claimButton != null && claimButton.isFound()) {
                return new Result(claimButton, rewardDialogSeen);
            }

            actions.waitForNextPoll();
        }
        return new Result(null, rewardDialogSeen);
    }
}
