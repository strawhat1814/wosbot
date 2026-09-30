/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  dev.frostguard.api.configs.TemplatesEnum
 *  dev.frostguard.api.configs.TpDailyTaskEnum
 *  dev.frostguard.api.domain.AccountDescriptor
 *  dev.frostguard.api.domain.AreaData
 *  dev.frostguard.api.domain.ImageSearchResultData
 *  dev.frostguard.api.domain.OcrSettingsData
 *  dev.frostguard.api.domain.PointData
 *  dev.frostguard.engine.helper.TemplateSearchHelper
 *  dev.frostguard.engine.helper.TemplateSearchHelper$SearchConfig
 *  dev.frostguard.engine.nav.LeftMenuTextSettings
 *  dev.frostguard.engine.nav.SearchConfigConstants
 *  dev.frostguard.engine.nav.SidebarSection
 *  dev.frostguard.engine.schedule.DelayedTask
 *  dev.frostguard.engine.schedule.LaunchPoint
 *  dev.frostguard.tasks.city.BuildingUpgradeConfirmationFlow
 *  dev.frostguard.tasks.city.BuildingUpgradeConfirmationFlow$Outcome
 *  dev.frostguard.tasks.city.BuildingUpgradeConfirmationFlow$Ui
 *  dev.frostguard.tasks.city.GrowthMissionBuildRoutine$FurnitureResource
 *  dev.frostguard.tasks.city.GrowthMissionBuildRoutine$OpenGrowthResult
 *  dev.frostguard.tasks.city.GrowthMissionBuildRoutine$UpgradeOutcome
 *  dev.frostguard.tasks.city.RepeatedResourceReplenishmentFlow
 *  dev.frostguard.tasks.city.RepeatedResourceReplenishmentFlow$Result
 *  dev.frostguard.tasks.city.RepeatedResourceReplenishmentFlow$Ui
 *  dev.frostguard.vision.convert.GameTimeUtils
 */
package dev.frostguard.tasks.city;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.helper.TemplateSearchHelper;
import dev.frostguard.engine.nav.LeftMenuTextSettings;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.nav.SidebarSection;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.StatisticsService;
import dev.frostguard.engine.service.TelegramBotService;
import dev.frostguard.vision.convert.GameTimeUtils;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;

public class GrowthMissionBuildRoutine
extends DelayedTask {
    private static final int MAX_FURNITURE_STEPS = 16;
    private static final int MAX_RESOURCE_REPLENISHMENTS = 4;
    private static final int MAX_CLAIM_TAPS = 12;
    private static final int COMPLETION_SETTLE_SECONDS = 2;
    private static final int DEFAULT_RETRY_MINUTES = 20;
    private static final int FURNITURE_PROGRESS_RETRY_MINUTES = 3;
    private static final int NO_MAIN_ACTION_RETRY_HOURS = 3;
    private static final int STEEL_WAIT_MINUTES = 15;
    /** Fast furniture: every hold is a fixed 2s ADB swipe, then inspect. */
    private static final int FAST_FURNITURE_HOLD_MS = 2000;
    /** Cap hold bursts per run (Next taps do not count). Live Carl/Stuart needed 2. */
    private static final int FAST_FURNITURE_MAX_HOLDS = 8;
    /** Piece Upgrade sits below the building header Upgrade band (header area ends ~y 610). */
    private static final int FURNITURE_UPGRADE_MIN_Y = 620;
    private static final double RESOURCE_STEEL_GUARD_THRESHOLD = 65.0;
    private static final AreaData MISSIONS_BUTTON_AREA = new AreaData(new PointData(15, 1025), new PointData(95, 1085));
    private static final PointData REPLENISH_CONFIRM_POINT = new PointData(511, 1056);
    private static final AreaData GROWTH_TITLE_AREA = new AreaData(new PointData(180, 40), new PointData(540, 110));
    private static final AreaData GROWTH_TAB_AREA = new AreaData(new PointData(0, 1145), new PointData(720, 1205));
    private static final AreaData DAILY_TAB_SELECTED_AREA = new AreaData(new PointData(360, 1145), new PointData(720, 1205));
    /**
     * Fallback Growth-tab tap when templates miss. Keep this on the left blue pill only —
     * lower/darker pixels beside the missions panel dismiss the window, and the Heroes
     * home nav sits immediately under the pill (HeroRecruit ~160–217, y≥1190).
     */
    private static final AreaData GROWTH_TAB_CLICK_AREA = new AreaData(new PointData(185, 1160), new PointData(250, 1172));
    /** Prefer a fixed center tap over a wide random area so jitter cannot reach Heroes. */
    private static final PointData GROWTH_TAB_FALLBACK_POINT = new PointData(210, 1166);
    private static final int GROWTH_TAB_TAP_RADIUS = 3;
    /** Clamp template-hit taps above Heroes home nav (live unselected ~y 1181 is too low). */
    private static final int GROWTH_TAB_SAFE_Y_MIN = 1158;
    private static final int GROWTH_TAB_SAFE_Y_MAX = 1174;
    private static final AreaData DAILY_TITLE_AREA = new AreaData(new PointData(180, 40), new PointData(540, 170));
    /**
     * Main Growth mission Go/Claim — right column of the upper mission card.
     * Live Main Go ~y 341; Main Claim ~y 571. Lower Side/list Go near y~910 must not match.
     */
    private static final AreaData MAIN_GO_CLAIM_AREA = new AreaData(new PointData(480, 140), new PointData(720, 620));
    /**
     * Main Go only — keep above the Claim row. Live Kevin matched Go at y~577 (Claim band)
     * at 96% and then took the building road with no city up-arrow (queue still Idle).
     */
    private static final AreaData MAIN_GO_AREA = new AreaData(new PointData(480, 140), new PointData(720, 480));
    private static final int MAIN_GO_MAX_Y = 480;
    private static final AreaData ALL_COMPLETE_OCR_AREA = new AreaData(new PointData(80, 400), new PointData(640, 900));
    private static final AreaData HEADER_UPGRADE_AREA = new AreaData(new PointData(540, 500), new PointData(720, 610));
    /** Piece Upgrade only — keep below header Upgrade (~y 500–610) so holds never hit main. */
    private static final AreaData FURNITURE_UPGRADE_AREA = new AreaData(new PointData(500, 620), new PointData(720, 760));
    /**
     * Blue "Next" on the furniture detail card (live ~577,650). Same band as Upgrade,
     * kept as its own area so Next search can be tuned independently.
     */
    private static final AreaData FURNITURE_NEXT_AREA = new AreaData(new PointData(520, 620), new PointData(700, 720));
    /**
     * Bottom-left "Furniture" tab/label that identifies the furniture upgrade panel.
     * Live marker ~ (136,1195) on Kevin furniture frames.
     */
    private static final AreaData FURNITURE_PANEL_AREA = new AreaData(new PointData(100, 1160), new PointData(300, 1260));
    private static final AreaData CITY_UPGRADE_HEX_TAP_AREA = new AreaData(new PointData(385, 855), new PointData(455, 930));
    /**
     * After Growth Go, tap here (with area jitter) to dismiss the tutorial guide hand /
     * highlight circles that obscure the Upgrade arrow. Building road only — furniture
     * never uses this. Allowed twice if the up-arrow is still missing after the first tap.
     * Anchored ~30px below screen center so the tap hits the selected building, not HUD.
     */
    private static final AreaData BUILDING_CENTER_DISMISS_AREA =
            new AreaData(new PointData(300, 590), new PointData(420, 750));
    private static final int BUILDING_CENTER_DISMISS_SETTLE_MS = 1200;
    private static final int MAX_GUIDE_CENTER_DISMISS = 2;
    /** Building-road center dismiss count for this execute (furniture never increments). */
    private int growthGuideDismissCount;
    /**
     * City view after Growth Go — blue up-arrow bubble on the selected building.
     * Avoids bottom nav / mission tabs.
     */
    private static final AreaData CITY_UPGRADE_ARROW_AREA =
            new AreaData(new PointData(40, 120), new PointData(680, 1050));
    /**
     * Blue Upgrade on the building requirement dialog (right; Finish/gems stay left).
     * Used after city hex tap so we do not hang in panel-Upgrade template locate.
     */
    private static final AreaData BUILDING_DIALOG_UPGRADE_TAP_AREA =
            new AreaData(new PointData(500, 1100), new PointData(680, 1230));
    private static final AreaData BUILDING_PANEL_UPGRADE_AREA = new AreaData(new PointData(380, 1000), new PointData(700, 1220));
    private static final AreaData RESOURCE_ICON_AREA = new AreaData(new PointData(500, 620), new PointData(640, 740));
    private static final AreaData BUILDING_CONFIRM_UPGRADE_AREA = new AreaData(new PointData(350, 900), new PointData(700, 1255));
    private static final AreaData QUEUE_AREA_1 = new AreaData(new PointData(95, 370), new PointData(358, 407));
    private static final AreaData QUEUE_AREA_2 = new AreaData(new PointData(95, 443), new PointData(358, 480));
    private static final TemplateSearchHelper.SearchConfig GROWTH_TITLE_SEARCH = TemplateSearchHelper.SearchConfig.builder().withMaxAttempts(3).withDelay(300L).withThreshold(90).withArea(GROWTH_TITLE_AREA).build();
    private static final TemplateSearchHelper.SearchConfig DAILY_TITLE_SEARCH = TemplateSearchHelper.SearchConfig.builder().withMaxAttempts(2).withDelay(250L).withThreshold(85).withArea(DAILY_TITLE_AREA).build();
    private static final TemplateSearchHelper.SearchConfig GROWTH_TAB_SEARCH = TemplateSearchHelper.SearchConfig.builder().withMaxAttempts(3).withDelay(300L).withThreshold(85).withArea(GROWTH_TAB_AREA).build();
    private static final TemplateSearchHelper.SearchConfig DAILY_TAB_SELECTED_SEARCH = TemplateSearchHelper.SearchConfig.builder().withMaxAttempts(2).withDelay(200L).withThreshold(85).withArea(DAILY_TAB_SELECTED_AREA).build();
    private static final TemplateSearchHelper.SearchConfig GO_MAIN_SEARCH;
    private static final TemplateSearchHelper.SearchConfig GO_SCORE_PROBE;
    private static final TemplateSearchHelper.SearchConfig CLAIM_QUICK_SEARCH;
    private static final TemplateSearchHelper.SearchConfig CLAIM_FALLBACK_QUICK_SEARCH;
    private static final TemplateSearchHelper.SearchConfig HEADER_UPGRADE_SEARCH;
    private static final TemplateSearchHelper.SearchConfig FURNITURE_UPGRADE_SEARCH;
    private static final TemplateSearchHelper.SearchConfig FURNITURE_NEXT_SEARCH;
    private static final TemplateSearchHelper.SearchConfig FURNITURE_NEXT_SCORE_PROBE;
    private static final TemplateSearchHelper.SearchConfig FURNITURE_PANEL_SEARCH;
    private static final TemplateSearchHelper.SearchConfig CITY_UPGRADE_ARROW_SEARCH;
    private static final TemplateSearchHelper.SearchConfig CITY_UPGRADE_ARROW_SCORE_PROBE;
    private static final TemplateSearchHelper.SearchConfig BUILDING_PANEL_UPGRADE_SEARCH;
    private static final TemplateSearchHelper.SearchConfig REPLENISH_BUTTON_RECHECK;
    private static final TemplateSearchHelper.SearchConfig BUILDING_CONFIRM_UPGRADE_SEARCH;
    private static final TemplateSearchHelper.SearchConfig BUILDING_CONFIRM_UPGRADE_RELAXED;
    private static final TemplateSearchHelper.SearchConfig BUILDING_CONFIRM_UPGRADE_POSTCONDITION;
    private static final TemplateSearchHelper.SearchConfig BUILDING_CONFIRM_UPGRADE_SCORE_PROBE;

    public GrowthMissionBuildRoutine(AccountDescriptor profile, TpDailyTaskEnum taskType) {
        super(profile, taskType);
    }

    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.HOME;
    }

    protected void execute() {
        // Task instances are reused across runs — reset per-execute dismiss budget.
        this.growthGuideDismissCount = 0;
        OpenGrowthResult openResult = this.openGrowthMissionsAndTapGo();
        if (openResult == OpenGrowthResult.ALL_COMPLETE) {
            this.logInfo(this.logLine("All Growth missions complete \u2014 rescheduling for daily reset."));
            this.notifyTelegram("All Growth missions complete");
            this.dismissToHome();
            this.reschedule(GameTimeUtils.dailyResetTime());
            return;
        }
        if (openResult == OpenGrowthResult.NO_MAIN_ACTION) {
            this.logInfo(this.logLine("No Main Claim/Go \u2014 dismissing and retrying in "
                    + NO_MAIN_ACTION_RETRY_HOURS + "h"));
            this.dismissToHome();
            this.reschedule(LocalDateTime.now().plusHours(NO_MAIN_ACTION_RETRY_HOURS));
            return;
        }
        if (openResult != OpenGrowthResult.READY_FOR_BUILD) {
            this.dismissToHome();
            this.reschedule(LocalDateTime.now().plusMinutes(DEFAULT_RETRY_MINUTES));
            return;
        }
        UpgradeOutcome outcome = this.performBuildingUpgrades();
        if (outcome.avoidBackOnExit()) {
            // Back/ESC after a failed city up-arrow search closes the upgrade bubble.
            this.recoverHomeWithoutBack();
        } else {
            this.dismissToHome();
        }
        if (outcome.startedConstruction()) {
            this.notifyTelegram("Growth construction started");
            StatisticsService.obtain().addToCounter(this.profile, "Growth Construction Started", 1);
            this.scheduleAfterConstruction();
        } else if (outcome.cityArrowMissed()) {
            // No up-arrow usually means the selected building is already under construction
            // (Growth cannot start the next building until that queue finishes).
            this.logInfo(this.logLine(
                    "City up-arrow miss — checking construction queue before retrying Growth"));
            if (!this.tryScheduleFromConstructionQueue("city up-arrow miss")) {
                this.reschedule(LocalDateTime.now().plusMinutes(DEFAULT_RETRY_MINUTES));
            }
        } else if (outcome.steelBlocked()) {
            this.logInfo(this.logLine("Steel guard blocked furniture spend. Retrying in 15 minutes."));
            this.reschedule(LocalDateTime.now().plusMinutes(STEEL_WAIT_MINUTES));
        } else if (outcome.furnitureProgress()) {
            this.notifyTelegram("Growth furniture progress");
            StatisticsService.obtain().addToCounter(this.profile, "Growth Furniture Upgrades", 1);
            this.logInfo(this.logLine("Furniture pieces progressed — retrying in "
                    + FURNITURE_PROGRESS_RETRY_MINUTES + " minutes"));
            this.reschedule(LocalDateTime.now().plusMinutes(FURNITURE_PROGRESS_RETRY_MINUTES));
        } else {
            this.reschedule(LocalDateTime.now().plusMinutes(DEFAULT_RETRY_MINUTES));
        }
    }

    private OpenGrowthResult openGrowthMissionsAndTapGo() {
        this.logInfo(this.logLine("Opening missions panel"));
        this.tapInside(MISSIONS_BUTTON_AREA);
        this.sleepTask(2500L);
        if (!this.ensureGrowthTabSelected()) {
            // Live Stuart 2026-09-19 14:01: first open after Storehouse left no missions chrome.
            this.logInfo(this.logLine("Missions panel missing after first open — retrying missions button"));
            this.tapInside(MISSIONS_BUTTON_AREA);
            this.sleepTask(2800L);
            if (!this.ensureGrowthTabSelected()) {
                this.logWarning(this.logLine("Could not reach Growth Missions tab"));
                return OpenGrowthResult.FAILED;
            }
        }
        // Claim-first: drain every Main Claim before looking for Go.
        this.sleepTask(900L);
        this.logInfo(this.logLine("Looking for Growth mission Claim (before Go)"));
        int claimed = this.claimAllReadyGrowthMissions();
        if (claimed > 0) {
            this.logInfo(this.logLine("Claimed " + claimed + " Growth mission reward(s)"));
            StatisticsService.obtain().addToCounter(this.profile, "Growth Missions Claimed", claimed);
            this.sleepTask(800L);
        }
        this.logInfo(this.logLine("Looking for Growth mission Go"));
        if (this.tapAnyGoButton()) {
            return OpenGrowthResult.READY_FOR_BUILD;
        }
        if (this.isAllGrowthMissionsComplete()) {
            return OpenGrowthResult.ALL_COMPLETE;
        }
        this.logGoMiss();
        return OpenGrowthResult.NO_MAIN_ACTION;
    }

    private boolean tapAnyGoButton() {
        ImageSearchResultData mainGo = this.templateSearchHelper.locatePattern(TemplatesEnum.GROWTH_MISSION_GO_BUTTON_MAIN, GO_MAIN_SEARCH);
        if (mainGo.isFound()) {
            PointData goPoint = mainGo.getPoint();
            if (goPoint != null && goPoint.getY() > MAIN_GO_MAX_Y) {
                this.logWarning(this.logLine(String.format(Locale.ROOT,
                        "Ignoring Main Go match at %s (below Go band y<=%d — Claim-row false hit)",
                        goPoint, MAIN_GO_MAX_Y)));
                return false;
            }
            this.logInfo(this.logLine(String.format(Locale.ROOT, "Tapping Main mission Go at %s (score %.1f)", goPoint, mainGo.getMatchScore())));
            this.tapInside(mainGo);
            this.sleepTask(2000L);
            return true;
        }
        // Side / lower-list Go is intentionally unused — those rows false-match Main builds (~y 910).
        return false;
    }

    private int claimAllReadyGrowthMissions() {
        int claimed = 0;
        for (int i = 0; i < MAX_CLAIM_TAPS; ++i) {
            ImageSearchResultData claim;
            try {
                claim = this.findGrowthClaimButtonQuick();
            }
            catch (Exception e) {
                this.logWarning(this.logLine("Claim search error \u2014 stopping Claim loop: " + e.getMessage()));
                break;
            }
            if (!claim.isFound()) {
                if (claimed == 0) {
                    this.logInfo(this.logLine(String.format(Locale.ROOT, "Claim not visible (best=%.1f) \u2014 checking Go", claim.getMatchScore())));
                } else {
                    this.logInfo(this.logLine(String.format(Locale.ROOT, "No further Claim (best=%.1f) after %d claim(s)", claim.getMatchScore(), claimed)));
                }
                break;
            }
            this.logInfo(this.logLine(String.format(Locale.ROOT, "Tapping Growth Claim at %s (score %.1f)", claim.getPoint(), claim.getMatchScore())));
            this.tapInside(claim);
            ++claimed;
            this.sleepTask(900L);
        }
        if (claimed >= MAX_CLAIM_TAPS) {
            this.logWarning(this.logLine("Claim loop hit safety cap (" + MAX_CLAIM_TAPS + ") \u2014 continuing to Go"));
        }
        return claimed;
    }

    private ImageSearchResultData findGrowthClaimButtonQuick() {
        // Do not fall back to DAILY_MISSION_CLAIM_BUTTON — that fires while still on
        // Daily after a false Growth-tab switch and drains Daily claims instead.
        ImageSearchResultData claim = this.templateSearchHelper.locatePattern(
                TemplatesEnum.GROWTH_MISSION_CLAIM_BUTTON, CLAIM_QUICK_SEARCH);
        if (claim.isFound()) {
            return claim;
        }
        return this.templateSearchHelper.locatePattern(
                TemplatesEnum.GROWTH_MISSION_CLAIM_BUTTON, CLAIM_FALLBACK_QUICK_SEARCH);
    }

    private boolean isAllGrowthMissionsComplete() {
        OcrSettingsData[] presets;
        for (OcrSettingsData preset : presets = new OcrSettingsData[]{LeftMenuTextSettings.WHITE_SETTINGS, LeftMenuTextSettings.WHITE_NUMBERS, LeftMenuTextSettings.ORANGE_SETTINGS}) {
            try {
                String text = this.emuManager.readText(this.EMULATOR_NUMBER, ALL_COMPLETE_OCR_AREA.topLeft(), ALL_COMPLETE_OCR_AREA.bottomRight(), preset, true);
                if (text == null || text.isBlank()) continue;
                String normalized = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ");
                if (!GrowthMissionBuildRoutine.looksLikeAllGrowthComplete(normalized = normalized.replaceAll("\\s+", " ").trim())) continue;
                this.logInfo(this.logLine("Detected all-complete Growth text: \"" + text.trim() + "\""));
                return true;
            }
            catch (Exception exception) {
                // empty catch block
            }
        }
        return false;
    }

    static boolean looksLikeAllGrowthComplete(String normalizedLower) {
        boolean hasMissionContext;
        if (normalizedLower == null || normalizedLower.isBlank()) {
            return false;
        }
        boolean hasComplete = normalizedLower.contains("complet") || normalizedLower.contains("finished") || normalizedLower.contains("done");
        boolean bl = hasMissionContext = normalizedLower.contains("mission") || normalizedLower.contains("growth") || normalizedLower.contains("all");
        if (hasComplete && hasMissionContext) {
            return true;
        }
        return normalizedLower.contains("no available") || normalizedLower.contains("come back later") || normalizedLower.contains("already completed");
    }

    private void logGoMiss() {
        ImageSearchResultData mainProbe = this.templateSearchHelper.locatePattern(TemplatesEnum.GROWTH_MISSION_GO_BUTTON_MAIN, GO_SCORE_PROBE);
        ImageSearchResultData claimProbe = this.templateSearchHelper.locatePattern(TemplatesEnum.GROWTH_MISSION_CLAIM_BUTTON, GO_SCORE_PROBE);
        this.logWarning(this.logLine(String.format(Locale.ROOT, "No Growth mission Main Go in upper band. Best scores main=%.1f claim=%.1f", mainProbe.getMatchScore(), claimProbe.getMatchScore())));
    }

    private boolean ensureGrowthTabSelected() {
        if (!this.isMissionsPanelOpen()) {
            this.logWarning(this.logLine("Missions panel not detected after opening"));
            return false;
        }
        if (this.isOnGrowthMissions() && !this.isOnDailyMissions()) {
            this.logDebug(this.logLine("Already on Growth Missions"));
            return true;
        }
        for (int attempt = 1; attempt <= 3; ++attempt) {
            if (this.isOnDailyMissions()) {
                this.logInfo(this.logLine("Daily Missions detected \u2014 clicking Growth tab (attempt " + attempt + "/3)"));
            } else {
                this.logInfo(this.logLine("Growth title not confirmed \u2014 clicking Growth tab (attempt " + attempt + "/3)"));
            }
            this.clickGrowthTab(attempt);
            this.sleepTask(1200L);
            // Require Daily cleared — Growth title alone can false-positive on Daily chrome
            // while DAILY_TAB_SELECTED is still lit (live Dave 2026-09-19 13:17).
            if (this.isOnGrowthMissions() && !this.isOnDailyMissions()) {
                this.logInfo(this.logLine("Switched to Growth Missions"));
                return true;
            }
            if (this.isOnDailyMissions()) {
                this.logInfo(this.logLine("Still on Daily after Growth tab tap (attempt " + attempt + ")"));
            }
        }
        this.logWarning(this.logLine("Could not switch from Daily to Growth tab"));
        return false;
    }

    private void clickGrowthTab(int attempt) {
        ImageSearchResultData unselected = this.templateSearchHelper.locatePattern(
                TemplatesEnum.GROWTH_MISSION_GROWTH_TAB_UNSELECTED, GROWTH_TAB_SEARCH);
        if (unselected.isFound() && this.isLeftGrowthTabHit(unselected.getPoint())) {
            this.logInfo(this.logLine(String.format(Locale.ROOT,
                    "Tapping Growth tab (unselected) at %s (score %.1f)",
                    unselected.getPoint(), unselected.getMatchScore())));
            this.tapGrowthTabHit(unselected);
            return;
        }
        ImageSearchResultData selected = this.templateSearchHelper.locatePattern(
                TemplatesEnum.GROWTH_MISSION_GROWTH_TAB_SELECTED, GROWTH_TAB_SEARCH);
        // When Daily is selected, "Growth selected" often matches the right Daily pill
        // (~x 410). Only trust selected hits on the left Growth half.
        if (selected.isFound() && this.isLeftGrowthTabHit(selected.getPoint())) {
            this.logInfo(this.logLine(String.format(Locale.ROOT,
                    "Tapping Growth tab (selected template) at %s (score %.1f)",
                    selected.getPoint(), selected.getMatchScore())));
            this.tapGrowthTabHit(selected);
            return;
        }
        if (selected.isFound()) {
            this.logWarning(this.logLine(String.format(Locale.ROOT,
                    "Ignoring Growth-selected match at %s (not left Growth pill) — using click area",
                    selected.getPoint())));
        } else if (unselected.isFound()) {
            this.logWarning(this.logLine(String.format(Locale.ROOT,
                    "Ignoring Growth-unselected match at %s (not left Growth pill) — using click area",
                    unselected.getPoint())));
        }
        this.logWarning(this.logLine("Growth tab templates missed \u2014 tapping Growth fallback "
                + String.valueOf(GROWTH_TAB_FALLBACK_POINT) + " / " + String.valueOf(GROWTH_TAB_CLICK_AREA)
                + " (attempt " + attempt + ")"));
        // Fixed point first: tapInside on a taller band used to land on Heroes home nav.
        this.tapNear(GROWTH_TAB_FALLBACK_POINT, GROWTH_TAB_TAP_RADIUS);
    }

    /** Growth is the left bottom tab; Daily is the right. Reject center/right false hits. */
    private boolean isLeftGrowthTabHit(PointData point) {
        return point != null && point.getX() < 360 && point.getX() >= 120;
    }

    /**
     * Bottom Growth/Daily tabs sit on a short blue pill. Oversized templates used to
     * include dimmed backdrop below/beside that pill; {@code tapInside(match)} then
     * sampled those pixels and dismissed the missions window. Tap near the match
     * center with a small radius, clamped above Heroes home nav.
     */
    private void tapGrowthTabHit(ImageSearchResultData hit) {
        PointData safe = this.safeGrowthTabPoint(hit.getPoint());
        if (safe.getY() != hit.getPoint().getY() || safe.getX() != hit.getPoint().getX()) {
            this.logInfo(this.logLine("Clamped Growth tab tap from " + hit.getPoint() + " to " + safe
                    + " (away from Heroes nav)"));
        }
        this.tapNear(safe, GROWTH_TAB_TAP_RADIUS);
    }

    /** Keep Growth-tab taps on the left pill and above Heroes (~y 1190). */
    private PointData safeGrowthTabPoint(PointData point) {
        int x = point.getX();
        int y = point.getY();
        if (x < 185) {
            x = 185;
        } else if (x > 250) {
            x = 220;
        }
        if (y < GROWTH_TAB_SAFE_Y_MIN) {
            y = GROWTH_TAB_SAFE_Y_MIN;
        } else if (y > GROWTH_TAB_SAFE_Y_MAX) {
            y = GROWTH_TAB_SAFE_Y_MAX;
        }
        return new PointData(x, y);
    }

    private boolean isMissionsPanelOpen() {
        if (this.isOnGrowthMissions() || this.isOnDailyMissions()) {
            return true;
        }
        return this.templateSearchHelper.locatePattern(TemplatesEnum.GROWTH_MISSION_GROWTH_TAB_UNSELECTED, GROWTH_TAB_SEARCH).isFound() || this.templateSearchHelper.locatePattern(TemplatesEnum.GROWTH_MISSION_GROWTH_TAB_SELECTED, GROWTH_TAB_SEARCH).isFound();
    }

    private boolean isOnDailyMissions() {
        if (this.isDailyTabSelected()) {
            return true;
        }
        return this.templateSearchHelper.locatePattern(TemplatesEnum.DAILY_MISSION_SCREEN_TITLE, DAILY_TITLE_SEARCH).isFound();
    }

    private boolean isOnGrowthMissions() {
        return this.templateSearchHelper.locatePattern(TemplatesEnum.GROWTH_MISSION_SCREEN_TITLE, GROWTH_TITLE_SEARCH).isFound();
    }

    private boolean isDailyTabSelected() {
        return this.templateSearchHelper.locatePattern(TemplatesEnum.GROWTH_MISSION_DAILY_TAB_SELECTED, DAILY_TAB_SELECTED_SEARCH).isFound();
    }

    private UpgradeOutcome performBuildingUpgrades() {
        // Identify the furniture panel before any center dismiss so we do not tap blindly.
        boolean furniturePanel = this.isFurniturePanelOpen();
        if (furniturePanel) {
            this.logInfo(this.logLine("Furniture panel marker confirmed — taking furniture road"));
            // Furniture missions have no guide-hand overlay; center dismiss is building-road only.
            return this.runFurnitureRoad();
        }
        this.logInfo(this.logLine("No furniture panel marker — taking building road"));
        return this.runBuildingRoad();
    }

    /**
     * Furniture mission loop:
     * <ul>
     *   <li>Save-steel (default): single Upgrade taps, Next between pieces, refuse steel cost.</li>
     *   <li>Fast hold: repeated 2s Upgrade holds; after each release only check main header Upgrade (and Next).</li>
     *   <li>When every piece is done, header Upgrade upgrades the building (confirm OK).</li>
     * </ul>
     */
    private UpgradeOutcome runFurnitureRoad() {
        if (this.isFurnitureSaveSteelEnabled()) {
            this.logInfo(this.logLine("Furniture road: save-steel mode (single taps)"));
            return this.runFurnitureRoadSaveSteel();
        }
        this.logInfo(this.logLine("Furniture road: fast hold mode (no resource-cost check)"));
        return this.runFurnitureRoadFastHold();
    }

    private boolean isFurnitureSaveSteelEnabled() {
        Boolean saveSteel = this.profile.getConfig(
                ConfigurationKeyEnum.CITY_GROWTH_MISSION_FURNITURE_SAVE_STEEL_BOOL, Boolean.class);
        return saveSteel == null || saveSteel;
    }

    private UpgradeOutcome runFurnitureRoadSaveSteel() {
        boolean buildingStarted = false;
        boolean furnitureProgress = false;
        boolean steelBlocked = false;
        for (int step = 1; step <= MAX_FURNITURE_STEPS; ++step) {
            this.sleepTask(500L);

            // All furniture finished — building Upgrade is available.
            ImageSearchResultData headerUpgrade = this.findHeaderUpgrade();
            if (headerUpgrade.isFound()) {
                this.logInfo(this.logLine("All furniture done — tapping building header Upgrade (step " + step + ")"));
                if (!this.confirmBuildingUpgrade(headerUpgrade)) {
                    break;
                }
                buildingStarted = true;
                break;
            }

            FurnitureStepResult furniture = this.tryFurnitureRoadStep(step);
            if (furniture == FurnitureStepResult.ADVANCED) {
                furnitureProgress = true;
                continue;
            }
            if (furniture == FurnitureStepResult.STARTED) {
                furnitureProgress = true;
                continue;
            }
            if (furniture == FurnitureStepResult.STEEL_BLOCKED) {
                steelBlocked = true;
                headerUpgrade = this.findHeaderUpgrade();
                if (!headerUpgrade.isFound()) {
                    break;
                }
                this.logInfo(this.logLine("Building header Upgrade after steel furniture guard"));
                if (!this.confirmBuildingUpgrade(headerUpgrade)) {
                    break;
                }
                buildingStarted = true;
                break;
            }
            if (furniture == FurnitureStepResult.FAILED) {
                break;
            }

            this.logInfo(this.logLine("Furniture road: no Next/Upgrade controls (step " + step + ")"));
            break;
        }
        return new UpgradeOutcome(buildingStarted, steelBlocked, furnitureProgress, false);
    }

    /**
     * Hold piece Upgrade in fixed 2s bursts. After every release the only meaningful
     * check is building header (main) Upgrade — tap+confirm, never hold it. Next is
     * tapped when a piece hits 100%. No steel/resource-cost inspect in this mode.
     */
    private UpgradeOutcome runFurnitureRoadFastHold() {
        boolean buildingStarted = false;
        boolean furnitureProgress = false;
        int holds = 0;

        for (int step = 1; step <= MAX_FURNITURE_STEPS; ++step) {
            this.sleepTask(400L);

            ImageSearchResultData headerUpgrade = this.findHeaderUpgrade();
            if (headerUpgrade.isFound()) {
                this.logInfo(this.logLine("Fast furniture: main Upgrade ready after inspect (step "
                        + step + ") — tap confirm only, no hold"));
                if (!this.confirmBuildingUpgrade(headerUpgrade)) {
                    break;
                }
                buildingStarted = true;
                break;
            }

            ImageSearchResultData nextButton = this.findFurnitureNext();
            if (nextButton.isFound()) {
                this.logInfo(this.logLine("Fast furniture: Next after hold — tapping "
                        + nextButton.getPoint() + " (step " + step + ")"));
                this.tapNear(nextButton.getPoint(), 6);
                this.sleepTask(1200L);
                furnitureProgress = true;
                continue;
            }

            ImageSearchResultData furnitureUpgrade = this.findFurniturePieceUpgrade();
            if (!furnitureUpgrade.isFound()) {
                this.logInfo(this.logLine("Fast furniture: no Upgrade/Next/header (step " + step + ")"));
                break;
            }

            if (holds >= FAST_FURNITURE_MAX_HOLDS) {
                this.logWarning(this.logLine(
                        "Fast furniture: reached max 2s holds (" + FAST_FURNITURE_MAX_HOLDS + ")"));
                furnitureProgress = true;
                break;
            }

            ++holds;
            PointData holdPoint = furnitureUpgrade.getPoint();
            this.logInfo(this.logLine(String.format(Locale.ROOT,
                    "Fast furniture: 2s hold on piece Upgrade at %s (%d/%d, step %d)",
                    holdPoint, holds, FAST_FURNITURE_MAX_HOLDS, step)));
            this.swipe(holdPoint, holdPoint, FAST_FURNITURE_HOLD_MS);
            this.sleepTask(700L);
            furnitureProgress = true;
        }

        return new UpgradeOutcome(buildingStarted, false, furnitureProgress, false);
    }

    /** Piece Upgrade only; reject hits in the building header band. */
    private ImageSearchResultData findFurniturePieceUpgrade() {
        ImageSearchResultData furnitureUpgrade = this.templateSearchHelper.locatePattern(
                TemplatesEnum.GROWTH_MISSION_BUILDING_FURNITURE_UPGRADE, FURNITURE_UPGRADE_SEARCH);
        if (!furnitureUpgrade.isFound()) {
            return furnitureUpgrade;
        }
        PointData point = furnitureUpgrade.getPoint();
        if (point != null && point.getY() < FURNITURE_UPGRADE_MIN_Y) {
            this.logWarning(this.logLine(String.format(Locale.ROOT,
                    "Ignoring Upgrade at %s (y<%d — building header band, not piece)",
                    point, FURNITURE_UPGRADE_MIN_Y)));
            return ImageSearchResultData.miss();
        }
        return furnitureUpgrade;
    }

    private UpgradeOutcome runBuildingRoad() {
        boolean started = false;
        boolean cityArrowMissed = false;
        for (int step = 1; step <= MAX_FURNITURE_STEPS; ++step) {
            this.sleepTask(500L);

            ImageSearchResultData headerUpgrade = this.findHeaderUpgrade();
            if (headerUpgrade.isFound()) {
                this.logInfo(this.logLine("Building road: main header Upgrade (step " + step + ")"));
                if (!this.confirmBuildingUpgrade(headerUpgrade)) {
                    break;
                }
                started = true;
                break;
            }

            this.logInfo(this.logLine("Building road: searching city up-arrow (step " + step + ")"));
            ImageSearchResultData cityArrow = this.locateCityUpgradeArrowWithGuideRetry();
            if (cityArrow.isFound()) {
                this.logInfo(this.logLine(String.format(Locale.ROOT,
                        "Building road: city up-arrow at %s (score %.1f, step %d)",
                        cityArrow.getPoint(), cityArrow.getMatchScore(), step)));
                if (!this.upgradeViaCityArrow(cityArrow)) {
                    break;
                }
                started = true;
                break;
            }
            this.logInfo(this.logLine("Building road: no city up-arrow (step " + step + ")"));
            cityArrowMissed = true;
            break;
        }
        return new UpgradeOutcome(started, false, false, cityArrowMissed && !started);
    }

    private boolean isFurniturePanelOpen() {
        return this.templateSearchHelper.locatePattern(
                TemplatesEnum.GROWTH_MISSION_BUILDING_FURNITURE_PANEL, FURNITURE_PANEL_SEARCH).isFound();
    }

    /**
     * Per-furniture step: Next means this piece is done (100%) — advance.
     * Upgrade means upgrade this piece only (no building confirm dialog).
     * Building confirm is only for header Upgrade after every piece is done.
     */
    private FurnitureStepResult tryFurnitureRoadStep(int step) {
        ImageSearchResultData nextButton = this.findFurnitureNext();
        if (nextButton.isFound()) {
            this.logInfo(this.logLine("Furniture road: piece complete — tapping Next at "
                    + nextButton.getPoint() + " (step " + step + ")"));
            this.tapNear(nextButton.getPoint(), 6);
            this.sleepTask(1200L);
            return FurnitureStepResult.ADVANCED;
        }

        ImageSearchResultData furnitureUpgrade = this.findFurniturePieceUpgrade();
        if (!furnitureUpgrade.isFound()) {
            this.logFurnitureNextMiss(step);
            return FurnitureStepResult.NONE;
        }

        if (this.isSteelFurnitureCost()) {
            this.logWarning(this.logLine("Furniture road: STEEL SAVE — refusing furniture Upgrade"));
            return FurnitureStepResult.STEEL_BLOCKED;
        }

        this.logInfo(this.logLine(String.format(Locale.ROOT,
                "Furniture road: tapping piece Upgrade at %s (score %.1f, step %d)",
                furnitureUpgrade.getPoint(), furnitureUpgrade.getMatchScore(), step)));
        this.tapInside(furnitureUpgrade);
        this.sleepTask(1000L);
        if (!this.refillResourcesIfNeeded()) {
            this.logWarning(this.logLine("Furniture road: resource replenishment failed"));
            return FurnitureStepResult.FAILED;
        }
        // Piece Upgrade has no confirm dialog; button becomes Next when the piece hits 100%.
        this.sleepTask(1500L);
        nextButton = this.findFurnitureNext();
        if (nextButton.isFound()) {
            this.logInfo(this.logLine("Furniture road: piece hit 100% — tapping Next at "
                    + nextButton.getPoint() + " (step " + step + ")"));
            this.tapNear(nextButton.getPoint(), 6);
            this.sleepTask(1200L);
            return FurnitureStepResult.ADVANCED;
        }
        return FurnitureStepResult.STARTED;
    }

    private ImageSearchResultData findFurnitureNext() {
        return this.templateSearchHelper.locatePattern(
                TemplatesEnum.GROWTH_MISSION_BUILDING_FURNITURE_NEXT, FURNITURE_NEXT_SEARCH);
    }

    private void logFurnitureNextMiss(int step) {
        ImageSearchResultData probe = this.templateSearchHelper.locatePattern(
                TemplatesEnum.GROWTH_MISSION_BUILDING_FURNITURE_NEXT, FURNITURE_NEXT_SCORE_PROBE);
        this.logInfo(this.logLine(String.format(Locale.ROOT,
                "Furniture road: Next not visible (best=%.1f, step %d)",
                probe.getMatchScore(), step)));
    }

    /**
     * Building road only: center-tap to clear the guide hand, then look for the up-arrow.
     * If the arrow is still missing, center-tap once more and search again. Cap is two
     * center taps — further repeats used to leave the city view stuck. Furniture never
     * calls this.
     */
    private ImageSearchResultData locateCityUpgradeArrowWithGuideRetry() {
        this.dismissGrowthGuideOverlay();
        ImageSearchResultData arrow = this.findCityUpgradeArrow(false);
        if (arrow.isFound()) {
            return arrow;
        }
        this.logInfo(this.logLine("City up-arrow missed after first center dismiss — retrying center tap once"));
        this.dismissGrowthGuideOverlay();
        return this.findCityUpgradeArrow(true);
    }

    /**
     * After Growth Go on a normal building, a guide hand / highlight may cover the
     * Upgrade arrow. One jittered center tap clears it. A second tap is allowed only
     * when the up-arrow is still missing. Furniture road never calls this.
     */
    private void dismissGrowthGuideOverlay() {
        if (this.growthGuideDismissCount >= MAX_GUIDE_CENTER_DISMISS) {
            return;
        }
        ++this.growthGuideDismissCount;
        this.logInfo(this.logLine(String.format(Locale.ROOT,
                "Tapping building center to dismiss guide hand / highlight overlay (%d/%d)",
                this.growthGuideDismissCount, MAX_GUIDE_CENTER_DISMISS)));
        this.tapInside(BUILDING_CENTER_DISMISS_AREA);
        this.sleepTask(BUILDING_CENTER_DISMISS_SETTLE_MS);
    }

    private ImageSearchResultData findHeaderUpgrade() {
        return this.templateSearchHelper.locatePattern(TemplatesEnum.GROWTH_MISSION_BUILDING_HEADER_UPGRADE, HEADER_UPGRADE_SEARCH);
    }

    private ImageSearchResultData findCityUpgradeArrow(boolean logMiss) {
        ImageSearchResultData arrow = this.templateSearchHelper.locatePattern(
                TemplatesEnum.GROWTH_MISSION_BUILDING_CITY_UPGRADE_ARROW, CITY_UPGRADE_ARROW_SEARCH);
        if (arrow.isFound()) {
            this.logInfo(this.logLine(String.format(Locale.ROOT,
                    "City up-arrow template matched at %s (score %.1f)",
                    arrow.getPoint(), arrow.getMatchScore())));
            return arrow;
        }

        ImageSearchResultData legacy = this.templateSearchHelper.locatePattern(
                TemplatesEnum.BUILDING_BUTTON_UPGRADE, CITY_UPGRADE_ARROW_SEARCH);
        if (legacy.isFound()) {
            this.logInfo(this.logLine(String.format(Locale.ROOT,
                    "City up-arrow matched via legacy building/upgradeButton (score %.1f at %s)",
                    legacy.getMatchScore(), legacy.getPoint())));
            return legacy;
        }

        if (logMiss) {
            ImageSearchResultData growthProbe = this.templateSearchHelper.locatePattern(
                    TemplatesEnum.GROWTH_MISSION_BUILDING_CITY_UPGRADE_ARROW, CITY_UPGRADE_ARROW_SCORE_PROBE);
            ImageSearchResultData legacyProbe = this.templateSearchHelper.locatePattern(
                    TemplatesEnum.BUILDING_BUTTON_UPGRADE, CITY_UPGRADE_ARROW_SCORE_PROBE);
            this.logWarning(this.logLine(String.format(Locale.ROOT,
                    "City up-arrow templates missed after center-dismiss retry (best growth=%.1f legacy=%.1f)",
                    growthProbe.getMatchScore(), legacyProbe.getMatchScore())));
        }
        return ImageSearchResultData.miss();
    }

    private boolean upgradeViaCityArrow(ImageSearchResultData cityArrow) {
        this.tapInside(cityArrow);
        this.sleepTask(2000L);
        if (!this.refillResourcesIfNeeded()) {
            return false;
        }

        ImageSearchResultData panelUpgrade = this.templateSearchHelper.locatePattern(
                TemplatesEnum.GROWTH_MISSION_BUILDING_PANEL_UPGRADE, BUILDING_PANEL_UPGRADE_SEARCH);
        if (panelUpgrade.isFound()) {
            this.logInfo(this.logLine(String.format(Locale.ROOT,
                    "Tapping building panel Upgrade at %s (score %.1f)",
                    panelUpgrade.getPoint(), panelUpgrade.getMatchScore())));
            this.tapInside(panelUpgrade);
            this.sleepTask(1500L);
            if (this.locateConfirmUpgradeButton().isFound()) {
                return this.confirmUpgradeDialog();
            }
            this.tapAllianceHelp();
            return true;
        }

        this.logInfo(this.logLine("Panel Upgrade template missed — trying confirm Upgrade text/icon"));
        return this.confirmUpgradeDialog();
    }

    /**
     * Steel guard only — furniture Upgrade itself is resource-agnostic.
     * Compares steel against other cost icons so a weak steel false-positive does not block meat/wood rows.
     */
    private boolean isSteelFurnitureCost() {
        double meat = this.matchScore(TemplatesEnum.GROWTH_MISSION_RESOURCE_ICON_MEAT);
        double wood = this.matchScore(TemplatesEnum.GROWTH_MISSION_RESOURCE_ICON_WOOD);
        double iron = this.matchScore(TemplatesEnum.GROWTH_MISSION_RESOURCE_ICON_IRON);
        double coal = this.matchScore(TemplatesEnum.GROWTH_MISSION_RESOURCE_ICON_COAL);
        double steel = this.matchScore(TemplatesEnum.GROWTH_MISSION_RESOURCE_ICON_STEEL);
        this.logInfo(this.logLine(String.format(Locale.ROOT,
                "Furniture steel-guard scores meat=%.1f wood=%.1f iron=%.1f coal=%.1f steel=%.1f (steel>=%.0f)",
                meat, wood, iron, coal, steel, RESOURCE_STEEL_GUARD_THRESHOLD)));
        return steel >= RESOURCE_STEEL_GUARD_THRESHOLD
                && steel >= meat
                && steel >= wood
                && steel >= iron
                && steel >= coal;
    }

    private double matchScore(TemplatesEnum iconTemplate) {
        ImageSearchResultData hit = this.templateSearchHelper.locatePattern(iconTemplate, TemplateSearchHelper.SearchConfig.builder().withMaxAttempts(1).withThreshold(0).withArea(RESOURCE_ICON_AREA).build());
        return hit.isFound() ? hit.getMatchScore() : 0.0;
    }

    private boolean confirmBuildingUpgrade(ImageSearchResultData upgradeButton) {
        this.tapInside(upgradeButton);
        this.sleepTask(2000L);
        if (!this.refillResourcesIfNeeded()) {
            return false;
        }
        return this.confirmUpgradeDialog();
    }

    private ImageSearchResultData locateConfirmUpgradeButton() {
        ImageSearchResultData textHit = this.templateSearchHelper.locatePattern(TemplatesEnum.GAME_HOME_SHORTCUTS_UPGRADE_TEXT, BUILDING_CONFIRM_UPGRADE_SEARCH);
        if (textHit.isFound()) {
            return textHit;
        }
        ImageSearchResultData relaxedText = this.templateSearchHelper.locatePattern(TemplatesEnum.GAME_HOME_SHORTCUTS_UPGRADE_TEXT, BUILDING_CONFIRM_UPGRADE_RELAXED);
        if (relaxedText.isFound()) {
            this.logInfo(this.logLine(String.format(Locale.ROOT, "Confirm Upgrade matched at relaxed threshold (score %.1f at %s)", relaxedText.getMatchScore(), relaxedText.getPoint())));
            return relaxedText;
        }
        ImageSearchResultData iconHit = this.templateSearchHelper.locatePattern(TemplatesEnum.GAME_HOME_SHORTCUTS_UPGRADE, BUILDING_CONFIRM_UPGRADE_RELAXED);
        if (iconHit.isFound()) {
            this.logInfo(this.logLine(String.format(Locale.ROOT, "Confirm Upgrade icon fallback matched (score %.1f at %s)", iconHit.getMatchScore(), iconHit.getPoint())));
            return iconHit;
        }
        return ImageSearchResultData.miss();
    }

    private void logConfirmUpgradeMiss() {
        ImageSearchResultData textProbe = this.templateSearchHelper.locatePattern(TemplatesEnum.GAME_HOME_SHORTCUTS_UPGRADE_TEXT, BUILDING_CONFIRM_UPGRADE_SCORE_PROBE);
        ImageSearchResultData iconProbe = this.templateSearchHelper.locatePattern(TemplatesEnum.GAME_HOME_SHORTCUTS_UPGRADE, BUILDING_CONFIRM_UPGRADE_SCORE_PROBE);
        this.logWarning(this.logLine(String.format(Locale.ROOT, "Confirm Upgrade not found. Best scores text=%.1f icon=%.1f obtainVisible=%s", textProbe.getMatchScore(), iconProbe.getMatchScore(), this.templateSearchHelper.locatePattern(TemplatesEnum.GAME_HOME_SHORTCUTS_OBTAIN, SearchConfigConstants.DEFAULT_SINGLE).isFound())));
    }

    private boolean confirmUpgradeDialog() {
        BuildingUpgradeConfirmationFlow.Outcome outcome = BuildingUpgradeConfirmationFlow.run(
                new BuildingUpgradeConfirmationFlow.Ui() {
                    @Override
                    public boolean tapDetectedUpgrade() {
                        ImageSearchResultData upgrade = locateConfirmUpgradeButton();
                        if (upgrade.isFound()) {
                            logInfo(logLine(String.format(Locale.ROOT,
                                    "Upgrade confirmation detected at %s (score %.1f)",
                                    upgrade.getPoint(), upgrade.getMatchScore())));
                            return tapInside(upgrade);
                        }
                        logConfirmUpgradeMiss();
                        // Last resort only — dialog Upgrade Y shifts by building; prefer label match.
                        logWarning(logLine("Upgrade label miss — last-resort tap of dialog Upgrade area "
                                + BUILDING_DIALOG_UPGRADE_TAP_AREA));
                        tapInside(BUILDING_DIALOG_UPGRADE_TAP_AREA);
                        return true;
                    }

                    @Override
                    public void waitForTransition() {
                        sleepTask(500L);
                    }

                    @Override
                    public boolean isConfirmationPending() {
                        boolean upgradeActionVisible = templateSearchHelper.locatePattern(
                                TemplatesEnum.GAME_HOME_SHORTCUTS_UPGRADE_TEXT,
                                BUILDING_CONFIRM_UPGRADE_POSTCONDITION).isFound();
                        if (upgradeActionVisible) {
                            return true;
                        }
                        return !templateSearchHelper.locatePattern(
                                TemplatesEnum.GAME_HOME_FURNACE,
                                SearchConfigConstants.DEFAULT_SINGLE).isFound();
                    }
                },
                3);
        if (outcome == BuildingUpgradeConfirmationFlow.Outcome.CONFIRMED) {
            this.tapAllianceHelp();
            return true;
        }
        // Dave 2026-09-19: Upgrade tap started construction but Home furnace was not
        // visible in time → POSTCONDITION_NOT_MET skipped queue OCR and Growth kept
        // opening the next mission while a build was still running.
        if (outcome == BuildingUpgradeConfirmationFlow.Outcome.POSTCONDITION_NOT_MET) {
            this.logWarning(this.logLine(
                    "Upgrade confirmation outcome: POSTCONDITION_NOT_MET — treating as started; will OCR construction queue"));
            this.tapAllianceHelp();
            return true;
        }
        this.logWarning(this.logLine("Upgrade confirmation outcome: " + String.valueOf(outcome)));
        return false;
    }

    private boolean refillResourcesIfNeeded() {
        RepeatedResourceReplenishmentFlow.Result result = RepeatedResourceReplenishmentFlow.run(
                new RepeatedResourceReplenishmentFlow.Ui() {
                    @Override
                    public PointData findReplenishAll() {
                        ImageSearchResultData hit = templateSearchHelper.locatePattern(
                                TemplatesEnum.REPLENISH_ALL_BUTTON, REPLENISH_BUTTON_RECHECK);
                        return hit.isFound() ? hit.getPoint() : null;
                    }

                    @Override
                    public PointData findObtain() {
                        ImageSearchResultData hit = templateSearchHelper.locatePattern(
                                TemplatesEnum.GAME_HOME_SHORTCUTS_OBTAIN,
                                SearchConfigConstants.DEFAULT_SINGLE);
                        return hit.isFound() ? hit.getPoint() : null;
                    }

                    @Override
                    public void openObtain(PointData point) {
                        tapNear(point);
                        sleepTask(500L);
                    }

                    @Override
                    public void replenishAndConfirm(PointData point) {
                        logInfo(logLine("Refilling one missing resource for Growth building upgrade..."));
                        tapNear(point);
                        sleepTask(300L);
                        tapNear(REPLENISH_CONFIRM_POINT);
                        sleepTask(1000L);
                    }
                },
                MAX_RESOURCE_REPLENISHMENTS);
        if (!result.ready()) {
            this.logWarning(this.logLine("Resource replenishment outcome: " + String.valueOf(result.outcome())));
        }
        return result.ready();
    }

    private boolean tapAllianceHelp() {
        ImageSearchResultData help = this.templateSearchHelper.locatePatternMultiScale(TemplatesEnum.GAME_HOME_SHORTCUTS_HELP_REQUEST4, SearchConfigConstants.HIGH_SENSITIVITY);
        if (help == null || !help.isFound()) {
            return false;
        }
        this.tapInside(help.getPoint(), help.getPoint(), 1, 500);
        return true;
    }

    private void scheduleAfterConstruction() {
        if (!this.tryScheduleFromConstructionQueue("after construction start")) {
            LocalDateTime fallback = LocalDateTime.now().plusMinutes(DEFAULT_RETRY_MINUTES);
            this.logWarning(this.logLine("Queue timer unreadable. Fallback reschedule at "
                    + fallback.format(DATETIME_FORMATTER)));
            this.reschedule(fallback);
        }
    }

    /**
     * Opens CITY left menu and OCRs construction queue timers. Returns true when a
     * busy-queue duration was read and Growth was rescheduled for that completion.
     */
    private boolean tryScheduleFromConstructionQueue(String reason) {
        // Construction UI needs a moment before the left-menu timer is readable.
        this.sleepTask(2500L);
        // execute() already dismissed to Home — avoid pressing Back again on Home
        // (that can leave the CITY sidebar in a half-open state before OCR).
        if (!this.templateSearchHelper.locatePattern(
                TemplatesEnum.GAME_HOME_FURNACE, SearchConfigConstants.DEFAULT_SINGLE).isFound()) {
            this.dismissToHome();
        }
        if (!this.navigationHelper.openSidebarSection(SidebarSection.CITY)) {
            this.logWarning(this.logLine(
                    "CITY sidebar failed to open for construction timer OCR (" + reason + ")"));
            return false;
        }
        this.sleepTask(1500L);

        for (int attempt = 1; attempt <= 5; ++attempt) {
            // Force a fresh frame — reuseFrame OCR would otherwise read the Home
            // capture from the furnace check taken before the CITY sidebar opened.
            this.emuManager.captureScreen(this.EMULATOR_NUMBER);
            Optional<Duration> wait = this.readShortestQueueWait(attempt == 5);
            if (wait.isPresent()) {
                LocalDateTime next = LocalDateTime.now().plus(wait.get()).plusSeconds(2L);
                this.logInfo(this.logLine("Construction timer OCR \u2192 next run at "
                        + next.format(DATETIME_FORMATTER)
                        + " (attempt " + attempt + ", " + reason + ")"));
                this.reschedule(next);
                this.marchHelper.closeLeftMenu();
                return true;
            }
            this.logInfo(this.logLine(
                    "Construction timer OCR miss (attempt " + attempt + "/5, " + reason + ")"));
            this.sleepTask(800L);
        }

        this.marchHelper.closeLeftMenu();
        return false;
    }

    private Optional<Duration> readShortestQueueWait(boolean logRawMisses) {
        Duration shortest = null;
        for (AreaData area : new AreaData[]{QUEUE_AREA_1, QUEUE_AREA_2}) {
            Optional<Duration> candidate = this.readQueueDuration(area, logRawMisses);
            if (candidate.isEmpty() || shortest != null && candidate.get().compareTo(shortest) >= 0) {
                continue;
            }
            shortest = candidate.get();
        }
        return Optional.ofNullable(shortest);
    }

    private Optional<Duration> readQueueDuration(AreaData area, boolean logRawMisses) {
        OcrSettingsData[] presets = new OcrSettingsData[]{
                LeftMenuTextSettings.WHITE_SETTINGS,
                LeftMenuTextSettings.WHITE_NUMBERS,
                LeftMenuTextSettings.RED_SETTINGS,
                LeftMenuTextSettings.ORANGE_SETTINGS};
        for (OcrSettingsData preset : presets) {
            try {
                String text = this.emuManager.readText(
                        this.EMULATOR_NUMBER, area.topLeft(), area.bottomRight(), preset, true);
                if (text == null || text.isBlank()) {
                    continue;
                }
                String normalized = text.replaceAll("\\s+", "");
                if (!GameTimeUtils.isAcceptedFormat(normalized)) {
                    if (logRawMisses) {
                        this.logInfo(this.logLine(String.format(Locale.ROOT,
                                "Queue OCR rejected raw='%s' normalized='%s' area=%s",
                                text.trim(), normalized, area)));
                    }
                    continue;
                }
                return Optional.of(GameTimeUtils.parseDuration(normalized));
            } catch (Exception exception) {
                if (logRawMisses) {
                    this.logDebug(this.logLine("Queue OCR error on " + area + ": " + exception.getMessage()));
                }
            }
        }
        return Optional.empty();
    }

    private void dismissToHome() {
        this.pressBack();
        this.sleepTask(400L);
        this.pressBack();
        this.sleepTask(400L);
        this.navigationHelper.ensureCorrectScreenLocation(LaunchPoint.HOME);
    }

    /**
     * After a failed city up-arrow search, Back/ESC closes the selected-building
     * bubble (hides the arrow). If Home furnace is already visible, stay put;
     * otherwise only try Home↔World taps — never press Back.
     */
    private void recoverHomeWithoutBack() {
        this.logInfo(this.logLine("Recovering Home without Back/ESC after city up-arrow miss"));
        if (this.templateSearchHelper.locatePattern(
                TemplatesEnum.GAME_HOME_FURNACE, SearchConfigConstants.DEFAULT_SINGLE).isFound()) {
            return;
        }
        ImageSearchResultData world = this.templateSearchHelper.locatePattern(
                TemplatesEnum.GAME_HOME_WORLD, SearchConfigConstants.DEFAULT_SINGLE);
        if (world.isFound()) {
            this.tapInside(world);
            this.sleepTask(1500L);
        }
        if (this.templateSearchHelper.locatePattern(
                TemplatesEnum.GAME_HOME_FURNACE, SearchConfigConstants.DEFAULT_SINGLE).isFound()) {
            return;
        }
        this.logWarning(this.logLine("Home furnace not confirmed without Back — leaving screen as-is"));
    }

    private void notifyTelegram(String outcome) {
        try {
            String profileName = this.profile.getDisplayName();
            if (profileName == null || profileName.isBlank()) {
                profileName = "id " + this.profile.getId();
            }
            // Legacy Markdown: strip chars that break parse_mode rather than escaping mid-sentence.
            String safeName = profileName.replace("_", " ").replace("*", "").replace("`", "").replace("[", "");
            TelegramBotService.getInstance().notifyAllowedChat(
                    "✅ *" + outcome + "*\nProfile: " + safeName);
        } catch (Exception e) {
            this.logDebug(this.logLine("Telegram notify skipped: " + e.getMessage()));
        }
    }

    private String logLine(String note) {
        return "GrowthMissionBuildRoutine | " + note;
    }

    private enum FurnitureStepResult {
        NONE,
        ADVANCED,
        STARTED,
        STEEL_BLOCKED,
        FAILED
    }

    private enum OpenGrowthResult {
        READY_FOR_BUILD,
        ALL_COMPLETE,
        NO_MAIN_ACTION,
        FAILED
    }

    private record UpgradeOutcome(
            boolean startedConstruction,
            boolean steelBlocked,
            boolean furnitureProgress,
            boolean cityArrowMissed) {
        boolean avoidBackOnExit() {
            return cityArrowMissed && !startedConstruction && !furnitureProgress;
        }
    }

    static {
        GO_MAIN_SEARCH = TemplateSearchHelper.SearchConfig.builder().withMaxAttempts(3).withDelay(300L).withThreshold(82).withArea(MAIN_GO_AREA).build();
        GO_SCORE_PROBE = TemplateSearchHelper.SearchConfig.builder().withMaxAttempts(1).withThreshold(0).withArea(MAIN_GO_AREA).build();
        CLAIM_QUICK_SEARCH = TemplateSearchHelper.SearchConfig.builder().withMaxAttempts(3).withDelay(250L).withThreshold(82).withArea(MAIN_GO_CLAIM_AREA).build();
        CLAIM_FALLBACK_QUICK_SEARCH = TemplateSearchHelper.SearchConfig.builder().withMaxAttempts(2).withDelay(200L).withThreshold(78).withArea(MAIN_GO_CLAIM_AREA).build();
        HEADER_UPGRADE_SEARCH = TemplateSearchHelper.SearchConfig.builder().withMaxAttempts(3).withDelay(300L).withThreshold(88).withArea(HEADER_UPGRADE_AREA).build();
        FURNITURE_UPGRADE_SEARCH = TemplateSearchHelper.SearchConfig.builder().withMaxAttempts(3).withDelay(300L).withThreshold(88).withArea(FURNITURE_UPGRADE_AREA).build();
        FURNITURE_NEXT_SEARCH = TemplateSearchHelper.SearchConfig.builder().withMaxAttempts(3).withDelay(300L).withThreshold(82).withArea(FURNITURE_NEXT_AREA).build();
        FURNITURE_NEXT_SCORE_PROBE = TemplateSearchHelper.SearchConfig.builder().withMaxAttempts(1).withThreshold(0).withArea(FURNITURE_NEXT_AREA).build();
        FURNITURE_PANEL_SEARCH = TemplateSearchHelper.SearchConfig.builder().withMaxAttempts(2).withDelay(200L).withThreshold(88).withArea(FURNITURE_PANEL_AREA).build();
        CITY_UPGRADE_ARROW_SEARCH = TemplateSearchHelper.SearchConfig.builder().withMaxAttempts(4).withDelay(400L).withThreshold(70).withArea(CITY_UPGRADE_ARROW_AREA).build();
        CITY_UPGRADE_ARROW_SCORE_PROBE = TemplateSearchHelper.SearchConfig.builder().withMaxAttempts(1).withThreshold(0).withArea(CITY_UPGRADE_ARROW_AREA).build();
        BUILDING_PANEL_UPGRADE_SEARCH = TemplateSearchHelper.SearchConfig.builder().withMaxAttempts(4).withDelay(400L).withThreshold(78).withArea(BUILDING_PANEL_UPGRADE_AREA).build();
        REPLENISH_BUTTON_RECHECK = TemplateSearchHelper.SearchConfig.builder().withMaxAttempts(2).withDelay(300L).withThreshold(90).withCoordinates(new PointData(180, 1070), new PointData(535, 1195)).build();
        BUILDING_CONFIRM_UPGRADE_SEARCH = TemplateSearchHelper.SearchConfig.builder().withMaxAttempts(5).withDelay(500L).withThreshold(85).withArea(BUILDING_CONFIRM_UPGRADE_AREA).build();
        BUILDING_CONFIRM_UPGRADE_RELAXED = TemplateSearchHelper.SearchConfig.builder().withMaxAttempts(3).withDelay(400L).withThreshold(78).withArea(BUILDING_CONFIRM_UPGRADE_AREA).build();
        BUILDING_CONFIRM_UPGRADE_POSTCONDITION = TemplateSearchHelper.SearchConfig.builder().withMaxAttempts(1).withThreshold(85).withArea(BUILDING_CONFIRM_UPGRADE_AREA).build();
        BUILDING_CONFIRM_UPGRADE_SCORE_PROBE = TemplateSearchHelper.SearchConfig.builder().withMaxAttempts(1).withThreshold(0).withArea(BUILDING_CONFIRM_UPGRADE_AREA).build();
    }
}
