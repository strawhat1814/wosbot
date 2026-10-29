package dev.frostguard.tasks.events;

import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.data.entity.DailyTask;
import dev.frostguard.data.repository.DailyTaskRepository;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.engine.service.TaskManagementService;
import dev.frostguard.engine.service.StaminaService;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.helper.NavigationHelper.EventMenu;
import dev.frostguard.engine.helper.NavigationHelper.EventMenuOpenResult;
import dev.frostguard.engine.helper.DeploymentHelper;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.diagnostics.MissingTemplateSnapshotSettings;
import dev.frostguard.tasks.diagnostics.TaskControlSignals;
import dev.frostguard.tasks.diagnostics.TaskDiagnosticSnapshots;
import java.awt.Color;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

public class MercenaryEventRoutine extends DelayedTask {
    /** World HUD summon icons differ by difficulty; grow this list as each crop is added. */
    private static final TemplatesEnum[] WORLD_SUMMON_TEMPLATES = {
            TemplatesEnum.MERCENARY_WORLD_SUMMON,
            TemplatesEnum.MERCENARY_WORLD_SUMMON_2,
            TemplatesEnum.MERCENARY_WORLD_SUMMON_3
    };

    private static final AreaData WORLD_SUMMON_SEARCH_AREA = AreaData.of(520, 180, 720, 980);
    private static final SearchConfig WORLD_SUMMON_SEARCH = SearchConfig.builder()
            .withMaxAttempts(3)
            .withDelay(200L)
            .withThreshold(75)
            .withArea(WORLD_SUMMON_SEARCH_AREA)
            .build();
    private static final Duration HELP_RETRY_DELAY = Duration.ofMinutes(15);
    private static final Duration HELP_MISSING_RETRY = Duration.ofMinutes(5);

    private final DailyTaskRepository iDailyTaskRepository = DailyTaskRepository.getRepository();
    private final TaskManagementService taskManagementService = TaskManagementService.shared();
    private Integer lastMercenaryLevel = null;
    private Integer lastStaminaSpent = null;
    private int attackAttempts = 0;
    private int flagNumber = 0;
    private boolean useFlag = false;
    private final int refreshStaminaLevel = 100;
    private final int minStaminaLevel = 40;
    private boolean scout = false;
    private final EventMenuRetryState menuRetry = new EventMenuRetryState();

    public MercenaryEventRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
        super(profile, tpDailyTask);
    }

    @Override
    protected void execute() {

        flagNumber = profile.getConfig(ConfigurationKeyEnum.MERCENARY_FLAG_INT, Integer.class);
        useFlag = flagNumber > 0;

        if (profile.getConfig(ConfigurationKeyEnum.INTEL_BOOL, Boolean.class)
                && useFlag
                && taskManagementService.lookupTaskState(profile.getId(), TpDailyTaskEnum.INTEL.getId()).isScheduled()) {
            // Make sure intel isn't about to run
            DailyTask intel = iDailyTaskRepository.findByAccountIdAndTaskType(profile.getId(), TpDailyTaskEnum.INTEL);
            if (ChronoUnit.MINUTES.between(LocalDateTime.now(), intel.getScheduledAt()) < 5) {
                reschedule(LocalDateTime.now().plusMinutes(35)); // Reschedule in 35 minutes, after intel has run
                logWarning(
                        "Intel task is scheduled to run soon. Rescheduling Mercenary Event to run 30min after intel.");
                return;
            }
        }

        // Verify if there's enough stamina to hunt, if not, reschedule the task
        if (!staminaHelper.checkStaminaAndMarchesOrReschedule(minStaminaLevel, refreshStaminaLevel, this))
            return;

        if (tryWorldSummonAndHandle()) {
            return;
        }

        boolean tabWasAbsent = false;
        int attempt = 0;
        while (attempt < 2) {
            EventMenuOpenResult opened = openMercenaryMenu();
            if (opened == EventMenuOpenResult.REACHED) {
                menuRetry.found();
                handleMercenaryEvent();
                return;
            }
            if (opened == EventMenuOpenResult.TAB_ABSENT) {
                tabWasAbsent = true;
            }
            logDebug("Navigation to Mercenary event failed, attempt " + (attempt + 1));
            sleepTask(300);
            pressBack();
            attempt++;
        }

        respondToMenu(tabWasAbsent ? EventMenuOpenResult.TAB_ABSENT : EventMenuOpenResult.PANEL_CLOSED);
    }

    /**
     * Prefer World HUD summon over Events. The HUD icon opens the merc card with
     * Scout/Challenge; that control must be tapped before the World Attack/Help popup.
     * Returns true when the visit was fully handled (or a retry was scheduled).
     * Returns false so Events can run when the shortcut was absent or the card never opened.
     */
    private boolean tryWorldSummonAndHandle() {
        TemplatesEnum matched = null;
        for (TemplatesEnum summonTemplate : WORLD_SUMMON_TEMPLATES) {
            ImageSearchResultData hit = templateSearchHelper.locatePattern(summonTemplate, WORLD_SUMMON_SEARCH);
            if (hit.isFound()) {
                logInfo("World mercenary summon shortcut found (" + summonTemplate.name()
                        + ", score=" + String.format(java.util.Locale.ROOT, "%.1f", hit.getMatchScore())
                        + "). Tapping to open the merc card.");
                tapInside(hit);
                sleepTask(2500);
                matched = summonTemplate;
                break;
            }
        }
        if (matched == null) {
            logInfo("No World mercenary summon shortcut matched; using Events menu.");
            return false;
        }

        if (!scoutOrChallengeFromSummonCard() && !mercActionPopupOpen()) {
            sleepTask(1500);
            if (!scoutOrChallengeFromSummonCard() && !mercActionPopupOpen()) {
                String snapshot = TaskDiagnosticSnapshots.capture(
                        emuManager, EMULATOR_NUMBER, "mercenaryevent", "world-summon-no-scout");
                logWarning("World summon (" + matched.name()
                        + ") opened neither Scout/Challenge nor Attack/Help. Falling back to Events. "
                        + snapshot);
                pressBack();
                sleepTask(500);
                return false;
            }
        }

        try {
            respondToOpenedScreen(true);
            boolean sameLevelAsLastTime = noteLevelChange(checkMercenaryLevelPreAttack());
            if (requestHelpWhenMilestone(lastMercenaryLevel)) {
                return true;
            }
            if (restIfFinalLevelSettled(lastMercenaryLevel)) {
                return true;
            }
            attackFromWorldPopup(sameLevelAsLastTime);
            return true;
        } catch (Exception e) {
            TaskControlSignals.rethrowControlSignal(e);
            LocalDateTime retryAt = LocalDateTime.now().plusMinutes(30);
            String snapshot = TaskDiagnosticSnapshots.capture(
                    emuManager, EMULATOR_NUMBER, "mercenaryevent", "task-error");
            logError("Mercenary Event stopped on " + e.getClass().getSimpleName()
                    + "; retrying at " + retryAt.format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss"))
                    + "; " + snapshot + ".", e);
            reschedule(retryAt);
            return true;
        }
    }

    /**
     * On the World-summon merc card, tap Scout (or Challenge) and wait for map travel.
     * Returns true when that control was found and tapped.
     */
    private boolean scoutOrChallengeFromSummonCard() {
        ImageSearchResultData scoutButton = templateSearchHelper.locatePattern(
                TemplatesEnum.MERCENARY_SCOUT_BUTTON, SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (scoutButton.isFound()) {
            scout = true;
            logInfo("World summon card: Scout found. Tapping to travel to the merc.");
            tapInside(scoutButton);
            sleepTask(4000);
            return true;
        }

        ImageSearchResultData challengeButton = templateSearchHelper.locatePattern(
                TemplatesEnum.MERCENARY_CHALLENGE_BUTTON, SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (challengeButton.isFound()) {
            scout = false;
            logInfo("World summon card: Challenge found. Tapping to travel to the merc.");
            tapInside(challengeButton);
            sleepTask(4000);
            return true;
        }
        return false;
    }

    /** True when Attack, Request Help, or Rally is visible on the World merc action popup. */
    private boolean mercActionPopupOpen() {
        ImageSearchResultData attack = templateSearchHelper.locatePattern(
                TemplatesEnum.MERCENARY_ATTACK_BUTTON, SearchConfigConstants.DEFAULT_SINGLE);
        if (attack.isFound()) {
            return true;
        }
        ImageSearchResultData help = templateSearchHelper.locatePattern(
                TemplatesEnum.MERCENARY_ASK_HELP_BUTTON, SearchConfigConstants.DEFAULT_SINGLE);
        if (help.isFound()) {
            return true;
        }
        ImageSearchResultData rally = templateSearchHelper.locatePattern(
                TemplatesEnum.RALLY_BUTTON, SearchConfigConstants.DEFAULT_SINGLE);
        return rally.isFound();
    }

    private void handleMercenaryEvent() {
        try {
            // Select a mercenary event level if needed
            if (!selectMercenaryEventLevel()) {
                return; // If level selection failed, exit the task
            }

            // Large-font level on the Events screen before Scout/Challenge.
            Integer preScoutLevel = checkMercenaryLevelPreScout();
            if (preScoutLevel != null) {
                lastMercenaryLevel = preScoutLevel;
            }

            // Check for scout or challenge buttons
            ImageSearchResultData eventButton = findMercenaryEventButton();

            if (eventButton == null) {
                // Lv.25 with no Scout/Challenge means the hunt is finished (or waiting
                // on alliance help with no travel control). Do not 5-minute spin.
                if (restIfFinalLevelSettled(preScoutLevel != null ? preScoutLevel : lastMercenaryLevel)) {
                    return;
                }
                respondToOpenedScreen(false);
                return;
            }
            respondToOpenedScreen(true);

            scoutAndAttack(eventButton);
        } catch (Exception e) {
            TaskControlSignals.rethrowControlSignal(e);
            LocalDateTime retryAt = LocalDateTime.now().plusMinutes(30);
            String snapshot = TaskDiagnosticSnapshots.capture(
                    emuManager, EMULATOR_NUMBER, "mercenaryevent", "task-error");
            logError("Mercenary Event stopped on " + e.getClass().getSimpleName()
                    + "; retrying at " + retryAt.format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss"))
                    + "; " + snapshot + ".", e);
            reschedule(retryAt);
        }
    }

    private boolean noteLevelChange(Integer preAttackLevel) {
        boolean sameLevelAsLastTime = false;
        logInfo("Previous mercenary level: " + lastMercenaryLevel);
        Integer currentLevel = preAttackLevel != null ? preAttackLevel : checkMercenaryLevelPreAttack();
        if (currentLevel != null) {
            sameLevelAsLastTime = currentLevel.equals(lastMercenaryLevel);
            lastMercenaryLevel = currentLevel;
        }

        if (sameLevelAsLastTime) {
            attackAttempts++;
            staminaHelper.addStamina(lastStaminaSpent);
            logInfo("Mercenary level is the same as last time, indicating a possible attack loss. Skipping flag selection to use strongest march.");
        } else {
            attackAttempts = 0;
            logInfo("Mercenary level has changed since last time. Using flag selection if enabled.");
        }
        return sameLevelAsLastTime;
    }

    private Integer checkMercenaryLevelPreScout() {
        return checkMercenaryLevel(CommonGameAreas.MERCENARY_LEVEL_PRE_SCOUT_OCR_AREA, "pre-scout");
    }

    private Integer checkMercenaryLevelPreAttack() {
        return checkMercenaryLevel(CommonGameAreas.MERCENARY_LEVEL_PRE_ATTACK_OCR_AREA, "pre-attack");
    }

    private Integer checkMercenaryLevel(AreaData area, String band) {
        Integer level = readMercenaryLevelDigits(area, band);
        Integer sanitized = MercenaryHelpMilestones.sanitizeLevelRead(level, lastMercenaryLevel);
        if (sanitized == null && level != null && !"pre-scout".equals(band)) {
            // Truncated two-digit title (e.g. 25 → 2): retry a wider band once.
            AreaData wider = AreaData.of(
                    Math.max(0, area.topLeft().getX() - 40),
                    Math.max(0, area.topLeft().getY() - 10),
                    Math.min(720, area.bottomRight().getX() + 60),
                    Math.min(1280, area.bottomRight().getY() + 15));
            logWarning("Mercenary level OCR " + level + " looks truncated vs previous "
                    + lastMercenaryLevel + "; retrying wider " + band + " band.");
            level = readMercenaryLevelDigits(wider, band);
            sanitized = MercenaryHelpMilestones.sanitizeLevelRead(level, lastMercenaryLevel);
        }
        if (sanitized == null) {
            if (level != null) {
                logWarning("Rejected mercenary level OCR " + level + " (" + band + ").");
            } else {
                logWarning("No mercenary level found after OCR attempts (" + band + ").");
            }
            return null;
        }

        logInfo("Current mercenary level: " + sanitized + " (" + band + ").");
        return sanitized;
    }

    private Integer readMercenaryLevelDigits(AreaData area, String band) {
        // Pre-scout badge is white on pink; World popup title is blue on white.
        Color textColor = "pre-scout".equals(band)
                ? new Color(255, 255, 255)
                : new Color(40, 90, 180);
        OcrSettingsData configs = OcrSettingsData.assembler()
                .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)
                .stripBackground(true)
                .setTextColor(textColor)
                .charWhitelist("0123456789")
                .build();

        Integer level = readNumberValue(area.topLeft(), area.bottomRight(), configs);
        if (level == null && !"pre-scout".equals(band)) {
            OcrSettingsData whiteFallback = OcrSettingsData.assembler()
                    .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)
                    .stripBackground(true)
                    .setTextColor(new Color(255, 255, 255))
                    .charWhitelist("0123456789")
                    .build();
            level = readNumberValue(area.topLeft(), area.bottomRight(), whiteFallback);
        }
        return level;
    }

    /**
     * @return true when the visit was fully handled (help tapped or short retry scheduled)
     */
    private boolean requestHelpWhenMilestone(Integer level) {
        if (!MercenaryHelpMilestones.requiresAskForHelp(level)) {
            return false;
        }

        ImageSearchResultData help = templateSearchHelper.locatePattern(
                TemplatesEnum.MERCENARY_ASK_HELP_BUTTON,
                SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (!help.isFound()) {
            if (MercenaryHelpMilestones.isFinalLevel(level)) {
                return restUntilDailyReset(
                        "Mercenary final level " + level
                                + " has no Request Help control (help already sent or hunt finished).");
            }
            LocalDateTime retryAt = LocalDateTime.now().plus(HELP_MISSING_RETRY);
            String snapshot = TaskDiagnosticSnapshots.capture(
                    emuManager, EMULATOR_NUMBER, "mercenaryevent", "ask-help-missing");
            logWarning("Mercenary level " + level + " requires Ask for Help but the control was absent. Retrying at "
                    + stamp(retryAt) + "; " + snapshot + ".");
            reschedule(retryAt);
            return true;
        }

        logInfo("Mercenary level " + level + " is a help milestone. Tapping Request Help (no Attack march).");
        tapInside(help);
        sleepTask(1500);

        ImageSearchResultData confirm = templateSearchHelper.locatePattern(
                TemplatesEnum.MERCENARY_ASK_HELP_CONFIRM,
                SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (!confirm.isFound()) {
            LocalDateTime retryAt = LocalDateTime.now().plus(HELP_MISSING_RETRY);
            String snapshot = TaskDiagnosticSnapshots.capture(
                    emuManager, EMULATOR_NUMBER, "mercenaryevent", "ask-help-confirm-missing");
            logWarning("Request Help opened but Confirm was absent. Retrying at "
                    + stamp(retryAt) + "; " + snapshot + ".");
            reschedule(retryAt);
            return true;
        }

        logInfo("Confirming Request Help dialog.");
        tapInside(confirm);
        sleepTask(1500);
        LocalDateTime next = LocalDateTime.now().plus(HELP_RETRY_DELAY);
        reschedule(next);
        if (MercenaryHelpMilestones.isFinalLevel(level)) {
            logInfo("Final help milestone (Lv." + level + ") confirmed. One verify visit at "
                    + stamp(next) + "; then rest until daily reset if still finished.");
        } else {
            logInfo("Request Help confirmed. Next visit at " + stamp(next) + ".");
        }
        return true;
    }

    /**
     * Level 25 with no Ask Help left: do not Attack. Rest until daily reset after
     * the post-help verify visit.
     */
    private boolean restIfFinalLevelSettled(Integer level) {
        if (!MercenaryHelpMilestones.isFinalLevel(level)) {
            return false;
        }
        return restUntilDailyReset(
                "Mercenary final level " + level + " reached; not starting a solo Attack.");
    }

    private boolean restUntilDailyReset(String reason) {
        LocalDateTime next = GameTimeUtils.dailyResetTime();
        String snapshot = TaskDiagnosticSnapshots.capture(
                emuManager, EMULATOR_NUMBER, "mercenaryevent", "final-level-rest");
        logInfo(reason + " Next visit at " + stamp(next) + "; " + snapshot + ".");
        reschedule(next);
        return true;
    }

    private boolean selectMercenaryEventLevel() {
        // Try each initiation type in order: Legends -> Epic -> Champions
        String[] initiationTypes = { "Legends", "Epic", "Champions" };
        TemplatesEnum[] unselectedTemplates = {
                TemplatesEnum.MERCENARY_LEGENDS_INITIATION_UNSELECTED,
                TemplatesEnum.MERCENARY_EPIC_INITIATION_UNSELECTED,
                TemplatesEnum.MERCENARY_CHAMPIONS_INITIATION_UNSELECTED
        };
        TemplatesEnum[] selectedTemplates = {
                TemplatesEnum.MERCENARY_LEGENDS_INITIATION_SELECTED,
                TemplatesEnum.MERCENARY_EPIC_INITIATION_SELECTED,
                TemplatesEnum.MERCENARY_CHAMPIONS_INITIATION_SELECTED
        };

        for (int i = 0; i < initiationTypes.length; i++) {
            boolean tabIsSelected = false;

            // First check if this initiation type is already selected
            ImageSearchResultData alreadySelectedTab = templateSearchHelper.locatePattern(
                    selectedTemplates[i],
                    SearchConfigConstants.SINGLE_WITH_RETRIES);

            if (alreadySelectedTab.isFound()) {
                logInfo(initiationTypes[i]
                        + " Initiation tab is already selected. Proceeding with difficulty selection.");
                tabIsSelected = true;
            } else {
                // Tab is not selected, check if it's unselected (available to select)
                ImageSearchResultData unselectedTab = templateSearchHelper.locatePattern(
                        unselectedTemplates[i],
                        SearchConfigConstants.SINGLE_WITH_RETRIES);

                if (unselectedTab.isFound()) {
                    logInfo("Found unselected " + initiationTypes[i] + " Initiation tab. Tapping to open.");

                    // Tap the unselected tab to open it
                    tapInside(unselectedTab);
                    sleepTask(1500);

                    // Verify that the tab changed to selected (not locked)
                    ImageSearchResultData selectedTab = templateSearchHelper.locatePattern(
                            selectedTemplates[i],
                            SearchConfigConstants.SINGLE_WITH_RETRIES);

                    if (selectedTab.isFound()) {
                        tabIsSelected = true;
                    } else {
                        logDebug(initiationTypes[i] + " Initiation tab is locked. Skipping to next type.");
                    }
                }
            }

            if (tabIsSelected) {
                logInfo(initiationTypes[i] + " Initiation tab is open. Attempting to select difficulty.");

                // Now select a difficulty within this initiation type
                // Define difficulties in order from highest to lowest
                record DifficultyLevel(String name, PointData point) {
                }
                DifficultyLevel[] difficultyLevels = {
                        new DifficultyLevel("Insane", new PointData(467, 1088)),
                        new DifficultyLevel("Nightmare", new PointData(252, 1088)),
                        new DifficultyLevel("Hard", new PointData(575, 817)),
                        new DifficultyLevel("Normal", new PointData(360, 817)),
                        new DifficultyLevel("Easy", new PointData(145, 817))
                };

                for (DifficultyLevel level : difficultyLevels) {
                    logDebug("Attempting to select difficulty: " + level.name() + " in " + initiationTypes[i]
                            + " Initiation.");
                    tapNear(level.point());
                    sleepTask(2000);
                    ImageSearchResultData challengeCheck = templateSearchHelper.locatePattern(
                            TemplatesEnum.MERCENARY_DIFFICULTY_CHALLENGE,
                            SearchConfigConstants.SINGLE_WITH_RETRIES);
                    if (challengeCheck.isFound()) {
                        sleepTask(1000);
                        tapInside(challengeCheck);
                        sleepTask(1000);
                        tapNear(new PointData(504, 788)); // Tap the confirm button
                        logInfo("Selected mercenary event difficulty: " + level.name() + " in " + initiationTypes[i]
                                + " Initiation.");
                        sleepTask(2000);
                        return true;
                    }
                    sleepTask(1000);
                    pressBack();
                }
            }
        }

        // If no tab was found at all, a level was already selected beforehand
        logInfo("No initiation type found, assuming one was already selected beforehand. Proceeding.");
        return true;
    }

    EventMenuOpenResult openMercenaryMenu() {
        logInfo("Navigating to Mercenary event...");

        EventMenuOpenResult opened = navigationHelper.openEventMenu(EventMenu.MERCENARY);

        if (opened != EventMenuOpenResult.REACHED) {
            logWarning("Failed to navigate to Mercenary event");
            return opened;
        }

        sleepTask(2000);
        return opened;
    }

    void respondToMenu(EventMenuOpenResult opened) {
        if (opened == EventMenuOpenResult.REACHED) {
            menuRetry.found();
            return;
        }
        if (opened == EventMenuOpenResult.PANEL_CLOSED) {
            LocalDateTime retryAt = EventPeriodVisit.retryAt(LocalDateTime.now());
            logWarning("Events panel did not open. Retrying at " + stamp(retryAt) + "; "
                    + diagnosticSnapshot("event-panel") + ".");
            reschedule(retryAt);
            return;
        }

        EventMenuRetryState.Choice choice = menuRetry.choose(LocalDateTime.now(), GameTimeUtils.dailyResetTime());
        String snapshot = diagnosticSnapshot("event-navigation");
        if (!choice.resting()) {
            logWarning("Mercenary event tab was not in view. One more menu visit at " + stamp(choice.at())
                    + "; " + snapshot + ".");
        } else {
            logWarning("Mercenary event tab was still not in view after the extra menu visit. Next visit at "
                    + stamp(choice.at()) + "; hunt was not completed; " + snapshot + ".");
        }
        reschedule(choice.at());
    }

    void respondToOpenedScreen(boolean huntControlPresent) {
        EventPeriodVisit.OpenedScreen screen = EventPeriodVisit.openedScreen(
                huntControlPresent,
                EventPeriodTemplates.installed(EventPeriodTemplates.MERCENARY_COMPLETION),
                EventPeriodTemplates.installed(EventPeriodTemplates.MERCENARY_START_COUNTDOWN));
        noteEndCountdown(screen == EventPeriodVisit.OpenedScreen.COMPLETED);
        if (screen == EventPeriodVisit.OpenedScreen.ACTIVE) {
            return;
        }

        LocalDateTime next = EventPeriodVisit.nextVisit(
                screen, LocalDateTime.now(), GameTimeUtils.dailyResetTime(), null);
        if (screen == EventPeriodVisit.OpenedScreen.COMPLETED) {
            logInfo("Mercenary hunt completed. Next visit at " + stamp(next) + ".");
            reschedule(next);
            return;
        }

        String line = "Mercenary hunt controls were absent and completion is not confirmed. Retrying at "
                + stamp(next);
        String missing = MissingTemplateSnapshotSettings.note(
                MissingTemplateSnapshotSettings.enabled(),
                EventPeriodTemplates.MERCENARY_COMPLETION,
                () -> diagnosticSnapshot("completion-template"));
        logWarning(missing.isEmpty() ? line + "." : line + "; " + missing + ".");
        reschedule(next);
    }

    private void noteEndCountdown(boolean completionConfirmed) {
        if (!EventPeriodVisit.endCountdownUnconfirmed(
                EventPeriodTemplates.installed(EventPeriodTemplates.MERCENARY_END_COUNTDOWN),
                completionConfirmed)) {
            return;
        }
        logError("Mercenary end countdown was read and the hunt is not confirmed; "
                + diagnosticSnapshot("end-countdown") + ".");
    }

    String diagnosticSnapshot(String type) {
        return TaskDiagnosticSnapshots.capture(emuManager, EMULATOR_NUMBER, "mercenaryevent", type);
    }

    private static String stamp(LocalDateTime time) {
        return time.format(DATETIME_FORMATTER);
    }

    /**
     * Finds either the scout button or challenge button for the mercenary event.
     * 
     * @return The search result of the found button, or null if neither button is
     *         found
     */
    private ImageSearchResultData findMercenaryEventButton() {
        logInfo("Checking for mercenary event buttons.");

        // First check for scout button
        ImageSearchResultData scoutButton = templateSearchHelper.locatePattern(
                TemplatesEnum.MERCENARY_SCOUT_BUTTON,
                SearchConfigConstants.SINGLE_WITH_RETRIES);

        if (scoutButton.isFound()) {
            scout = true;
            logInfo("Found scout button for mercenary event.");
            return scoutButton;
        }

        // If scout button not found, check for challenge button
        ImageSearchResultData challengeButton = templateSearchHelper.locatePattern(
                TemplatesEnum.MERCENARY_CHALLENGE_BUTTON,
                SearchConfigConstants.SINGLE_WITH_RETRIES);

        if (challengeButton.isFound()) {
            scout = false;
            logInfo("Found challenge button for mercenary event.");
            return challengeButton;
        }

        logInfo("Neither scout nor challenge button found for mercenary event.");
        return null;
    }

    private void scoutAndAttack(ImageSearchResultData eventButton) {
        logInfo("Starting scout/attack process for mercenary event.");

        if (eventButton == null) {
            respondToOpenedScreen(false);
            return;
        }

        // Click on the button (whether it's scout or challenge)
        tapInside(eventButton);
        sleepTask(4000); // Wait to travel to mercenary location on map

        boolean sameLevelAsLastTime = noteLevelChange(checkMercenaryLevelPreAttack());
        if (requestHelpWhenMilestone(lastMercenaryLevel)) {
            return;
        }
        if (restIfFinalLevelSettled(lastMercenaryLevel)) {
            return;
        }
        attackFromWorldPopup(sameLevelAsLastTime);
    }

    private void attackFromWorldPopup(boolean sameLevelAsLastTime) {
        // Determine whether to rally or attack
        boolean rally = false;
        ImageSearchResultData attackOrRallyButton = null;

        if (attackAttempts > 3) {
            logWarning(
                    "Multiple consecutive attack attempts detected without level change. Rallying the mercenary instead of normal attack.");
            attackOrRallyButton = templateSearchHelper.locatePattern(
                    TemplatesEnum.RALLY_BUTTON,
                    SearchConfigConstants.SINGLE_WITH_RETRIES);
            rally = true;
        } else {
            attackOrRallyButton = templateSearchHelper.locatePattern(
                    TemplatesEnum.MERCENARY_ATTACK_BUTTON,
                    SearchConfigConstants.SINGLE_WITH_RETRIES);
        }

        if (attackOrRallyButton == null || !attackOrRallyButton.isFound()) {
            logWarning("Attack/Rally button not found after scouting/challenging. Retrying in 5 minutes.");
            reschedule(LocalDateTime.now().plusMinutes(5));
            return;
        }

        if (scout) {
            logInfo("Scout outcome confirmed by the attack/rally control. Decreasing stamina by 15.");
            StaminaService.getServices().subtractStamina(profile.getId(), 15);
        }

        if (rally && deferIfBearTrapBlocksRallyStart()) {
            return;
        }
        logInfo(rally ? "Rallying mercenary." : "Attacking mercenary.");
        tapInside(attackOrRallyButton);
        sleepTask(1000);

        if (rally) {
            tapInside(new PointData(275, 821), new PointData(444, 856));
            sleepTask(500);
        }

        // Check if the march screen is open before proceeding
        ImageSearchResultData deployButton = templateSearchHelper.locatePattern(
                TemplatesEnum.DEPLOY_BUTTON,
                SearchConfigConstants.SINGLE_WITH_RETRIES);

        if (!deployButton.isFound()) {
            logError(
                    "March queue is full or another issue occurred. Cannot start a new march. Retrying in 10 minutes.");
            reschedule(LocalDateTime.now().plusMinutes(10));
            return;
        }

        // Check if we should use a specific flag
        if (useFlag && !sameLevelAsLastTime) {
            if (!marchHelper.selectFlag(flagNumber)) {
                logWarning("Configured formation #" + flagNumber + " is unavailable. Cancelling deployment.");
                pressBack();
                reschedule(LocalDateTime.now().plusMinutes(5));
                return;
            }
            sleepTask(300);
        }

        int maxStaminaCost = rally
                ? DeploymentHelper.MAX_RALLY_STAMINA_COST
                : DeploymentHelper.MAX_ATTACK_STAMINA_COST;
        var deployment = deploymentHelper.readScreen(maxStaminaCost);
        long travelTimeSeconds = deployment.travelTimeSeconds();
        int spentStamina = deployment.staminaCost();
        lastStaminaSpent = spentStamina;
        if (deploymentHelper.hasNoDeployableTroops() || deploymentHelper.isDeployCostRed()) {
            logWarning("Deployment blocked by troops or stamina. No march was sent or deducted.");
            pressBack();
            reschedule(LocalDateTime.now().plusMinutes(5));
            return;
        }

        if (rally && deferIfBearTrapBlocksRallyStart()) {
            return;
        }

        // Validate travel time before deploying
        if (travelTimeSeconds <= 0) {
            logError("Failed to parse valid march time via OCR. Using conservative 10 minute fallback reschedule.");
            tapInside(deployButton); // Deploy anyway since we're already in the march screen
            sleepTask(2000);

            if (deploymentHelper.isSameTargetDialog()) {
                logWarning("Another march is already targeting this mercenary. Cancelling deployment.");
                pressBack();
                pressBack();
                reschedule(LocalDateTime.now().plusMinutes(1));
                return;
            }
            DeploymentHelper.LaunchCheck launchCheck = deploymentHelper.verifyLaunchTransition();
            if (launchCheck != DeploymentHelper.LaunchCheck.WORLD_VERIFIED) {
                String snapshot = TaskDiagnosticSnapshots.capture(
                        emuManager, EMULATOR_NUMBER, "mercenaryevent", "deployment-unknown-travel");
                logWarning("Deployment was not verified (" + launchCheck + "); stamina was not deducted. " + snapshot);
                reschedule(LocalDateTime.now().plusMinutes(5));
                return;
            }

            // Update stamina with fallback
            staminaHelper.subtractStamina(spentStamina, rally);

            // Reschedule with conservative estimate
            LocalDateTime fallbackTime = LocalDateTime.now().plusMinutes(10);
            reschedule(fallbackTime);
            logInfo("Mercenary march deployed with unknown return time. Task will retry at " +
                    fallbackTime.format(DateTimeFormatter.ofPattern("HH:mm:ss")));
            return;
        }

        // Deploy march with known travel time
        tapInside(deployButton);
        sleepTask(2000);

        if (deploymentHelper.isSameTargetDialog()) {
            logWarning("Another march is already targeting this mercenary. Cancelling deployment.");
            pressBack();
            pressBack();
            reschedule(LocalDateTime.now().plusMinutes(1));
            return;
        }

        // Verify deployment succeeded
        DeploymentHelper.LaunchCheck launchCheck = deploymentHelper.verifyLaunchTransition();
        if (launchCheck != DeploymentHelper.LaunchCheck.WORLD_VERIFIED) {
            String snapshot = TaskDiagnosticSnapshots.capture(
                    emuManager, EMULATOR_NUMBER, "mercenaryevent", "deployment-unknown");
            logWarning("Deployment was not verified (" + launchCheck + "); stamina was not deducted. " + snapshot);
            reschedule(LocalDateTime.now().plusMinutes(5));
            return;
        }

        logInfo("March deployed successfully.");

        // Calculate return time
        long returnTimeSeconds = (travelTimeSeconds * 2) + 2;
        LocalDateTime rescheduleTime = rally
                ? LocalDateTime.now().plusSeconds(returnTimeSeconds).plusMinutes(5)
                : LocalDateTime.now().plusSeconds(returnTimeSeconds);

        reschedule(rescheduleTime);

        // Update stamina
        staminaHelper.subtractStamina(spentStamina, rally);

        logInfo("Mercenary march sent. Task will run again at " +
                rescheduleTime.format(DateTimeFormatter.ofPattern("HH:mm:ss")) +
                " (in " + (returnTimeSeconds / 60) + " minutes).");
    }

    @Override
    public LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.WORLD;
    }

    @Override
    protected boolean consumesStamina() {
        return true;
    }

}
