package dev.frostguard.tasks.combat;

import static dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_ACTIVE_PETS_BOOL;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_CALL_RALLY_BOOL;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_JOIN_FLAG_INT;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_JOIN_RALLY_BOOL;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_NUMBER_INT;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_OWN_RALLY_START_DELAY_SEC;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_PREPARATION_TIME_INT;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_RALLY_FLAG_INT;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_RECALL_TROOPS_BOOL;
import static dev.frostguard.api.configs.TemplatesEnum.ALLIANCE_TERRITORY_BUTTON;
import static dev.frostguard.api.configs.TemplatesEnum.ALLIANCE_WAR_BUTTON;
import static dev.frostguard.api.configs.TemplatesEnum.BEAR_DEPLOY_BUTTON;
import static dev.frostguard.api.configs.TemplatesEnum.BEAR_HUNT_IS_RUNNING;
import static dev.frostguard.api.configs.TemplatesEnum.BEAR_JOIN_PLUS_ICON;
import static dev.frostguard.api.configs.TemplatesEnum.BEAR_RALLY_BUTTON;
import static dev.frostguard.api.configs.TemplatesEnum.GAME_HOME_PETS;
import static dev.frostguard.api.configs.TemplatesEnum.GAME_HOME_WAR;
import static dev.frostguard.api.configs.TemplatesEnum.MARCHES_AREA_RECALL_BUTTON;
import static dev.frostguard.api.configs.TemplatesEnum.MARCHES_AREA_SPEEDUP_BUTTON;
import static dev.frostguard.api.configs.TemplatesEnum.MARCHES_AREA_VIEW_BUTTON;
import static dev.frostguard.api.configs.TemplatesEnum.RALLY_HOLD_BUTTON;

import java.awt.Color;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalLong;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.FormationSlots;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.helper.BearTrapHelper;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.schedule.BearBSession;
import dev.frostguard.engine.schedule.BearBSessionStore;
import dev.frostguard.engine.schedule.BearBTiming;
import dev.frostguard.engine.schedule.BearTrapParticipationSchedule;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.schedule.TaskQueue;
import dev.frostguard.engine.service.BotOcrEngine;
import dev.frostguard.engine.service.ConfigService;
import dev.frostguard.engine.service.ScheduleService;
import dev.frostguard.engine.service.TelegramBotService;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.convert.RegexNumberParser;
import dev.frostguard.vision.ocr.ResilientOcrExecutor;

/**
 * Shared Bear B mechanics for Own Rally and Join short tasks.
 */
abstract class AbstractBearBRoutine extends DelayedTask {

    private static final int TRAP_DURATION_MINUTES = 30;
    private static final int TEMPLATE_RETRIES = 3;
    private static final int TEMPLATE_RETRIES_EXTENDED = 5;
    private static final int TEMPLATE_RETRIES_MAX = 10;
    private static final int MAX_GATHER_RECALL_ATTEMPTS = 120;
    private static final int INSIDE_RETRY_SECONDS = 20;
    private static final int WAR_TAB_REFRESH_SETTLE_MS = 500;
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");

    private static final PointData ALLIANCE_BUTTON_TL = new PointData(493, 1187);
    private static final PointData ALLIANCE_BUTTON_BR = new PointData(561, 1240);
    private static final PointData SPECIAL_BUILDINGS_TL = new PointData(460, 110);
    private static final PointData SPECIAL_BUILDINGS_BR = new PointData(560, 130);
    private static final PointData BEAR_TRAP_1_GO_TL = new PointData(570, 350);
    private static final PointData BEAR_TRAP_1_GO_BR = new PointData(620, 370);
    private static final PointData BEAR_TRAP_2_GO_TL = new PointData(570, 530);
    private static final PointData BEAR_TRAP_2_GO_BR = new PointData(620, 550);
    private static final PointData BEAR_CENTER = new PointData(370, 507);
    private static final PointData PET_RAZORBACK_TL = new PointData(100, 410);
    private static final PointData PET_RAZORBACK_BR = new PointData(160, 460);
    private static final PointData PET_QUICK_USE_TL = new PointData(120, 1070);
    private static final PointData PET_QUICK_USE_BR = new PointData(280, 1100);
    private static final PointData PET_USE_TL = new PointData(460, 800);
    private static final PointData PET_USE_BR = new PointData(550, 830);
    private static final PointData AUTOJOIN_TL = new PointData(260, 1200);
    private static final PointData AUTOJOIN_BR = new PointData(450, 1240);
    private static final PointData AUTOJOIN_STOP_TL = new PointData(120, 1070);
    private static final PointData AUTOJOIN_STOP_BR = new PointData(240, 1110);
    private static final PointData RECALL_CONFIRM_TL = new PointData(446, 780);
    private static final PointData RECALL_CONFIRM_BR = new PointData(578, 800);
    private static final PointData MARCH_TIME_TL = new PointData(504, 1134);
    private static final PointData MARCH_TIME_BR = new PointData(622, 1162);

    private static final OcrSettingsData FREE_MARCHES_OCR = OcrSettingsData.assembler()
            .charWhitelist("0123456789/")
            .stripBackground(true)
            .setTextColor(new Color(253, 253, 253))
            .build();

    protected int trapNumber = 1;
    protected int trapPreparationTime = 10;
    protected int ownRallyFlag = 1;
    protected int ownStartDelaySec = 0;
    protected boolean callOwnRally;
    protected boolean joinRally;
    protected boolean usePets;
    protected boolean recallTroops;
    protected LocalDateTime referenceTrapTime;
    protected List<Integer> joinFlags = new ArrayList<>();
    protected int currentJoinFlagIndex;
    protected ResilientOcrExecutor<Duration> durationHelper;
    protected BearBSession session;

    protected AbstractBearBRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

    @Override
    protected boolean acceptsInjections() {
        return false;
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.WORLD;
    }

    @Override
    public boolean consumesStamina() {
        return false;
    }

    @Override
    public boolean provideDailyMissionProgress() {
        return false;
    }

    protected void hydrateConfiguration() {
        this.trapNumber = resolveConfigInt(BEAR_TRAP_NUMBER_INT, 1);
        if (trapNumber != 2) {
            trapNumber = 1;
        }
        this.referenceTrapTime = resolveConfigDateTime(BearTrapParticipationSchedule.scheduleKey(trapNumber));
        this.trapPreparationTime = resolveConfigInt(BEAR_TRAP_PREPARATION_TIME_INT, 10);
        this.callOwnRally = resolveConfigBoolean(BEAR_TRAP_CALL_RALLY_BOOL, false);
        this.joinRally = resolveConfigBoolean(BEAR_TRAP_JOIN_RALLY_BOOL, false);
        this.usePets = resolveConfigBoolean(BEAR_TRAP_ACTIVE_PETS_BOOL, false);
        this.recallTroops = resolveConfigBoolean(BEAR_TRAP_RECALL_TROOPS_BOOL, false);
        this.ownRallyFlag = resolveConfigInt(BEAR_TRAP_RALLY_FLAG_INT, 1);
        this.ownStartDelaySec = Math.max(0, resolveConfigInt(BEAR_TRAP_OWN_RALLY_START_DELAY_SEC, 0));
        this.joinFlags = decodeJoinFlags();
        this.currentJoinFlagIndex = 0;
    }

    protected boolean ensureWindowSession() {
        if (referenceTrapTime == null) {
            logWarning(logLine("Reference trap time not configured"));
            return false;
        }
        BearTrapHelper.WindowResult window = resolveWindowState();
        if (window.getState() != BearTrapHelper.WindowState.INSIDE) {
            return false;
        }
        LocalDateTime windowEnd = LocalDateTime.ofInstant(window.getCurrentWindowEnd(), ZoneOffset.UTC);
        LocalDateTime activation = windowEnd.minusMinutes(TRAP_DURATION_MINUTES);
        LocalDateTime end = activation.plusMinutes(TRAP_DURATION_MINUTES);
        session = BearBSessionStore.require(
                profile.getId(),
                trapNumber,
                activation,
                end,
                end.toInstant(ZoneOffset.UTC));
        return true;
    }

    protected boolean windowStillOpen() {
        return session != null && Instant.now().isBefore(session.windowEndInstant());
    }

    protected long secondsRemaining() {
        if (session == null) {
            return 0;
        }
        return Math.max(0, ChronoUnit.SECONDS.between(Instant.now(), session.windowEndInstant()));
    }

    protected void rescheduleInsideWindow(int delaySeconds) {
        LocalDateTime wake = LocalDateTime.now().plusSeconds(Math.max(1, delaySeconds));
        if (session != null) {
            LocalDateTime endLocal = LocalDateTime.ofInstant(session.windowEndInstant(), ZoneId.systemDefault());
            if (wake.isAfter(endLocal)) {
                wake = endLocal;
            }
        }
        reschedule(wake);
    }

    protected void finishWindowAndParkNext() {
        if (session != null && session.markEndReported()) {
            notifyTelegram("Bear B ended — " + profile.getName()
                    + ": own " + session.ownCalled()
                    + ", joined " + session.joinsOk()
                    + ", failed joins " + session.joinsFailed());
            requeueDisabledTasks();
        }
        if (session != null) {
            BearBSessionStore.clear(profile.getId());
            session = null;
        }
        deferToNextWindow();
    }

    protected void deferToNextWindow() {
        if (referenceTrapTime == null) {
            reschedule(LocalDateTime.now().plusHours(48));
            return;
        }
        BearTrapHelper.WindowResult result = resolveWindowState();
        LocalDateTime nextWindowStart = LocalDateTime.ofInstant(
                result.getNextWindowStart(), ZoneId.systemDefault());
        LocalDateTime nextWindowStartUtc = LocalDateTime.ofInstant(
                result.getNextWindowStart(), ZoneOffset.UTC);
        logInfo(logLine("Planning next Bear B for (UTC): " + nextWindowStartUtc.format(DISPLAY)));
        reschedule(nextWindowStart);
        LocalDateTime nextActivation = nextWindowStartUtc.plusMinutes(trapPreparationTime);
        ConfigService.obtain().writeAccountSetting(
                profile,
                BearTrapParticipationSchedule.scheduleKey(trapNumber),
                nextActivation.format(DISPLAY));
    }

    protected void maybeWarnPrep(long secondsUntilActivation) {
        if (session == null || !session.markPrepWarned()) {
            return;
        }
        long minutes = Math.max(0, (secondsUntilActivation + 59) / 60);
        notifyTelegram("Bear B prep — " + profile.getName()
                + ": trap " + trapNumber
                + ", activates in " + minutes + "m");
    }

    protected void runPreparationIfNeeded() {
        if (session == null || session.isPrepDone()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        long secondsUntilActivation = ChronoUnit.SECONDS.between(now, session.activationUtc());
        maybeWarnPrep(Math.max(0, secondsUntilActivation));
        logInfo(logLine("Preparing for Bear Trap event..."));
        disableAutojoin();
        if (recallTroops) {
            logInfo(logLine("Recalling gather troops..."));
            recallGatherTroops();
        }
        if (usePets) {
            logInfo(logLine("Activating pets..."));
            enablePets();
        }
        logInfo(logLine("Moving camera to Bear Trap " + trapNumber));
        reachBearTrap(trapNumber);
        sleepTask(1000);
        session.markPrepDone();
        now = LocalDateTime.now(ZoneOffset.UTC);
        secondsUntilActivation = ChronoUnit.SECONDS.between(now, session.activationUtc());
        if (secondsUntilActivation > 0) {
            logInfo(logLine("Waiting for trap auto-activation in " + secondsUntilActivation + "s"));
            sleepTask(secondsUntilActivation * 1000L + 2000L);
        }
    }

    protected boolean ownStartDelayElapsed() {
        if (session == null || ownStartDelaySec <= 0) {
            return true;
        }
        long sinceActivation = ChronoUnit.SECONDS.between(session.activationUtc(), LocalDateTime.now(ZoneOffset.UTC));
        return sinceActivation >= ownStartDelaySec;
    }

    protected int secondsUntilOwnStartDelay() {
        if (session == null || ownStartDelaySec <= 0) {
            return 0;
        }
        long sinceActivation = ChronoUnit.SECONDS.between(session.activationUtc(), LocalDateTime.now(ZoneOffset.UTC));
        return (int) Math.max(0, ownStartDelaySec - sinceActivation);
    }

    protected boolean shouldLaunchOwnNow() {
        if (!callOwnRally || session == null || session.isOwnStoppedForCutoff()) {
            return false;
        }
        OptionalLong march = session.lockedMarchSeconds();
        if (BearBTiming.shouldStopOwn(secondsRemaining(), march)) {
            session.markOwnStoppedForCutoff();
            logInfo(logLine("Own rally cutoff reached; join continues until window end"));
            return false;
        }
        Instant now = Instant.now();
        if (now.isBefore(session.nextOwnAt()) || now.isBefore(session.ownBusyUntil())) {
            return false;
        }
        return ownStartDelayElapsed();
    }

    protected void initializeOcr() {
        BotOcrEngine provider = new BotOcrEngine(emuManager, EMULATOR_NUMBER);
        this.durationHelper = new ResilientOcrExecutor<>(provider);
    }

    /**
     * @return march seconds when deploy succeeded; empty when failed; empty+indefinite when read failed but deploy ok uses unlocked
     */
    protected OptionalLong deployOwnRally() {
        logInfo(logLine("Calling own rally..."));
        if (!navigateToBearForOwn()) {
            logError(logLine("Could not reach Bear Trap for own rally"));
            return OptionalLong.empty();
        }
        tapInside(BEAR_CENTER, BEAR_CENTER, 1, 200);
        sleepTask(500);

        ImageSearchResultData rallyButton = templateSearchHelper.locatePattern(
                BEAR_RALLY_BUTTON,
                SearchConfig.builder().withThreshold(80).withMaxAttempts(TEMPLATE_RETRIES_MAX).build());
        if (!rallyButton.isFound()) {
            logError(logLine("Rally button not detected"));
            return OptionalLong.empty();
        }
        logInfo(logLine("Entering rally menu..."));
        tapInside(rallyButton.getPoint(), rallyButton.getPoint(), 1, 200);
        sleepTask(500);

        ImageSearchResultData hold = templateSearchHelper.locatePattern(
                RALLY_HOLD_BUTTON,
                SearchConfig.builder().withThreshold(90).withMaxAttempts(TEMPLATE_RETRIES_MAX).build());
        if (!hold.isFound()) {
            logError(logLine("Hold Rally button not detected"));
            return OptionalLong.empty();
        }
        tapInside(hold.getPoint(), hold.getPoint(), 1, 200);
        sleepTask(300);

        if (!marchHelper.selectFlag(ownRallyFlag)) {
            logWarning(logLine("Configured rally formation #" + ownRallyFlag + " unavailable; cancelling own"));
            pressBack();
            return OptionalLong.empty();
        }

        long marchSeconds = scanMarchTime();
        boolean definite = marchSeconds > 0;
        if (!definite) {
            logWarning(logLine("Could not read definite march time; will re-read on next own"));
            marchSeconds = BearBTiming.UNKNOWN_MARCH_FALLBACK_SECONDS;
        }

        ImageSearchResultData deploy = templateSearchHelper.locatePattern(
                BEAR_DEPLOY_BUTTON,
                SearchConfig.builder().withThreshold(90).withMaxAttempts(TEMPLATE_RETRIES_MAX).build());
        if (!deploy.isFound()) {
            logWarning(logLine("Deploy button not detected after selecting flag"));
            pressBack();
            return OptionalLong.empty();
        }
        tapInside(deploy);
        sleepTask(500);
        logInfo(logLine("Own rally deployed. March time: " + marchSeconds + "s definite=" + definite));
        if (definite && session != null) {
            session.lockMarchSeconds(marchSeconds);
        }
        return OptionalLong.of(marchSeconds);
    }

    /**
     * Prefer the running-Bear HUD icon; fall back to today's Alliance→Trap path when missing.
     */
    protected boolean navigateToBearForOwn() {
        ImageSearchResultData hud = templateSearchHelper.locatePattern(
                BEAR_HUNT_IS_RUNNING,
                SearchConfig.builder().withThreshold(85).withMaxAttempts(TEMPLATE_RETRIES).build());
        if (hud.isFound()) {
            logInfo(logLine("Bear HUD icon found; tapping to center trap"));
            tapInside(hud);
            sleepTask(1200);
            return true;
        }
        logInfo(logLine("Bear HUD missing; falling back to Alliance→Trap"));
        return reachBearTrap(trapNumber);
    }

    protected void markOwnSuccess(long marchSeconds) {
        if (session == null) {
            return;
        }
        session.incrementOwnCalled();
        Instant now = Instant.now();
        OptionalLong locked = session.lockedMarchSeconds();
        OptionalLong forCycle = locked.isPresent() ? locked : OptionalLong.of(marchSeconds);
        int cycle = BearBTiming.ownCycleSeconds(forCycle);
        session.setOwnBusyUntil(now.plusSeconds(cycle));
        session.setNextOwnAt(now.plusSeconds(cycle));
    }

    protected Boolean attemptJoinOneRally() {
        List<ImageSearchResultData> plusIcons = templateSearchHelper.locateAllPatterns(
                BEAR_JOIN_PLUS_ICON,
                SearchConfig.builder().withThreshold(90).withMaxAttempts(2).withMaxResults(8).build());
        ImageSearchResultData plusIcon = plusIcons.stream()
                .filter(ImageSearchResultData::isFound)
                .min(Comparator.comparingInt(match -> match.getPoint().row()))
                .orElse(null);
        if (plusIcon == null) {
            logWarning(logLine("Zero joinable rallies detected (plus icon not present)"));
            return null;
        }

        int selectedFlag = resolveNextJoinFlag();
        logInfo(logLine("Joining rally with flag #" + selectedFlag + " (rotation: " + joinFlags + ")"));
        tapInside(plusIcon.getPoint(), plusIcon.getPoint(), 1, 100);
        sleepTask(300);

        if (!marchHelper.selectFlag(selectedFlag)) {
            logWarning(logLine("Configured join formation #" + selectedFlag + " unavailable; cancelling"));
            pressBack();
            return false;
        }
        if (deploymentHelper.hasNoDeployableTroops()) {
            logWarning(logLine("No deployable troops on join formation; aborting"));
            abortJoinFormationScreen();
            return false;
        }
        if (deploymentHelper.isDeployCostRed()) {
            logWarning(logLine("Deploy stamina cost is red; aborting join"));
            abortJoinFormationScreen();
            return false;
        }

        ImageSearchResultData deploy = templateSearchHelper.locatePattern(
                BEAR_DEPLOY_BUTTON,
                SearchConfig.builder().withThreshold(90).withMaxAttempts(TEMPLATE_RETRIES_MAX).build());
        if (!deploy.isFound()) {
            logWarning(logLine("Deploy button not detected after selecting flag."));
            abortJoinFormationScreen();
            return false;
        }
        tapInside(deploy);
        sleepTask(350);

        // Only leave via Back on an explicit fail dialog. Do not Back after a
        // successful Deploy — that can cancel the join march mid-send.
        if (deploymentHelper.isMarchQueueFull()) {
            logWarning(logLine("March queue full after Deploy; aborting join"));
            abortJoinFormationScreen();
            return false;
        }
        if (deploymentHelper.isSameTargetDialog()) {
            logWarning(logLine("Same-target dialog after Deploy; aborting join"));
            abortJoinFormationScreen();
            return false;
        }
        ImageSearchResultData stillDeploy = templateSearchHelper.locatePattern(
                BEAR_DEPLOY_BUTTON,
                SearchConfig.builder().withThreshold(90).withMaxAttempts(1).build());
        if (stillDeploy.isFound()) {
            logWarning(logLine("Deploy still present after tap — rally likely filled; aborting to try another"));
            abortJoinFormationScreen();
            return false;
        }
        logInfo(logLine("Rally join Deploy accepted"));
        return true;
    }

    /**
     * Reads free marches from the World HUD fraction. Unreadable → 0 (never invent capacity).
     */
    protected int inspectFreeMarches() {
        checkPreemption();
        emuManager.captureScreen(EMULATOR_NUMBER);
        AreaData marches = CommonGameAreas.WORLD_FREE_MARCHES_OCR;
        PointData tl = marches.topLeft();
        PointData br = marches.bottomRight();
        ResilientOcrExecutor<Integer> frameReader = new ResilientOcrExecutor<>(provider.reusingLastFrame());
        Integer used = frameReader.attemptRecognition(
                tl, br, 5, 10L, FREE_MARCHES_OCR,
                RegexNumberParser::hasFractionSyntax, RegexNumberParser::numerator);
        checkPreemption();
        Integer total = frameReader.attemptRecognition(
                tl, br, 5, 10L, FREE_MARCHES_OCR,
                RegexNumberParser::hasFractionSyntax, RegexNumberParser::denominator);
        if (used != null && total != null && total >= used) {
            int free = total - used;
            logInfo(logLine("Free marches: " + free + " (" + used + "/" + total + ")"));
            return Math.max(0, free);
        }
        logWarning(logLine("Could not read marches; treating free as 0 (no default capacity)"));
        return 0;
    }

    protected void abortJoinFormationScreen() {
        pressBack();
        sleepTask(300);
        pressBack();
        sleepTask(300);
    }

    protected abstract String logPrefix();

    protected String logLine(String note) {
        return logPrefix() + " | " + note;
    }

    protected int insideRetrySeconds() {
        return INSIDE_RETRY_SECONDS;
    }

    private void notifyTelegram(String text) {
        try {
            TelegramBotService.getInstance().notifyAllowedChat(text);
        } catch (Exception e) {
            logDebug(logLine("Telegram notify skipped: " + e.getMessage()));
        }
    }

    private void requeueDisabledTasks() {
        TaskQueue queue = ScheduleService.obtain().getCoordinator().getQueue(profile.getId());
        if (queue == null) {
            return;
        }
        Boolean gather = profile.getConfig(ConfigurationKeyEnum.GATHER_TASK_BOOL, Boolean.class);
        if (Boolean.TRUE.equals(gather)) {
            queue.runNow(TpDailyTaskEnum.GATHER_RESOURCES, true);
        }
        Boolean autojoin = profile.getConfig(ConfigurationKeyEnum.ALLIANCE_AUTOJOIN_BOOL, Boolean.class);
        if (Boolean.TRUE.equals(autojoin)) {
            queue.runNow(TpDailyTaskEnum.ALLIANCE_AUTOJOIN, true);
        }
    }

    private BearTrapHelper.WindowResult resolveWindowState() {
        Instant referenceUtc = referenceTrapTime.atZone(ZoneOffset.UTC).toInstant();
        return BearTrapHelper.calculateWindow(referenceUtc, trapPreparationTime);
    }

    private void disableAutojoin() {
        tapInside(ALLIANCE_BUTTON_TL, ALLIANCE_BUTTON_BR);
        sleepTask(3000);
        ImageSearchResultData warButton = templateSearchHelper.locatePattern(
                ALLIANCE_WAR_BUTTON,
                SearchConfig.builder().withThreshold(90).withMaxAttempts(TEMPLATE_RETRIES_EXTENDED).build());
        if (!warButton.isFound()) {
            logError(logLine("Alliance War button not detected to disable autojoin"));
            return;
        }
        tapInside(warButton.getPoint(), warButton.getPoint(), 1, 1000);
        sleepTask(1000);
        tapInside(AUTOJOIN_TL, AUTOJOIN_BR, 1, 1500);
        sleepTask(500);
        tapInside(AUTOJOIN_STOP_TL, AUTOJOIN_STOP_BR, 1, 500);
        sleepTask(500);
        navigationHelper.ensureCorrectScreenLocation(LaunchPoint.ANY);
    }

    private void enablePets() {
        ImageSearchResultData petsButton = templateSearchHelper.locatePattern(
                GAME_HOME_PETS,
                SearchConfig.builder().withThreshold(90).withMaxAttempts(TEMPLATE_RETRIES_EXTENDED).build());
        if (!petsButton.isFound()) {
            logError(logLine("Pets button not detected"));
            return;
        }
        tapInside(petsButton.getPoint(), petsButton.getPoint(), 1, 500);
        sleepTask(1000);
        tapInside(PET_RAZORBACK_TL, PET_RAZORBACK_BR, 1, 500);
        sleepTask(300);
        tapInside(PET_QUICK_USE_TL, PET_QUICK_USE_BR, 1, 500);
        sleepTask(300);
        tapInside(PET_USE_TL, PET_USE_BR, 1, 100);
        sleepTask(500);
        pressBack();
        sleepTask(300);
        navigationHelper.ensureCorrectScreenLocation(LaunchPoint.ANY);
    }

    private void recallGatherTroops() {
        writeProfileSetting(ConfigurationKeyEnum.GATHER_LAST_RECALL_TIME_STRING, LocalDateTime.now().toString());
        for (int attempt = 1; attempt <= MAX_GATHER_RECALL_ATTEMPTS; attempt++) {
            boolean recall = templateSearchHelper.locatePattern(
                    MARCHES_AREA_RECALL_BUTTON,
                    SearchConfig.builder().withThreshold(90).withMaxAttempts(TEMPLATE_RETRIES).build()).isFound();
            boolean view = templateSearchHelper.locatePattern(
                    MARCHES_AREA_VIEW_BUTTON,
                    SearchConfig.builder().withThreshold(90).withMaxAttempts(TEMPLATE_RETRIES).build()).isFound();
            boolean speedup = templateSearchHelper.locatePattern(
                    MARCHES_AREA_SPEEDUP_BUTTON,
                    SearchConfig.builder().withThreshold(90).withMaxAttempts(TEMPLATE_RETRIES).build()).isFound();
            if (!recall && !view && !speedup) {
                logInfo(logLine("Gather troops recalled or none present"));
                return;
            }
            if (recall) {
                ImageSearchResultData recallButton = templateSearchHelper.locatePattern(
                        MARCHES_AREA_RECALL_BUTTON,
                        SearchConfig.builder().withThreshold(90).withMaxAttempts(TEMPLATE_RETRIES).build());
                if (recallButton.isFound()) {
                    tapInside(recallButton.getPoint(), recallButton.getPoint(), 1, 300);
                    sleepTask(300);
                    tapInside(RECALL_CONFIRM_TL, RECALL_CONFIRM_BR, 1, 200);
                    sleepTask(500);
                }
            }
            if (view || speedup) {
                sleepTask(1000);
            }
            sleepTask(200);
        }
        logError(logLine("Gather recall exceeded max attempts"));
    }

    protected boolean reachBearTrap(int number) {
        tapInside(ALLIANCE_BUTTON_TL, ALLIANCE_BUTTON_BR);
        sleepTask(3000);
        ImageSearchResultData territory = templateSearchHelper.locatePattern(
                ALLIANCE_TERRITORY_BUTTON, SearchConfig.builder().withMaxAttempts(1).build());
        if (!territory.isFound()) {
            logError(logLine("Territory button not detected"));
            return false;
        }
        tapInside(territory.getPoint(), territory.getPoint(), 1, 2000);
        sleepTask(1000);
        tapInside(SPECIAL_BUILDINGS_TL, SPECIAL_BUILDINGS_BR, 1, 300);
        sleepTask(500);
        if (number == 1) {
            tapInside(BEAR_TRAP_1_GO_TL, BEAR_TRAP_1_GO_BR, 1, 300);
        } else if (number == 2) {
            tapInside(BEAR_TRAP_2_GO_TL, BEAR_TRAP_2_GO_BR, 1, 300);
        } else {
            return false;
        }
        sleepTask(2000);
        return true;
    }

    /**
     * Leave Rally (Solo) then return (Rally) so the list rebuilds with the next
     * joinable row on top. Re-tapping Rally alone does not refresh.
     */
    protected void refreshWarRallyListViaSoloTab() {
        logInfo(logLine("Refreshing War list via Solo→Rally tabs"));
        tapInside(CommonGameAreas.ALLIANCE_WAR_SOLO_TAB, 1, WAR_TAB_REFRESH_SETTLE_MS);
        sleepTask(WAR_TAB_REFRESH_SETTLE_MS);
        tapInside(CommonGameAreas.ALLIANCE_WAR_RALLY_TAB, 1, WAR_TAB_REFRESH_SETTLE_MS);
        sleepTask(WAR_TAB_REFRESH_SETTLE_MS);
    }

    private long scanMarchTime() {
        Duration marchingTime = durationHelper.attemptRecognition(
                MARCH_TIME_TL, MARCH_TIME_BR, 5, 200L, null,
                GameTimeUtils::isAcceptedFormat, GameTimeUtils::parseDuration);
        return marchingTime != null ? marchingTime.getSeconds() : 0;
    }

    private int resolveNextJoinFlag() {
        if (joinFlags.isEmpty()) {
            return 1;
        }
        int flag = joinFlags.get(currentJoinFlagIndex);
        currentJoinFlagIndex = (currentJoinFlagIndex + 1) % joinFlags.size();
        return flag;
    }

    private List<Integer> decodeJoinFlags() {
        String flagConfig = profile.getConfig(BEAR_TRAP_JOIN_FLAG_INT, String.class);
        List<Integer> flags = new ArrayList<>();
        if (flagConfig != null && !flagConfig.isBlank()) {
            for (String part : flagConfig.split(",")) {
                try {
                    int flag = Integer.parseInt(part.trim());
                    if (FormationSlots.supports(flag)) {
                        flags.add(flag);
                    }
                } catch (NumberFormatException ignored) {
                    logWarning(logLine("Invalid join flag value: " + part));
                }
            }
        }
        if (flags.isEmpty()) {
            flags.add(1);
        }
        flags.sort(Integer::compareTo);
        return flags;
    }

    private boolean resolveConfigBoolean(ConfigurationKeyEnum key, boolean defaultValue) {
        Boolean value = profile.getConfig(key, Boolean.class);
        return value != null ? value : defaultValue;
    }

    private int resolveConfigInt(ConfigurationKeyEnum key, int defaultValue) {
        Integer value = profile.getConfig(key, Integer.class);
        return value != null ? value : defaultValue;
    }

    private LocalDateTime resolveConfigDateTime(ConfigurationKeyEnum key) {
        return profile.getConfig(key, LocalDateTime.class);
    }

    protected void openWarAndJoin(int freeMarches) {
        if (freeMarches <= 0) {
            return;
        }
        ImageSearchResultData warButton = templateSearchHelper.locatePattern(
                GAME_HOME_WAR,
                SearchConfig.builder().withThreshold(90).withMaxAttempts(TEMPLATE_RETRIES).build());
        if (!warButton.isFound()) {
            logWarning(logLine("War button not found; skipping join pass"));
            return;
        }
        logInfo(logLine("Entering war section to check for rallies"));
        tapInside(warButton);
        sleepTask(800);
        // Order: War → join → Solo→Rally → join → Solo→Rally → … (leave/return refreshes top row)
        int joinsWanted = freeMarches;
        int joinsSucceeded = 0;
        int maxAttempts = Math.max(joinsWanted * 3, joinsWanted);
        for (int attempt = 1; attempt <= maxAttempts && joinsSucceeded < joinsWanted; attempt++) {
            Boolean outcome = attemptJoinOneRally();
            if (outcome == null) {
                break;
            }
            if (Boolean.TRUE.equals(outcome)) {
                joinsSucceeded++;
                if (session != null) {
                    session.incrementJoinsOk();
                }
            } else if (session != null) {
                session.incrementJoinsFailed();
            }
            if (joinsSucceeded >= joinsWanted) {
                break;
            }
            refreshWarRallyListViaSoloTab();
        }
        navigationHelper.ensureCorrectScreenLocation(LaunchPoint.ANY);
    }
}
