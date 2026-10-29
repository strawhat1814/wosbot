package dev.frostguard.engine.helper;

import java.awt.image.BufferedImage;
import java.util.Optional;
import java.util.function.Predicate;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.input.TapInteractionService;
import dev.frostguard.engine.input.TapJitterPolicy;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.SidebarDestination;
import dev.frostguard.engine.nav.SidebarFrameClassifier;
import dev.frostguard.engine.nav.SidebarRowAction;
import dev.frostguard.engine.nav.SidebarRowLookup;
import dev.frostguard.engine.nav.SidebarSection;
import dev.frostguard.engine.nav.SidebarViewportChangeDetector;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.vision.convert.ImageConverter;
import dev.frostguard.vision.logging.ProfileContextLogger;

/** State-verified navigation through the City, Wilderness, and Daily sidebar. */
public final class SidebarNavigator {

    private static final int SECTION_SETTLE_MS = 400;
    static final int TRANSITION_POLL_MS = 200;
    static final int TRANSITION_POLL_CHECKS = 9;
    static final int MAX_TRIGGER_TAPS = 2;
    static final int SIDEBAR_OPEN_SETTLE_MS = 2_000;
    static final int SCROLL_SETTLE_MS = 2_000;
    static final int SCROLL_DISTANCE_PX = 120;
    private static final int SCROLL_DURATION_MS = 500;
    private static final int MAX_BOUNDARY_STEPS = 10;
    private static final int DESTINATION_SETTLE_MS = 2_000;
    private static final int ROW_ICON_THRESHOLD = 88;
    private static final int ROW_ACTION_THRESHOLD = 88;
    /** Same title crop DeploymentHelper uses for the Rally "March Queue" unlock / full popup. */
    private static final int MARCH_QUEUE_UNLOCK_THRESHOLD = 85;
    private static final int MARCH_QUEUE_DISMISS_SETTLE_MS = 500;
    /** Softer match for My Rewards green checkmarks that sit just under 88. */
    private static final int CLAIM_ACTION_RETRY_THRESHOLD = 78;
    private static final int GO_LEFT_OFFSET = 315;
    private static final int GO_RIGHT_OFFSET = 394;
    /**
     * Vertical half-extent of the Claim/Go hit box. 30px clips Online Rewards /
     * Warm Welcome green checkmarks (live match ~0.42); 40px restores ~0.99.
     */
    private static final int GO_HALF_HEIGHT = 40;

    private final EmulatorController emu;
    private final String device;
    private final TapInteractionService taps;
    private final ScreenStateReader screenStates;
    private final Waiter transitionWaiter;
    private final ProfileContextLogger log;

    public SidebarNavigator(EmulatorController emu, String device, AccountDescriptor profile) {
        this.emu = emu;
        this.device = device;
        this.taps = TapInteractionService.forController(emu, device);
        this.screenStates = this::captureScreenState;
        this.transitionWaiter = this::interruptibleWait;
        this.log = new ProfileContextLogger(SidebarNavigator.class, profile);
    }

    public boolean openSection(SidebarSection target) {
        return openSectionInternal(target);
    }

    public boolean navigateTo(SidebarDestination destination) {
        ImageSearchResultData locatedRow = findRow(destination);
        if (locatedRow == null || !locatedRow.isFound()) {
            return false;
        }

        RawImageData actionFrame = emu.captureScreen(device);
        ImageSearchResultData rowIcon = locateRowIcon(destination, actionFrame);
        if (!rowIcon.isFound()) {
            log.info("Sidebar row moved before its action could be verified: " + destination);
            return false;
        }

        AreaData actionArea = rowActionAreaFor(rowIcon);
        ImageSearchResultData action = locateRowAction(destination, actionArea, actionFrame);
        if (action == null || !action.isFound()) {
            // Fresh frame: claim checkmarks often settle a beat after the row icon.
            actionFrame = emu.captureScreen(device);
            rowIcon = locateRowIcon(destination, actionFrame);
            if (rowIcon.isFound()) {
                actionArea = rowActionAreaFor(rowIcon);
                action = locateRowAction(destination, actionArea, actionFrame);
                if (action == null || !action.isFound()) {
                    action = locateRowAction(destination, actionArea, actionFrame, CLAIM_ACTION_RETRY_THRESHOLD);
                }
            }
        }
        if (action == null || !action.isFound()) {
            // Do not blind-tap the Claim slot: when the checkmark is gone the slot holds a
            // cooldown timer. Tapping it leaves Daily open and the task OCR-retries forever.
            log.info("Sidebar row found without an expected action: " + destination
                    + " icon=" + rowIcon + " actionArea=" + actionArea);
            return false;
        }

        log.info("Sidebar destination found: " + destination + " icon=" + rowIcon
                + " action=" + action);
        taps.tapInside(action, 1, DESTINATION_SETTLE_MS);
        if (selectedSection().isEmpty()) {
            return true;
        }

        // Claim checkmarks can collect or settle without leaving Daily. Prefer Go next.
        if (hasGoAction(destination)) {
            RawImageData goFrame = emu.captureScreen(device);
            ImageSearchResultData goIcon = locateRowIcon(destination, goFrame);
            if (goIcon.isFound()) {
                AreaData goArea = rowActionAreaFor(goIcon);
                ImageSearchResultData goAction = locateSpecificRowAction(
                        SidebarRowAction.GO, goArea, goFrame, ROW_ACTION_THRESHOLD);
                if (goAction.isFound()) {
                    log.info("Sidebar stayed open after first action; retrying with Go: "
                            + destination + " action=" + goAction);
                    taps.tapInside(goAction, 1, DESTINATION_SETTLE_MS);
                    if (selectedSection().isEmpty()) {
                        return true;
                    }
                }
            }
        }

        log.warn("Sidebar stayed open after tapping destination " + destination);
        return false;
    }

    /** Finds a row from its left icon in the current viewport and after every settled scroll. */
    public ImageSearchResultData findRow(SidebarDestination destination) {
        return findRowWithStatus(destination).rowIcon();
    }

    /** Distinguishes a failed sidebar transition from a completed scan with no matching row. */
    public SidebarRowLookup findRowWithStatus(SidebarDestination destination) {
        if (!openSection(destination.section())) {
            return SidebarRowLookup.sidebarUnavailable();
        }

        RawImageData frame = emu.captureScreen(device);
        if (frame == null || selectedSection(frame).orElse(null) != destination.section()) {
            log.warn("Sidebar state changed before scanning for " + destination);
            return SidebarRowLookup.sidebarUnavailable();
        }
        ImageSearchResultData current = locateRowIcon(destination, frame);
        if (current.isFound()) {
            return SidebarRowLookup.fromRow(current);
        }

        ScrollScanResult bottomScan = scan(destination, ScrollDirection.TOWARD_BOTTOM, frame);
        if (!bottomScan.completed()) {
            log.warn("Sidebar scan did not complete on a verified " + destination.section()
                    + " section: " + destination);
            return SidebarRowLookup.sidebarUnavailable();
        }
        if (bottomScan.rowIcon().isFound()) {
            return SidebarRowLookup.fromRow(bottomScan.rowIcon());
        }
        log.info("Sidebar destination unavailable after bounded icon scan: " + destination
                + " bottomBoundary=" + bottomScan.boundaryReached());
        return SidebarRowLookup.fromRow(ImageSearchResultData.miss());
    }

    public boolean close() {
        if (selectedSection().isEmpty()) {
            return true;
        }
        taps.tapInside(CommonGameAreas.LEFT_MENU_CLOSE, 1, SECTION_SETTLE_MS);
        boolean closed = selectedSection().isEmpty();
        if (!closed) {
            log.warn("Sidebar close control did not close the panel");
        }
        return closed;
    }

    private boolean openSectionInternal(SidebarSection target) {
        ScreenState initialState = screenStates.read();
        Optional<SidebarSection> current = initialState.section();

        if (current.isEmpty()) {
            if (!initialState.rootScreen()) {
                log.warn("Refusing to tap the sidebar trigger without a Home or World anchor");
                return false;
            }
            // Sample the full handle strip at random. Only after an Add March hit (March Queue
            // unlock dialog) does the retry force the opposite vertical half.
            AreaData fullTrigger = CommonGameAreas.LEFT_MENU_TRIGGER;
            TriggerHalf forceHalf = null;
            TriggerHalf lastHalf = null;
            int triggerTaps = 0;
            while (current.isEmpty() && triggerTaps < MAX_TRIGGER_TAPS) {
                triggerTaps++;
                AreaData trigger;
                PointData tapPoint;
                TriggerHalf half;
                if (forceHalf != null) {
                    half = forceHalf;
                    trigger = verticalHalf(fullTrigger, half);
                    tapPoint = TapJitterPolicy.sampleInsideUniform(trigger);
                    forceHalf = null;
                } else {
                    trigger = fullTrigger;
                    tapPoint = TapJitterPolicy.sampleInsideUniform(trigger);
                    half = halfContaining(fullTrigger, tapPoint);
                }
                lastHalf = half;
                log.debug("Sidebar closed/unknown; trigger tap " + triggerTaps + "/" + MAX_TRIGGER_TAPS
                        + " half=" + half + " point=" + tapPoint + " area=" + trigger
                        + " before selecting " + target
                        + (triggerTaps > 1 ? " (retry)" : ""));
                taps.tapNear(tapPoint, 0, 1, SIDEBAR_OPEN_SETTLE_MS);

                TransitionObservation observation = awaitSection(Optional::isPresent);
                current = observation.section();
                if (current.isPresent()) {
                    if (observation.checks() > 1 || triggerTaps > 1) {
                        log.debug("Sidebar open confirmed after triggerTaps=" + triggerTaps
                                + " half=" + half
                                + " checks=" + observation.checks()
                                + " observed=" + current.orElseThrow());
                    }
                    break;
                }

                ScreenState retryState = screenStates.read();
                current = retryState.section();
                if (current.isPresent()) {
                    log.debug("Sidebar open confirmed on retry verification without another trigger: observed="
                            + current.orElseThrow());
                    break;
                }
                boolean rootScreen = retryState.rootScreen();
                if (!rootScreen) {
                    // Accidental Add March opens the unlock/purchase March Queue dialog.
                    if (dismissMarchQueueUnlockDialog()) {
                        forceHalf = half.opposite();
                        log.info("Dismissed March Queue unlock dialog after sidebar trigger half="
                                + half + "; will retry opposite half=" + forceHalf
                                + " if attempts remain");
                    } else {
                        log.warn("Sidebar trigger left the root screen; pressing Back");
                        emu.pressBack(device);
                        interruptibleWait(400);
                    }
                    retryState = screenStates.read();
                    current = retryState.section();
                    if (current.isPresent()) {
                        break;
                    }
                    rootScreen = retryState.rootScreen();
                }
                if (!shouldRetryTrigger(triggerTaps, observation, current, rootScreen)) {
                    log.warn("Sidebar open not confirmed: triggerTaps=" + triggerTaps + "/"
                            + MAX_TRIGGER_TAPS + " half=" + lastHalf
                            + " checks=" + observation.checks()
                            + " interrupted=" + observation.interrupted()
                            + " observed=closed/unknown rootScreen=" + rootScreen);
                    return false;
                }
                log.debug("Sidebar still closed/unknown on a verified root screen after checks="
                        + observation.checks()
                        + (forceHalf != null
                                ? "; retrying opposite half after Add March"
                                : "; retrying full trigger strip"));
            }
        }

        if (current.orElse(null) != target) {
            taps.tapInside(CommonGameAreas.sidebarTab(target), 1, SECTION_SETTLE_MS);
            TransitionObservation observation = awaitSection(section -> section.orElse(null) == target);
            current = observation.section();
            if (current.orElse(null) != target) {
                log.warn("Sidebar section selection failed: requested=" + target
                        + " observed=" + current.map(Enum::name).orElse("closed/unknown")
                        + " checks=" + observation.checks());
                return false;
            }
            if (observation.checks() > 1) {
                log.debug("Sidebar section transition recovered after checks="
                        + observation.checks() + " requested=" + target);
            }
        }

        log.debug("Sidebar section ready without assuming a scroll origin: " + target);
        return true;
    }

    public static AreaData rowActionAreaFor(ImageSearchResultData rowIcon) {
        PointData center = rowIcon.getPoint();
        if (center == null) {
            throw new IllegalArgumentException("A located row icon is required");
        }
        int top = Math.max(CommonGameAreas.SIDEBAR_CONTENT.topLeft().getY(), center.getY() - GO_HALF_HEIGHT);
        int bottom = Math.min(CommonGameAreas.SIDEBAR_CONTENT.bottomRight().getY(), center.getY() + GO_HALF_HEIGHT);
        return new AreaData(
                new PointData(center.getX() + GO_LEFT_OFFSET, top),
                new PointData(center.getX() + GO_RIGHT_OFFSET, bottom));
    }

    static AreaData goButtonFor(ImageSearchResultData rowIcon) {
        return rowActionAreaFor(rowIcon);
    }

    private ScrollScanResult scan(SidebarDestination destination, ScrollDirection direction,
                                  RawImageData initialFrame) {
        RawImageData before = initialFrame;
        for (int step = 1; step <= MAX_BOUNDARY_STEPS; step++) {
            RawImageData after = scrollAndCapture(direction);
            if (after == null || selectedSection(after).orElse(null) != destination.section()) {
                log.warn("Sidebar state changed while scanning for " + destination
                        + " direction=" + direction + " step=" + step);
                return new ScrollScanResult(ImageSearchResultData.miss(), before, false, false);
            }

            ImageSearchResultData rowIcon = locateRowIcon(destination, after);
            boolean changed = SidebarViewportChangeDetector.materiallyChanged(
                    ImageConverter.toBufferedImage(before), ImageConverter.toBufferedImage(after));
            log.debug("Sidebar icon scan: destination=" + destination + " direction=" + direction
                    + " step=" + step + "/" + MAX_BOUNDARY_STEPS
                    + " changed=" + changed + " found=" + rowIcon.isFound());
            if (rowIcon.isFound()) {
                return new ScrollScanResult(rowIcon, after, false, true);
            }
            if (!changed) {
                return new ScrollScanResult(ImageSearchResultData.miss(), after, true, true);
            }
            before = after;
        }
        return new ScrollScanResult(ImageSearchResultData.miss(), before, false, true);
    }

    private RawImageData scrollAndCapture(ScrollDirection direction) {
        emu.swipeScreen(device, direction.from(), direction.to(), SCROLL_DURATION_MS);
        if (!interruptibleWait(SCROLL_SETTLE_MS)) {
            return null;
        }
        return emu.captureScreen(device);
    }

    private ImageSearchResultData locateRowIcon(SidebarDestination destination, RawImageData frame) {
        return emu.locatePattern(device, frame, destination.rowIcon(),
                CommonGameAreas.SIDEBAR_ROW_ICON_COLUMN.topLeft(),
                CommonGameAreas.SIDEBAR_ROW_ICON_COLUMN.bottomRight(), ROW_ICON_THRESHOLD);
    }

    private ImageSearchResultData locateRowAction(SidebarDestination destination, AreaData area,
                                                  RawImageData frame) {
        return locateRowAction(destination, area, frame, ROW_ACTION_THRESHOLD);
    }

    private ImageSearchResultData locateRowAction(SidebarDestination destination, AreaData area,
                                                  RawImageData frame, int threshold) {
        for (SidebarRowAction action : destination.actions()) {
            ImageSearchResultData result = locateSpecificRowAction(action, area, frame, threshold);
            if (result.isFound()) {
                return result;
            }
        }
        return ImageSearchResultData.miss();
    }

    private ImageSearchResultData locateSpecificRowAction(SidebarRowAction action, AreaData area,
                                                          RawImageData frame, int threshold) {
        for (TemplatesEnum template : action.templates()) {
            ImageSearchResultData result = emu.locatePattern(device, frame, template,
                    area.topLeft(), area.bottomRight(), threshold);
            if (result.isFound()) {
                return result;
            }
        }
        return ImageSearchResultData.miss();
    }

    /** My Rewards rows only expose Claim (green checkmark), never Go. */
    static boolean isClaimOnly(SidebarDestination destination) {
        SidebarRowAction[] actions = destination.actions();
        if (actions.length == 0) {
            return false;
        }
        for (SidebarRowAction action : actions) {
            if (action != SidebarRowAction.CLAIM) {
                return false;
            }
        }
        return true;
    }

    static boolean hasGoAction(SidebarDestination destination) {
        for (SidebarRowAction action : destination.actions()) {
            if (action == SidebarRowAction.GO) {
                return true;
            }
        }
        return false;
    }

    private ScreenState captureScreenState() {
        RawImageData frame = emu.captureScreen(device);
        Optional<SidebarSection> section = selectedSection(frame);
        return new ScreenState(section, section.isEmpty() && isRootScreen(frame));
    }

    private boolean isRootScreen(RawImageData frame) {
        double threshold = SearchConfigConstants.DEFAULT_SINGLE.getThreshold();
        return emu.locatePattern(device, frame, TemplatesEnum.GAME_HOME_FURNACE, threshold).isFound()
                || emu.locatePattern(device, frame, TemplatesEnum.GAME_HOME_WORLD, threshold).isFound();
    }

    private Optional<SidebarSection> selectedSection() {
        return selectedSection(emu.captureScreen(device));
    }

    private Optional<SidebarSection> selectedSection(RawImageData frame) {
        return SidebarFrameClassifier.selectedSection(ImageConverter.toBufferedImage(frame));
    }

    private TransitionObservation awaitSection(Predicate<Optional<SidebarSection>> accepted) {
        return awaitSection(accepted, this::selectedSection, transitionWaiter);
    }

    static TransitionObservation awaitSection(Predicate<Optional<SidebarSection>> accepted,
                                               SectionReader reader, Waiter waiter) {
        Optional<SidebarSection> observed = Optional.empty();
        boolean interrupted = false;
        for (int check = 1; check <= TRANSITION_POLL_CHECKS; check++) {
            observed = reader.read();
            if (accepted.test(observed) || check == TRANSITION_POLL_CHECKS) {
                return new TransitionObservation(observed, check, interrupted);
            }
            if (!waiter.waitFor(TRANSITION_POLL_MS)) {
                return new TransitionObservation(observed, check, true);
            }
        }
        throw new IllegalStateException("Unreachable sidebar transition polling state");
    }

    static boolean shouldRetryTrigger(int triggerTaps, TransitionObservation observation,
                                      Optional<SidebarSection> observed, boolean rootScreen) {
        return !observation.interrupted() && triggerTaps < MAX_TRIGGER_TAPS
                && observed.isEmpty() && rootScreen;
    }

    /**
     * Splits the collapsed-handle strip into an upper or lower half so a second trigger can
     * avoid the World "Add March" control that overlaps one half of the strip.
     */
    static AreaData verticalHalf(AreaData full, TriggerHalf half) {
        if (full == null || half == null) {
            throw new IllegalArgumentException("full area and half are required");
        }
        int x0 = Math.min(full.topLeft().getX(), full.bottomRight().getX());
        int x1 = Math.max(full.topLeft().getX(), full.bottomRight().getX());
        int y0 = Math.min(full.topLeft().getY(), full.bottomRight().getY());
        int y1 = Math.max(full.topLeft().getY(), full.bottomRight().getY());
        int midY = y0 + (y1 - y0) / 2;
        if (half == TriggerHalf.UPPER) {
            return new AreaData(new PointData(x0, y0), new PointData(x1, midY));
        }
        return new AreaData(new PointData(x0, midY), new PointData(x1, y1));
    }

    /** Which vertical half of {@code full} contains {@code point} (midline counts as lower). */
    static TriggerHalf halfContaining(AreaData full, PointData point) {
        if (full == null || point == null) {
            throw new IllegalArgumentException("full area and point are required");
        }
        int y0 = Math.min(full.topLeft().getY(), full.bottomRight().getY());
        int y1 = Math.max(full.topLeft().getY(), full.bottomRight().getY());
        int midY = y0 + (y1 - y0) / 2;
        return point.getY() < midY ? TriggerHalf.UPPER : TriggerHalf.LOWER;
    }

    /**
     * Closes the March Queue unlock/purchase dialog when a sidebar handle tap hit Add March.
     * Reuses the Rally "March Queue" title template and close point.
     */
    private boolean dismissMarchQueueUnlockDialog() {
        RawImageData frame = emu.captureScreen(device);
        AreaData titleArea = CommonGameAreas.RALLY_MARCH_QUEUE_FULL_AREA;
        ImageSearchResultData popup = emu.locatePattern(
                device,
                frame,
                TemplatesEnum.RALLY_MARCH_QUEUE_FULL,
                titleArea.topLeft(),
                titleArea.bottomRight(),
                MARCH_QUEUE_UNLOCK_THRESHOLD);
        if (!popup.isFound()) {
            return false;
        }
        log.warn("March Queue unlock dialog after sidebar trigger at " + popup.getPoint()
                + " score=" + popup.getMatchScore());
        taps.tapNear(CommonGameAreas.RALLY_MARCH_QUEUE_FULL_CLOSE);
        interruptibleWait(MARCH_QUEUE_DISMISS_SETTLE_MS);
        return true;
    }

    private boolean interruptibleWait(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private record ScrollScanResult(ImageSearchResultData rowIcon, RawImageData frame,
                                    boolean boundaryReached, boolean completed) {}

    record TransitionObservation(Optional<SidebarSection> section, int checks, boolean interrupted) {}

    record ScreenState(Optional<SidebarSection> section, boolean rootScreen) {}

    @FunctionalInterface
    interface SectionReader {
        Optional<SidebarSection> read();
    }

    @FunctionalInterface
    interface ScreenStateReader {
        ScreenState read();
    }

    @FunctionalInterface
    interface Waiter {
        boolean waitFor(long milliseconds);
    }

    enum TriggerHalf {
        UPPER,
        LOWER;

        TriggerHalf opposite() {
            return this == UPPER ? LOWER : UPPER;
        }
    }

    private enum ScrollDirection {
        TOWARD_BOTTOM(CommonGameAreas.SIDEBAR_SCROLL_TOWARD_BOTTOM_FROM,
                CommonGameAreas.SIDEBAR_SCROLL_TOWARD_BOTTOM_TO);

        private final PointData from;
        private final PointData to;

        ScrollDirection(PointData from, PointData to) {
            this.from = from;
            this.to = to;
        }

        PointData from() {
            return from;
        }

        PointData to() {
            return to;
        }
    }
}
