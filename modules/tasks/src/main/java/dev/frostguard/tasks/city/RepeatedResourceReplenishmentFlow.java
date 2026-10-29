package dev.frostguard.tasks.city;

import dev.frostguard.api.domain.PointData;

final class RepeatedResourceReplenishmentFlow {

    enum Outcome {
        READY,
        REPLENISH_BUTTON_MISSING,
        LIMIT_REACHED
    }

    record Result(Outcome outcome, int replenishedResources) {

        boolean ready() {
            return outcome == Outcome.READY;
        }
    }

    interface Ui {

        PointData findReplenishAll();

        PointData findObtain();

        void openObtain(PointData point);

        void replenishAndConfirm(PointData point);

        /**
         * Called between Replenish All polls after Obtain opens. Default is a no-op;
         * Growth waits for the Obtain-more sheet animation.
         */
        default void waitAfterObtainOpened(int attempt) {
        }
    }

    private static final int REPLENISH_AFTER_OBTAIN_POLLS = 3;

    private RepeatedResourceReplenishmentFlow() {
    }

    static Result run(Ui ui, int maxReplenishments) {
        int replenishedResources = 0;

        while (replenishedResources < maxReplenishments) {
            PointData replenishAll = ui.findReplenishAll();
            if (replenishAll == null) {
                PointData obtain = ui.findObtain();
                if (obtain == null) {
                    return new Result(Outcome.READY, replenishedResources);
                }

                ui.openObtain(obtain);
                replenishAll = findReplenishAllAfterObtain(ui);
                if (replenishAll == null) {
                    return new Result(Outcome.REPLENISH_BUTTON_MISSING, replenishedResources);
                }
            }

            ui.replenishAndConfirm(replenishAll);
            replenishedResources++;
        }

        if (ui.findReplenishAll() != null || ui.findObtain() != null) {
            return new Result(Outcome.LIMIT_REACHED, replenishedResources);
        }
        return new Result(Outcome.READY, replenishedResources);
    }

    /** Poll Replenish All while the Obtain-more sheet finishes opening. */
    static PointData findReplenishAllAfterObtain(Ui ui) {
        for (int attempt = 1; attempt <= REPLENISH_AFTER_OBTAIN_POLLS; ++attempt) {
            PointData replenishAll = ui.findReplenishAll();
            if (replenishAll != null) {
                return replenishAll;
            }
            if (attempt < REPLENISH_AFTER_OBTAIN_POLLS) {
                ui.waitAfterObtainOpened(attempt);
            }
        }
        return null;
    }
}
