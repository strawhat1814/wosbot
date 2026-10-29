package dev.frostguard.tasks.pets;

import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.tasks.diagnostics.TaskDiagnosticSnapshots;
import dev.frostguard.engine.nav.SidebarDestination;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Task that manages Pet Adventure chests.
 * 
 * <p>
 * <b>Execution Flow:</b>
 * <ol>
 * <li>Navigate to Pet Adventures (Pets â†’ Beast Cage â†’ Adventure Map)</li>
 * <li>Claim all completed adventure chests (up to 3 visible)</li>
 * <li>Start new adventures with available chests</li>
 * <li>Repeat until no more chests or attempts exhausted</li>
 * </ol>
 * 
 * <p>
 * <b>Game Mechanics:</b>
 * <ul>
 * <li>Maximum 3 chests visible on screen at once</li>
 * <li>Each chest adventure costs 10 stamina</li>
 * <li>4 daily attempts available (resets at game reset)</li>
 * <li>Chests respawn after claiming rewards</li>
 * <li>Completed chests can be shared with alliance</li>
 * </ul>
 * 
 * <p>
 * <b>Scheduling Strategy:</b>
 * <ul>
 * <li>After a confirmed start: 2 hours, then start any pin that is still idle</li>
 * <li>No confirmed start after an unverified pin: 15 minute retry, no stamina spend</li>
 * <li>After exhausting attempts: next game reset</li>
 * <li>Navigation failure: 15 minute retry</li>
 * </ul>
 * 
 * @author WoS Bot
 */
public class PetAdventureChestRoutine extends DelayedTask {

	// ========================================================================
	// GAME MECHANICS CONSTANTS
	// ========================================================================

	private static final int STAMINA_PER_CHEST = 10;
	private static final int MIN_STAMINA_REQUIRED = 10;
	private static final int TARGET_STAMINA_FOR_REFRESH = 40;
	private static final int MAX_CHEST_START_ITERATIONS = 12;

	private static final SearchConfig CHEST_CANDIDATES = SearchConfig.builder()
			.withMaxAttempts(3)
			.withThreshold(90)
			.withDelay(200L)
			.withMaxResults(3)
			.build();

	private static final SearchConfig ADVENTURE_TIMERS = SearchConfig.builder()
			.withMaxAttempts(1)
			.withThreshold((int) PetAdventureDecisions.TIMER_THRESHOLD)
			.withMaxResults(5)
			.build();

	// ========================================================================
	// NAVIGATION CONSTANTS
	// ========================================================================

	/**
	 * Area to tap for skipping reward popups after claiming chests.
	 * Taps on empty screen space to dismiss overlays.
	 */
	private static final PointData CHEST_CLAIM_POINT = new PointData(370, 800);

	private static final SearchConfig MAP_PROBE = SearchConfig.builder()
			.withMaxAttempts(1)
			.withThreshold(90)
			.withMaxResults(1)
			.build();

	// ========================================================================
	// CHEST PRIORITY ORDER
	// ========================================================================

	/**
	 * Chest priority order: Red (highest quality) â†’ Purple â†’ Blue (lowest).
	 */
	private static final List<TemplatesEnum> CHEST_PRIORITY = List.of(
			TemplatesEnum.PETS_CHEST_RED,
			TemplatesEnum.PETS_CHEST_PURPLE,
			TemplatesEnum.PETS_CHEST_BLUE);

	// ========================================================================
	// CONSTRUCTOR
	// ========================================================================

	/**
	 * Constructs a new PetAdventureChestRoutine.
	 * 
	 * @param profile The profile this task will execute for
	 * @param tpTask  The task type enum
	 */
	public PetAdventureChestRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
		super(profile, tpTask);
	}

	// ========================================================================
	// TASK CONFIGURATION
	// ========================================================================

	@Override
	protected boolean consumesStamina() {
		return true;
	}

	@Override
	public boolean provideDailyMissionProgress() {
		return true;
	}

	// ========================================================================
	// MAIN EXECUTION
	// ========================================================================

	/**
	 * Executes the pet adventure chest management process.
	 * 
	 * <p>
	 * <b>Process:</b>
	 * <ol>
	 * <li>Validate stamina availability</li>
	 * <li>Navigate to Pet Adventures</li>
	 * <li>Claim completed chests</li>
	 * <li>Start new adventures until attempts exhausted or no chests found</li>
	 * </ol>
	 */
	@Override
	protected void execute() {

		// Validate stamina before proceeding
		if (!staminaHelper.checkStaminaOrReschedule(
				MIN_STAMINA_REQUIRED,
				TARGET_STAMINA_FOR_REFRESH,
				this)) {
			return; // Task will be rescheduled by helper
		}

		if (!navigateToPetAdventures()) {
			rescheduleForNavigationRetry();
			return;
		}

		claimCompletedChests();
		if (observeOnlyVisit()) {
			clearObserveOnly();
			logInfo("Cleared the unverified-start lock so this visit can start every idle chest.");
		}
		startAvailableChests();
	}

	// ========================================================================
	// NAVIGATION METHODS
	// ========================================================================

	/**
	 * Navigates from current screen to the Pet Adventures map.
	 * 
	 * <p>
	 * <b>Steps:</b>
	 * <ol>
	 * <li>Open the Daily sidebar</li>
	 * <li>Locate the Pet Adventure row</li>
	 * <li>Tap its detected Go control</li>
	 * </ol>
	 * 
	 * @return true if navigation succeeded, false if any step failed
	 */
	private boolean navigateToPetAdventures() {
		logDebug("Opening Pet Adventure through the Daily sidebar");
		return navigationHelper.navigateToSidebarDestination(SidebarDestination.PET_ADVENTURE);
	}

	// ========================================================================
	// CLAIMING METHODS
	// ========================================================================

	/**
	 * Claims all completed adventure chests visible on the screen.
	 * 
	 * <p>
	 * Searches for up to 3 completed chests (maximum visible at once)
	 * and claims each one. After claiming, shares the chest with alliance
	 * if the share button is available.
	 * 
	 * <p>
	 * Performs 2 search attempts to ensure all completed chests are claimed,
	 * as new chests may become visible after claiming previous ones.
	 * 
	 * <p>
	 * <b>Process per chest:</b>
	 * <ol>
	 * <li>Tap completed chest</li>
	 * <li>Skip reward popups by tapping empty screen area</li>
	 * <li>Share with alliance if button available</li>
	 * <li>Return to adventure map</li>
	 * </ol>
	 */
	private void claimCompletedChests() {
		logDebug("Searching for completed chests to claim");

		for (int i = 0; i < 2; i++) {
			logDebug("Searching for completed chests. Attempt " + (i + 1) + ".");
			List<ImageSearchResultData> completedChests = templateSearchHelper.locateAllPatterns(
					TemplatesEnum.PETS_CHEST_COMPLETED,
					SearchConfigConstants.MULTIPLE_RESULTS);

			if (completedChests == null || completedChests.isEmpty()) {
				logInfo("No completed chests found on attempt " + (i + 1) + ".");
				continue;
			}

			logInfo("Found " + completedChests.size() + " completed chest(s). Claiming them now.");

			for (ImageSearchResultData chest : completedChests) {
				claimSingleChest(chest);
			}
		}
	}

	/**
	 * Claims a single completed chest.
	 * 
	 * @param chest The search result pointing to the completed chest
	 */
	private void claimSingleChest(ImageSearchResultData chest) {
		logDebug("Claiming completed chest");

		tapInside(chest.getPoint(), chest.getPoint());
		sleepTask(1000); // Wait for chest detail screen

		// Claim chest
		tapNear(CHEST_CLAIM_POINT);
		sleepTask(2000);
		pressBack();
		sleepTask(2000);

		// Check if share button is available
		ImageSearchResultData shareButton = templateSearchHelper.locatePattern(
				TemplatesEnum.PETS_CHEST_SHARE,
				SearchConfigConstants.DEFAULT_SINGLE);

		if (shareButton.isFound()) {
			logDebug("Sharing completed chest with alliance");
			tapInside(shareButton.getPoint(), shareButton.getPoint());
			sleepTask(1000); // Wait for share action
		}

		pressBack(); // Return to adventure map
		sleepTask(1000); // Wait for screen transition
	}

	// ========================================================================
	// CHEST STARTING METHODS
	// ========================================================================

	/**
	 * Starts every idle pin once during this visit. An unverified pin is
	 * skipped so the other idle pins can still be started. A confirmed pin
	 * is skipped too, so a countdown that has not appeared yet is not tapped
	 * again.
	 */
	private void startAvailableChests() {
		logDebug("Starting available adventure chests");
		VisitPass pass = new VisitPass();
		int iterationCount = 0;

		while (true) {
			iterationCount++;
			if (iterationCount > MAX_CHEST_START_ITERATIONS) {
				pass.noteUnknown("chest scan reached its iteration limit");
				break;
			}

			IdleChest next = findNextIdleChest(pass.skippedPins);
			if (next == null) {
				break;
			}

			ChestAttempt attempt = startIdleChest(next.template, next.chest, pass);
			switch (attempt.kind) {
				case STARTED -> {
					pass.confirmedStarts++;
					pass.skippedPins.add(next.chest.getPoint());
				}
				case SELECT_MISSING -> {
					pass.noteUnknown(attempt.detail);
					pass.skippedPins.add(next.chest.getPoint());
					if (!adventureMapVisible() && !dismissToAdventureMap()) {
						scheduleUnknownChestRetry(
								"adventure map was not visible after a missing Select control",
								"select-control");
						return;
					}
				}
				case UNVERIFIED_START, PANEL_UNKNOWN -> {
					pass.noteUnknown(attempt.detail);
					pass.skippedPins.add(next.chest.getPoint());
					if (!dismissToAdventureMap()) {
						scheduleUnknownChestRetry(
								"adventure map was not visible after an unverified chest action",
								"start-outcome");
						return;
					}
				}
				case NO_ATTEMPTS -> pass.attemptsExhausted = true;
			}

			if (pass.attemptsExhausted) {
				break;
			}
		}

		scheduleAfterChestPass(pass);
	}

	private IdleChest findNextIdleChest(List<PointData> skippedPins) {
		List<ImageSearchResultData> timers = templateSearchHelper.locateAllPatterns(
				TemplatesEnum.PETS_CHEST_ADVENTURE_TIMER,
				ADVENTURE_TIMERS);

		for (TemplatesEnum chestTemplate : CHEST_PRIORITY) {
			logDebug("Searching for " + chestTemplate);
			List<ImageSearchResultData> chests = templateSearchHelper.locateAllPatterns(
					chestTemplate,
					CHEST_CANDIDATES);
			if (chests == null || chests.isEmpty()) {
				continue;
			}

			for (ImageSearchResultData chestResult : chests) {
				PointData pin = chestResult.getPoint();
				if (!chestResult.isFound() || pin == null) {
					continue;
				}
				if (PetAdventureDecisions.occupiedByTimer(pin, timers)) {
					logInfo("Skipping " + chestTemplate + " already in adventure at " + pin);
					continue;
				}
				if (PetAdventureDecisions.alreadyAttempted(pin, skippedPins)) {
					logDebug("Skipping " + chestTemplate + " already attempted at " + pin);
					continue;
				}
				return new IdleChest(chestTemplate, chestResult);
			}
		}
		return null;
	}

	private ChestAttempt startIdleChest(TemplatesEnum chestTemplate, ImageSearchResultData chestResult, VisitPass pass) {
		PointData pin = chestResult.getPoint();
		logInfo("Found " + chestTemplate + " at " + pin + ". Attempting to start adventure");

		tapInside(pin, pin);
		sleepTask(500);

		ImageSearchResultData selectButton = templateSearchHelper.locatePattern(
				TemplatesEnum.PETS_CHEST_SELECT,
				SearchConfigConstants.DEFAULT_SINGLE);

		if (!selectButton.isFound()) {
			captureUnknownOnce(pass, "select-control");
			String detail = "Select control was missing for " + chestTemplate + " at " + pin;
			logInfo(detail + ". Skipping that pin for this visit.");
			return ChestAttempt.selectMissing(detail);
		}

		tapInside(selectButton);
		sleepTask(500);

		ImageSearchResultData startButton = templateSearchHelper.locatePattern(
				TemplatesEnum.PETS_CHEST_START,
				SearchConfigConstants.DEFAULT_SINGLE);

		if (startButton.isFound()) {
			tapInside(startButton);
			sleepTask(1000);
			ImageSearchResultData startStillVisible = templateSearchHelper.locatePattern(
					TemplatesEnum.PETS_CHEST_START,
					SearchConfigConstants.DEFAULT_SINGLE);
			if (startStillVisible.isFound()) {
				captureUnknownOnce(pass, "start-unconfirmed");
				String detail = "Start control remained visible after the tap at " + pin;
				logInfo(detail + ". Skipping that pin for this visit.");
				return ChestAttempt.panelUnknown(detail);
			}

			ImageSearchResultData inAdventure = templateSearchHelper.locatePattern(
					TemplatesEnum.PETS_CHEST_IN_ADVENTURE,
					SearchConfigConstants.DEFAULT_SINGLE);
			if (PetAdventureDecisions.recordsStaminaSpend(inAdventure)) {
				staminaHelper.subtractStamina(STAMINA_PER_CHEST, false);
				logInfo("Started " + chestTemplate + " adventure (consumed "
						+ STAMINA_PER_CHEST + " stamina)");
				pressBack();
				sleepTask(500);
				return ChestAttempt.started();
			}

			captureUnknownOnce(pass, "start-outcome");
			String detail = "Start tap at " + pin + " did not show In Adventure";
			logInfo(detail + ". Skipping that pin for this visit.");
			return ChestAttempt.unverified(detail);
		}

		ImageSearchResultData noAttemptsMessage = templateSearchHelper.locatePattern(
				TemplatesEnum.PETS_CHEST_ATTEMPT,
				SearchConfigConstants.DEFAULT_SINGLE);

		if (noAttemptsMessage.isFound()) {
			logInfo("No more adventure attempts available");
			return ChestAttempt.noAttempts();
		}

		captureUnknownOnce(pass, "start-status");
		String detail = "neither Start nor No Attempts was detected for " + chestTemplate + " at " + pin;
		logInfo(detail + ". Skipping that pin for this visit.");
		return ChestAttempt.panelUnknown(detail);
	}

	/**
	 * One back closes the start overlay. A second back closes a sheet that
	 * was left open, such as the stamina Obtain more panel, and returns to
	 * the adventure map. Further backs would leave Pet Adventure.
	 */
	private boolean dismissToAdventureMap() {
		for (int attempt = 0; attempt < 2; attempt++) {
			if (adventureMapVisible()) {
				return true;
			}
			pressBack();
			sleepTask(500);
		}
		return adventureMapVisible();
	}

	private boolean adventureMapVisible() {
		if (hasAnyMatch(TemplatesEnum.PETS_CHEST_ADVENTURE_TIMER, ADVENTURE_TIMERS)) {
			return true;
		}
		for (TemplatesEnum chestTemplate : CHEST_PRIORITY) {
			if (hasAnyMatch(chestTemplate, MAP_PROBE)) {
				return true;
			}
		}
		return false;
	}

	private boolean hasAnyMatch(TemplatesEnum template, SearchConfig config) {
		List<ImageSearchResultData> hits = templateSearchHelper.locateAllPatterns(template, config);
		if (hits == null) {
			return false;
		}
		for (ImageSearchResultData hit : hits) {
			if (hit != null && hit.isFound()) {
				return true;
			}
		}
		return false;
	}

	private void captureUnknownOnce(VisitPass pass, String type) {
		if (pass.snapshot != null) {
			return;
		}
		pass.snapshot = TaskDiagnosticSnapshots.capture(
				emuManager, EMULATOR_NUMBER, "petadventurechest", type);
	}

	// ========================================================================
	// RESCHEDULING METHODS
	// ========================================================================

	private void scheduleAfterChestPass(VisitPass pass) {
		PetAdventureDecisions.PetAdventureNextVisit visit = PetAdventureDecisions.nextVisit(
				pass.confirmedStarts, pass.unknownPin, pass.attemptsExhausted);
		switch (visit) {
			case ATTEMPTS_RESET -> rescheduleToGameReset();
			case UNKNOWN_RETRY -> scheduleUnknownRetry(pass);
			case COMPLETION_CHECK -> {
				if (pass.confirmedStarts > 0) {
					logInfo("Started " + pass.confirmedStarts + " adventure(s).");
					if (pass.unknownPin) {
						logInfo("Skipped unverified pins this visit: " + pass.unknownDetail);
					}
				} else {
					logInfo("No more available chests found. Rescheduling for chest completion check");
				}
				rescheduleForChestCompletion();
			}
		}
	}

	private void scheduleUnknownRetry(VisitPass pass) {
		LocalDateTime retryTime = PetAdventureDecisions.unknownRetryAt(LocalDateTime.now());
		String snapshot = pass.snapshot;
		if (snapshot == null) {
			snapshot = TaskDiagnosticSnapshots.capture(
					emuManager, EMULATOR_NUMBER, "petadventurechest", "iteration-limit");
		}
		String reason = pass.unknownDetail == null ? "chest scan did not confirm a start" : pass.unknownDetail;
		logWarning("Pet Adventure outcome is unknown: " + reason + "; retrying at "
				+ retryTime.format(DATETIME_FORMATTER) + "; " + snapshot + ".");
		reschedule(retryTime);
	}

	private void rescheduleForChestCompletion() {
		LocalDateTime nextCheck = PetAdventureDecisions.completionCheckAt(LocalDateTime.now());
		reschedule(nextCheck);
		logInfo("Rescheduled for chest completion check at " +
				nextCheck.format(TIME_FORMATTER));
	}

	/**
	 * Reschedules the task to the next game reset (00:00 UTC).
	 * 
	 * <p>
	 * Used when all daily attempts have been exhausted.
	 */
	private void rescheduleToGameReset() {
		LocalDateTime nextReset = GameTimeUtils.dailyResetTime();
		reschedule(nextReset);
		logInfo("Rescheduled to next game reset: " +
				nextReset.format(DATETIME_FORMATTER));
	}

	/**
	 * Reschedules the task for navigation retry (15 minutes).
	 * 
	 * <p>
	 * Used when navigation to Pet Adventures fails.
	 */
	private void rescheduleForNavigationRetry() {
		LocalDateTime retryTime = PetAdventureDecisions.unknownRetryAt(LocalDateTime.now());
		reschedule(retryTime);
		logWarning("Navigation failed. Retrying at " + retryTime.format(TIME_FORMATTER));
	}

	private boolean observeOnlyVisit() {
		return Boolean.TRUE.equals(profile.getConfig(
				ConfigurationKeyEnum.PET_ADVENTURE_OBSERVE_ONLY_BOOL, Boolean.class));
	}

	private void clearObserveOnly() {
		writeProfileSetting(ConfigurationKeyEnum.PET_ADVENTURE_OBSERVE_ONLY_BOOL, false);
	}

	private void scheduleUnknownChestRetry(String reason, String type) {
		LocalDateTime retryTime = PetAdventureDecisions.unknownRetryAt(LocalDateTime.now());
		String snapshot = TaskDiagnosticSnapshots.capture(emuManager, EMULATOR_NUMBER, "petadventurechest", type);
		logWarning("Pet Adventure outcome is unknown: " + reason + "; retrying at "
				+ retryTime.format(DATETIME_FORMATTER) + "; " + snapshot + ".");
		reschedule(retryTime);
	}

	private static final class VisitPass {
		private final List<PointData> skippedPins = new ArrayList<>();
		private int confirmedStarts;
		private boolean unknownPin;
		private boolean attemptsExhausted;
		private String unknownDetail;
		private String snapshot;

		private void noteUnknown(String detail) {
			unknownPin = true;
			if (detail == null || detail.isBlank()) {
				return;
			}
			unknownDetail = unknownDetail == null ? detail : unknownDetail + "; " + detail;
		}
	}

	private record IdleChest(TemplatesEnum template, ImageSearchResultData chest) {
	}

	private enum ChestStartKind {
		STARTED,
		SELECT_MISSING,
		UNVERIFIED_START,
		PANEL_UNKNOWN,
		NO_ATTEMPTS
	}

	private static final class ChestAttempt {
		private final ChestStartKind kind;
		private final String detail;

		private ChestAttempt(ChestStartKind kind, String detail) {
			this.kind = kind;
			this.detail = detail;
		}

		private static ChestAttempt started() {
			return new ChestAttempt(ChestStartKind.STARTED, null);
		}

		private static ChestAttempt selectMissing(String detail) {
			return new ChestAttempt(ChestStartKind.SELECT_MISSING, detail);
		}

		private static ChestAttempt unverified(String detail) {
			return new ChestAttempt(ChestStartKind.UNVERIFIED_START, detail);
		}

		private static ChestAttempt panelUnknown(String detail) {
			return new ChestAttempt(ChestStartKind.PANEL_UNKNOWN, detail);
		}

		private static ChestAttempt noAttempts() {
			return new ChestAttempt(ChestStartKind.NO_ATTEMPTS, null);
		}
	}
}
