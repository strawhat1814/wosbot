package dev.frostguard.tasks.pets;

import java.time.LocalDateTime;
import java.util.List;

import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;

/**
 * Pure decisions for Pet Adventure map pins and the start overlay.
 *
 * <p>An idle pin is a coloured chest without a countdown. An occupied pin
 * still matches the red/purple/blue rim crops, but it has a dark timer
 * pill under the marker. After Start, the accepted screen is the overlay
 * whose subtitle is {@code In Adventure}.</p>
 */
final class PetAdventureDecisions {

    static final double TEMPLATE_THRESHOLD = 90.0;

    /** Orange clock on the occupied-pin pill; second pin can sit a bit below 90. */
    static final double TIMER_THRESHOLD = 80.0;

    /**
     * A later scan of the same pin can land a few pixels away. Distinct
     * adventure pins on the map are farther apart than this.
     */
    static final int PIN_MATCH_DISTANCE = 48;

    static final int UNKNOWN_RETRY_MINUTES = 15;

    static final int COMPLETION_CHECK_HOURS = 2;

    /** Horizontal slack from the chest centre to its countdown pill. */
    static final int TIMER_MAX_DX = 90;

    /** Countdown sits under the pin, not on the chest art. */
    static final int TIMER_MIN_DY = 40;

    static final int TIMER_MAX_DY = 200;

    private PetAdventureDecisions() {
    }

    static boolean inAdventureOverlay(ImageSearchResultData overlay) {
        return overlay != null && overlay.isFound();
    }

    /** Stamina is spent only after the In Adventure overlay confirms the start. */
    static boolean recordsStaminaSpend(ImageSearchResultData overlay) {
        return inAdventureOverlay(overlay);
    }

    /**
     * A confirmed start waits for completion. Any visit that sent no confirmed
     * start, but did hit an unverified pin, retries shortly. The daily reset
     * is only for the no-attempts screen.
     */
    static PetAdventureNextVisit nextVisit(int confirmedStarts, boolean unknownPin, boolean attemptsExhausted) {
        if (attemptsExhausted) {
            return PetAdventureNextVisit.ATTEMPTS_RESET;
        }
        if (confirmedStarts > 0) {
            return PetAdventureNextVisit.COMPLETION_CHECK;
        }
        if (unknownPin) {
            return PetAdventureNextVisit.UNKNOWN_RETRY;
        }
        return PetAdventureNextVisit.COMPLETION_CHECK;
    }

    static LocalDateTime unknownRetryAt(LocalDateTime now) {
        return now.plusMinutes(UNKNOWN_RETRY_MINUTES);
    }

    static LocalDateTime completionCheckAt(LocalDateTime now) {
        return now.plusHours(COMPLETION_CHECK_HOURS);
    }

    static boolean alreadyAttempted(PointData chest, List<PointData> attempted) {
        if (chest == null || attempted == null) {
            return false;
        }
        for (PointData prior : attempted) {
            if (samePin(chest, prior)) {
                return true;
            }
        }
        return false;
    }

    static boolean samePin(PointData left, PointData right) {
        if (left == null || right == null) {
            return false;
        }
        return Math.abs(left.getX() - right.getX()) <= PIN_MATCH_DISTANCE
                && Math.abs(left.getY() - right.getY()) <= PIN_MATCH_DISTANCE;
    }

    static boolean occupiedByTimer(PointData chest, List<ImageSearchResultData> timers) {
        if (chest == null || timers == null) {
            return false;
        }
        for (ImageSearchResultData timer : timers) {
            if (timer == null || !timer.isFound()) {
                continue;
            }
            int dx = Math.abs(timer.getHitX() - chest.getX());
            int dy = timer.getHitY() - chest.getY();
            if (dx <= TIMER_MAX_DX && dy >= TIMER_MIN_DY && dy <= TIMER_MAX_DY) {
                return true;
            }
        }
        return false;
    }

    enum PetAdventureNextVisit {
        /** At least one start was confirmed, or no idle pin was left to try. */
        COMPLETION_CHECK,
        /** No confirmed start. The visit retries soon and does not spend stamina. */
        UNKNOWN_RETRY,
        /** The no-attempts control was visible. */
        ATTEMPTS_RESET
    }
}
