package dev.frostguard.tasks.lifecycle;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.engine.diagnostics.DiagnosticSnapshotStore;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.error.ActionRequiredContext;
import dev.frostguard.engine.error.ProfileCooldownException;
import dev.frostguard.engine.error.ProfileInReconnectStateException;
import dev.frostguard.engine.error.StartupCaptureException;
import dev.frostguard.engine.error.StopExecutionException;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.schedule.TaskDispatcher;
import dev.frostguard.engine.schedule.TaskQueue;
import dev.frostguard.engine.service.TelegramBotService;
import dev.frostguard.engine.helper.CharacterSwitchHelper;
import dev.frostguard.engine.input.TapInteractionService;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.engine.service.ProfileService;
import dev.frostguard.engine.service.ScheduleService;
import dev.frostguard.engine.sharedlogin.SharedLoginDirective;
import dev.frostguard.engine.sharedlogin.SharedLoginHandshakeCoordinator;
import dev.frostguard.engine.sharedlogin.SharedLoginHandshakeSession;
import dev.frostguard.engine.sharedlogin.SharedLoginObservation;
import dev.frostguard.engine.sharedlogin.SharedLoginPair;
import dev.frostguard.engine.sharedlogin.SharedLoginPairGate;
import dev.frostguard.tasks.combat.PolarSpecialRewardsBudget;
import dev.frostguard.vision.convert.ImageConverter;
import dev.frostguard.vision.detection.CloseCrossDetector;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import dev.frostguard.vision.ocr.OcrException;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.function.BooleanSupplier;

/**
 * Initialize task that starts the bot and prepares the game for automation.
 * 
 * <p>
 * This task is the first task executed when the bot starts and performs
 * critical initialization:
 * <ul>
 * <li>Ensures the emulator is running (launches if needed)</li>
 * <li>Verifies Whiteout Survival is installed</li>
 * <li>Launches the game if not already running</li>
 * <li>Waits for home or world screen to appear</li>
 * <li>Reads initial stamina value from profile</li>
 * </ul>
 * 
 * <p>
 * <b>Unique Behavior:</b>
 * <ul>
 * <li>This task does NOT reschedule after execution</li>
 * <li>Sets recurring=false on start to prevent re-execution</li>
 * <li>Unknown in-game startup overlays receive one foreground-owned Back recovery</li>
 * <li>Persistent blockers pause the profile until a conservative retry time</li>
 * <li>Exception on success: Task completes without rescheduling</li>
 * </ul>
 * 
 * <p>
 * <b>Error Handling:</b>
 * <ul>
 * <li>Game not installed: Throws StopExecutionException (stops queue)</li>
 * <li>Reconnect state detected: Throws ProfileInReconnectStateException</li>
 * <li>Home screen not found (any mid-task UI after {@code /adbreboot}): bounded Android Back presses while the game owns foreground, then pauses the profile</li>
 * <li>Verified Google Play redirect: Requires operator action and releases the
 * emulator slot during a profile cooldown</li>
 * </ul>
 * 
 * <p>
 * <b>State Management:</b>
 * The {@code isStarted} field avoids repeating emulator checks during one
 * initialization execution.
 */
public class InitializeRoutine extends DelayedTask {

	// ========== Home Screen Detection Constants ==========
	private static final int MAX_HOME_SCREEN_ATTEMPTS = 10;
	private static final int RESOURCE_DOWNLOAD_TIMEOUT_MINUTES = 10;
	private static final int RESOURCE_DOWNLOAD_POLL_DELAY_MS = 5000;
	private static final int MAX_RESOURCE_DOWNLOAD_ATTEMPTS =
			RESOURCE_DOWNLOAD_TIMEOUT_MINUTES * 60_000 / RESOURCE_DOWNLOAD_POLL_DELAY_MS;
	private static final int RESOURCE_DOWNLOAD_PROGRESS_LOG_INTERVAL = 12;
	private static final PointData WELCOME_BACK_TITLE_AREA_TOP_LEFT = new PointData(170, 170);
	private static final PointData WELCOME_BACK_TITLE_AREA_BOTTOM_RIGHT = new PointData(550, 340);
	private static final PointData WELCOME_BACK_CONFIRM_AREA_TOP_LEFT = new PointData(150, 880);
	private static final PointData WELCOME_BACK_CONFIRM_AREA_BOTTOM_RIGHT = new PointData(570, 1140);
	private static final PointData UPDATE_TITLE_AREA_TOP_LEFT = new PointData(250, 250);
	private static final PointData UPDATE_TITLE_AREA_BOTTOM_RIGHT = new PointData(470, 350);
	private static final PointData UPDATE_BUTTON_AREA_TOP_LEFT = new PointData(200, 850);
	private static final PointData UPDATE_BUTTON_AREA_BOTTOM_RIGHT = new PointData(520, 1050);
	private static final AreaData CLOSEABLE_OVERLAY_SEARCH_AREA = AreaData.of(540, 20, 719, 280);
	private static final int MAX_CLOSEABLE_OVERLAY_CENTER_Y = 240;
	private static final int UPDATE_PATTERN_THRESHOLD = 90;
	private static final int UPDATE_POSTCONDITION_TIMEOUT_MINUTES = 10;
	private static final int UPDATE_POSTCONDITION_POLL_DELAY_MS = 5000;
	private static final int MAX_UPDATE_POSTCONDITION_ATTEMPTS =
			UPDATE_POSTCONDITION_TIMEOUT_MINUTES * 60_000 / UPDATE_POSTCONDITION_POLL_DELAY_MS;
	private static final int UPDATE_PROGRESS_LOG_INTERVAL = 12;
	private static final String GOOGLE_PLAY_PACKAGE = "com.android.vending";
	private static final int MAX_WELCOME_BACK_DISMISSALS = 1;
	private static final int MAX_CLOSEABLE_OVERLAY_DISMISSALS = 3;
	private static final int MAX_TREK_EXIT_HINT_DISMISSALS = 3;
	private static final int MAX_ANR_WAIT_DISMISSALS = 3;
	private static final int ANR_WAIT_SETTLE_MS = 8_000;
	private static final int UNKNOWN_BLOCKER_BACK_SETTLE_MS = 2000;
	private static final int MAX_UNKNOWN_BLOCKER_POSTCONDITION_ATTEMPTS = 3;
	private static final int STARTUP_PATTERN_THRESHOLD = 90;

	// ========== Instance State ==========
	/**
	 * Tracks whether the emulator has been successfully started.
	 * The flag remains valid for the rest of the current initialization flow.
	 */
	boolean isStarted = false;
	private int unknownBlockerBackAttempts = 0;
	private int welcomeBackDismissals = 0;
	private int closeableOverlayDismissals = 0;
	private int trekExitHintDismissals = 0;
	private int anrWaitDismissals = 0;
	private int quitGameDismissals = 0;
	private Instant nextSharedLoginCharacterReadAt = Instant.EPOCH;
	private String lastVerifiedStartupState = "initialization started";
	private RawImageData lastStartupFrame;
	private final DiagnosticSnapshotStore startupSnapshots = DiagnosticSnapshotStore.forCurrentWorkspace();

	private static final int MAX_QUIT_GAME_DISMISSALS = 6;
	private static final long SHARED_LOGIN_CHARACTER_READ_COOLDOWN_MS = 8_000L;

	/**
	 * Helper for character switching operations.
	 */
	private CharacterSwitchHelper characterSwitchHelper;

	/**
	 * Constructs a new InitializeRoutine.
	 *
	 * @param profile     the profile this task belongs to
	 * @param tpDailyTask the task type enum
	 */
	public InitializeRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
		super(profile, tpDailyTask);
		// Initialize character switch helper
		this.characterSwitchHelper = new CharacterSwitchHelper(emuManager, EMULATOR_NUMBER, profile);
	}

	/**
	 * Main execution method for initialization.
	 * 
	 * <p>
	 * Flow:
	 * <ol>
	 * <li>Set recurring=false (one-time execution by default)</li>
	 * <li>Ensure emulator is running</li>
	 * <li>Verify game is installed</li>
	 * <li>Launch game if needed</li>
	 * <li>Wait for home/world screen</li>
	 * <li>Update initial stamina value</li>
	 * </ol>
	 * 
	 * <p>
	 * <b>No Reschedule:</b>
	 * This task intentionally does not call reschedule(). It either:
	 * <ul>
	 * <li>Completes successfully (recurring=false, task stops)</li>
	 * <li>Throws a bounded cooldown exception (queue persists the retry)</li>
	 * </ul>
	 * 
	 * @throws StopExecutionException           if game is not installed
	 * @throws ProfileInReconnectStateException if profile needs reconnection
	 */
	@Override
	protected void execute() {
		lastStartupFrame = null;
		setRecurring(false);

		ensureEmulatorRunning();
		ensureGameInstalled();
		ensureGameRunning();

		SharedLoginPair sharedPair = SharedLoginPair.resolve(
				profile,
				id -> ProfileService.obtain().fetchAllAccounts().stream()
						.filter(account -> id.equals(account.getId()))
						.findFirst()
						.orElse(null))
				.orElse(null);
		if (sharedPair != null) {
			// After a successful handshake, Initialize still runs before other tasks.
			// Do not markPending / peer-kick again or the pair thrash-resets forever.
			if (SharedLoginPairGate.isReady(sharedPair)) {
				CharacterSwitchHelper.CharacterVerifyResult readyVerify =
						classifySharedLoginReadyCharacter();
				if (readyVerify == CharacterSwitchHelper.CharacterVerifyResult.MATCH) {
					logInfo("Shared login already ready on owned character; skipping handshake");
					handleInitializationSuccess();
					return;
				}
				if (readyVerify == CharacterSwitchHelper.CharacterVerifyResult.INCONCLUSIVE) {
					// Junk OCR / profile sheet flake must not clear the ready gate or force
					// another Initialize loop. Last successful handshake already proved the
					// owned character; proceed so farming is not stuck on Init forever.
					logWarning("Shared login ready-path character OCR inconclusive; "
							+ "keeping pair ready and skipping re-handshake");
					handleInitializationSuccess();
					return;
				}
				logWarning("Shared login was ready but live character does not match; "
						+ "clearing Polar special-rewards budget and re-handshaking");
				if (profile.getId() != null) {
					PolarSpecialRewardsBudget.clearProfile(profile.getId());
				}
			}
			SharedLoginPairGate.markPending(sharedPair);
			if (!runSharedLoginHandshake(sharedPair)) {
				// Stay gated; keep Initialize retrying — no farming on the wrong character.
				setRecurring(true);
				reschedule(LocalDateTime.now().plusSeconds(5));
				logWarning("Shared login not ready; Initialize will retry. Farming stays blocked.");
				return;
			}
			SharedLoginPairGate.markReady(sharedPair);
			handleInitializationSuccess();
			return;
		}

		// Wait for home screen
		if (!waitForHomeScreen()) {
			// A blocking startup state already selected and logged its bounded outcome.
			return;
		}
		
		// Verify and switch character if needed (before reading stamina)
		if (!verifyAndSwitchCharacter()) {
			// Character verification/switching failed - already handled
			return;
		}
		
		// All checks passed - complete initialization
		handleInitializationSuccess();
	}

	/**
	 * Ensures the emulator is running, launching it if necessary.
	 * 
	 * <p>
	 * This method loops until the emulator is confirmed running.
	 * If not running, it attempts to launch and waits before checking again.
	 * 
	 * <p>
	 * The {@code isStarted} flag prevents redundant checks on subsequent
	 * calls within the same execution.
	 */
	private void ensureEmulatorRunning() {
		logInfo("Checking emulator status...");

		if (!isStarted) {
			awaitEmulatorRunning(
					() -> emuManager.isRunning(EMULATOR_NUMBER),
					() -> {
						logInfo("Emulator not found. Attempting to start it...");
						emuManager.launchEmulator(EMULATOR_NUMBER);
					},
					() -> {
						logInfo("Waiting 10 seconds before checking again.");
						sleepTask(10000); // Wait for emulator to start
					},
					this::checkPreemption);
			isStarted = true;
			lastVerifiedStartupState = "emulator running";
			logInfo("Emulator is running.");
		}
	}

	static void awaitEmulatorRunning(BooleanSupplier isRunning, Runnable launch,
			Runnable retryDelay, Runnable checkPreemption) {
		while (true) {
			checkPreemption.run();
			boolean running = isRunning.getAsBoolean();
			checkPreemption.run();
			if (running) {
				return;
			}
			launch.run();
			checkPreemption.run();
			retryDelay.run();
		}
	}

	/**
	 * Verifies that Whiteout Survival is installed on the emulator.
	 * 
	 * <p>
	 * If the game is not installed, throws StopExecutionException to halt
	 * the task queue, as automation cannot proceed without the game.
	 * 
	 * @throws StopExecutionException if game is not installed
	 */
	private void ensureGameInstalled() {
		if (!emuManager.isGameInstalled(EMULATOR_NUMBER)) {
			logError("Whiteout Survival is not installed. Stopping the task queue.");
			throw new StopExecutionException("Game not installed");
		}
		lastVerifiedStartupState = "game installed";
	}

	/**
	 * Ensures the game is running, launching it if necessary.
	 * 
	 * <p>
	 * Checks if Whiteout Survival is currently running. If not, launches
	 * the game and waits for it to start.
	 */
	private void ensureGameRunning() {
		if (!emuManager.isPackageRunning(EMULATOR_NUMBER, EmulatorController.GAME.getPackageName())) {
			logInfo("Whiteout Survival is not running. Launching the game...");
			emuManager.launchApp(EMULATOR_NUMBER, EmulatorController.GAME.getPackageName());
			sleepTask(10000); // Wait for game to launch
			lastVerifiedStartupState = "game launch requested and settle delay completed";
		} else {
			lastVerifiedStartupState = "game foreground package verified";
			logInfo("Whiteout Survival is already running.");
		}
	}

	private RawImageData captureStartupFrame(String inspection) {
		String serial;
		try {
			serial = emuManager.getDeviceSerial(EMULATOR_NUMBER);
		} catch (RuntimeException failure) {
			serial = "unavailable (" + failure.getClass().getSimpleName() + ")";
		}
		RawImageData frame = StartupCaptureRetry.capture(
				new StartupCaptureRetry.CaptureContext(
						EMULATOR_NUMBER, serial, inspection, lastVerifiedStartupState),
				() -> emuManager.captureScreen(EMULATOR_NUMBER),
				this::logWarning,
				this::sleepTask);
		lastStartupFrame = frame;
		return frame;
	}

	/**
	 * Waits for the home or world screen to appear.
	 * 
	 * <p>
	 * Continuously searches for home/world screen indicators up to
	 * MAX_HOME_SCREEN_ATTEMPTS. If a reconnect popup is detected, throws
	 * ProfileInReconnectStateException.
	 * 
	 * <p>
	 * If home screen is not found after all attempts, one Android Back recovery is
	 * allowed only while Whiteout Survival is verified in the foreground. A
	 * remaining blocker enters a profile cooldown and releases the emulator slot.
	 *
	 * <p>
	 * A verified update dialog uses only its detected button, then waits for a
	 * fresh known postcondition. Only a proven Google Play foreground redirect bypasses the
	 * generic startup recovery and escalates immediately.
	 *
	 * <p>
	 * The bounded unknown-overlay path:
	 * <ul>
	 * <li>Verifies that the game owns the foreground window</li>
	 * <li>Sends Android Back at most once</li>
	 * <li>Allows verified startup handlers to reveal a fresh home/world postcondition</li>
	 * <li>Otherwise stops only the game and enters a profile cooldown</li>
	 * </ul>
	 * 
	 * <p>
	 * If home screen is found, returns true to allow caller to proceed with
	 * character verification and initialization.
	 * 
	 * @return true if home screen was found, false if not found after max attempts
	 * @throws ProfileInReconnectStateException if reconnect popup detected
	 */
	private boolean waitForHomeScreen() {
		int attempts = 0;
		boolean homeScreenFound = false;

		while (attempts < MAX_HOME_SCREEN_ATTEMPTS) {
			if (searchForHomeScreen()) {
				homeScreenFound = true;
				lastVerifiedStartupState = "home/world screen";
				logInfo("Home screen found.");
				break;
			}

			checkForReconnectState();

			ResourceDownloadResult downloadResult = downloadRequiredResources();
			if (downloadResult == ResourceDownloadResult.COMPLETED) {
				homeScreenFound = true;
				logInfo("Home screen found after required resource download.");
				break;
			}
			if (downloadResult == ResourceDownloadResult.TIMED_OUT) {
				deferResourceDownloadTimeout();
			}

			if (dismissAnrDialogIfPresent()) {
				logInfo("Verified Android ANR dialog — tapped Wait. Letting the game recover.");
				sleepTask(ANR_WAIT_SETTLE_MS);
				attempts++;
				continue;
			}

			if (dismissWelcomeBackIfPresent()) {
				logInfo("Verified Welcome back dialog dismissed. Waiting for home/world postcondition.");
				sleepTask(2000);
				attempts++;
				continue;
			}

			MandatoryUpdateResult updateResult = followMandatoryUpdateIfPresent();
			if (updateResult == MandatoryUpdateResult.COMPLETED) {
				homeScreenFound = true;
				logInfo("Home screen found after the game update flow.");
				break;
			}

			if (dismissCloseableStartupOverlayIfPresent()) {
				logInfo("Verified closeable startup overlay dismissed. Waiting for home/world postcondition.");
				sleepTask(2000);
				attempts++;
				continue;
			}

			if (dismissTrekExitHintIfPresent()) {
				logInfo("Verified Trek exit hint dismissed. Waiting for a fresh home/world postcondition.");
				sleepTask(2000);
				attempts++;
				continue;
			}

			// Any mid-task UI (Intel, Alliance, Mail, deploy, …) — do not wait for a human.
			if (tryBoundedGameBackForUnknownScreen("home-finding loop")) {
				sleepTask(UNKNOWN_BLOCKER_BACK_SETTLE_MS);
				attempts++;
				continue;
			}

			// Passive checks and later recoveries do not retain a screenshot.
			logWarning("Home screen not found on an unsupported startup screen. "
					+ "Waiting 5 seconds for a passive state change before retrying...");
			sleepTask(5000);
			attempts++;
		}

		if (!homeScreenFound) {
			return recoverUnknownStartupBlocker();
		}
		
		return true;
	}

	private MandatoryUpdateResult followMandatoryUpdateIfPresent() {
		MandatoryUpdateInspection inspection;
		try {
			RawImageData capture = captureStartupFrame("mandatory-update dialog inspection");
			if (capture == null) {
				return MandatoryUpdateResult.NOT_PRESENT;
			}
			inspection = inspectMandatoryUpdate(capture);
		} catch (StartupCaptureException ex) {
			throw ex;
		} catch (RuntimeException ex) {
			logDebug("Mandatory-update screen inspection unavailable: " + ex.getMessage());
			return MandatoryUpdateResult.NOT_PRESENT;
		}

		MandatoryUpdateScreenClassifier.Evidence evidence = inspection.evidence();
		if (!evidence.detected()) {
			return MandatoryUpdateResult.NOT_PRESENT;
		}

		logInfo("Mandatory game update dialog and its action were verified. "
				+ evidence.technicalSummary());
		lastVerifiedStartupState = "mandatory in-game update dialog and button";
		if (!tapInside(inspection.updateButton())) {
			deferUnverifiedUpdatePostcondition(
					"The detected Update button could not be tapped safely.",
					evidence.technicalSummary());
		}
		logInfo("Tapped the detected in-game Update button. Waiting for a verified fresh postcondition.");
		lastVerifiedStartupState = "mandatory in-game Update action sent";
		return observeUpdatePostcondition(evidence);
	}

	private MandatoryUpdateResult observeUpdatePostcondition(
			MandatoryUpdateScreenClassifier.Evidence updateEvidence) {
		for (int attempt = 1; attempt <= MAX_UPDATE_POSTCONDITION_ATTEMPTS; attempt++) {
			sleepTask(UPDATE_POSTCONDITION_POLL_DELAY_MS);

			if (searchForHomeScreen()) {
				lastVerifiedStartupState = "home/world screen after update";
				logInfo("Game update flow returned to the home/world screen without operator action.");
				return MandatoryUpdateResult.COMPLETED;
			}
			checkForReconnectState();

			ResourceDownloadResult downloadResult = downloadRequiredResources();
			if (downloadResult == ResourceDownloadResult.COMPLETED) {
				return MandatoryUpdateResult.COMPLETED;
			}
			if (downloadResult == ResourceDownloadResult.TIMED_OUT) {
				deferUnverifiedUpdatePostcondition(
						"Required resources did not finish after the game update action.",
						updateEvidence.technicalSummary());
			}

			if (dismissWelcomeBackIfPresent()) {
				logInfo("Verified Welcome back dialog dismissed during the game update flow.");
				continue;
			}
			if (dismissCloseableStartupOverlayIfPresent()) {
				logInfo("Verified closeable startup overlay dismissed during the game update flow.");
				continue;
			}
			if (dismissTrekExitHintIfPresent()) {
				logInfo("Verified Trek exit hint dismissed during the game update flow.");
				continue;
			}
			if (tryBoundedGameBackForUnknownScreen("game update flow")) {
				sleepTask(UNKNOWN_BLOCKER_BACK_SETTLE_MS);
				continue;
			}

			RawImageData freshFrame = captureStartupFrame("post-update foreground screen inspection");
			StoreRedirectEvidence storeRedirect = inspectStoreRedirect(freshFrame);
			if (storeRedirect.detected()) {
				lastVerifiedStartupState = "Google Play foreground after in-game Update action";
				deferPlayStoreRedirect(updateEvidence, storeRedirect);
			}

			if (attempt % UPDATE_PROGRESS_LOG_INTERVAL == 0) {
				logInfo("Game update postcondition is still pending ("
						+ attempt * UPDATE_POSTCONDITION_POLL_DELAY_MS / 1000
						+ " seconds elapsed). No unverified screen input was sent.");
			}
		}

		deferUnverifiedUpdatePostcondition(
				"The Update action did not reach a known safe postcondition within "
						+ UPDATE_POSTCONDITION_TIMEOUT_MINUTES + " minutes.",
				updateEvidence.technicalSummary());
		return MandatoryUpdateResult.NOT_PRESENT;
	}

	private MandatoryUpdateInspection inspectMandatoryUpdate(RawImageData capture) {
		if (capture == null) {
			return new MandatoryUpdateInspection(
					MandatoryUpdateScreenClassifier.inspect(null, false, 0, false, 0),
					new ImageSearchResultData());
		}
		ImageSearchResultData title = OpenCvPatternLocator.locatePattern(
				capture,
				TemplatesEnum.GAME_START_MANDATORY_UPDATE_TITLE.getTemplate(),
				UPDATE_TITLE_AREA_TOP_LEFT,
				UPDATE_TITLE_AREA_BOTTOM_RIGHT,
				UPDATE_PATTERN_THRESHOLD);
		ImageSearchResultData updateButton = OpenCvPatternLocator.locatePattern(
				capture,
				TemplatesEnum.GAME_START_MANDATORY_UPDATE_BUTTON.getTemplate(),
				UPDATE_BUTTON_AREA_TOP_LEFT,
				UPDATE_BUTTON_AREA_BOTTOM_RIGHT,
				UPDATE_PATTERN_THRESHOLD);
		return new MandatoryUpdateInspection(
				MandatoryUpdateScreenClassifier.inspect(
						ImageConverter.toBufferedImage(capture),
						title.isFound(), title.getMatchScore(),
						updateButton.isFound(), updateButton.getMatchScore()),
				updateButton);
	}

	private boolean dismissWelcomeBackIfPresent() {
		if (welcomeBackDismissals >= MAX_WELCOME_BACK_DISMISSALS) {
			return false;
		}

		RawImageData capture = captureStartupFrame("Welcome back dialog inspection");
		if (capture == null) {
			return false;
		}
		ImageSearchResultData title = OpenCvPatternLocator.locatePattern(
				capture,
				TemplatesEnum.GAME_START_WELCOME_BACK_TITLE.getTemplate(),
				WELCOME_BACK_TITLE_AREA_TOP_LEFT,
				WELCOME_BACK_TITLE_AREA_BOTTOM_RIGHT,
				UPDATE_PATTERN_THRESHOLD);
		ImageSearchResultData confirm = OpenCvPatternLocator.locatePattern(
				capture,
				TemplatesEnum.GAME_START_WELCOME_BACK_CONFIRM_BUTTON.getTemplate(),
				WELCOME_BACK_CONFIRM_AREA_TOP_LEFT,
				WELCOME_BACK_CONFIRM_AREA_BOTTOM_RIGHT,
				UPDATE_PATTERN_THRESHOLD);
		if (!title.isFound() || !confirm.isFound()) {
			return false;
		}

		welcomeBackDismissals++;
		lastVerifiedStartupState = "Welcome back dialog and Confirm button";
		logInfo("Welcome back startup dialog and Confirm action verified from one fresh frame"
				+ "; titlePattern=" + String.format(java.util.Locale.ROOT, "%.1f", title.getMatchScore())
				+ "%"
				+ "; confirmPattern=" + String.format(java.util.Locale.ROOT, "%.1f", confirm.getMatchScore())
				+ "%");
		if (!tapInside(confirm)) {
			logWarning("Verified Welcome back Confirm area was unavailable for a safe tap; no fallback input sent.");
			return false;
		}
		lastVerifiedStartupState = "Welcome back Confirm action sent";
		return true;
	}

	private boolean dismissCloseableStartupOverlayIfPresent() {
		if (closeableOverlayDismissals >= MAX_CLOSEABLE_OVERLAY_DISMISSALS) {
			return false;
		}

		RawImageData capture = captureStartupFrame("closeable startup overlay inspection");
		if (capture == null) {
			return false;
		}
		CloseCrossDetector.Detection detectedClose = locateCloseableStartupControls(capture)
				.stream()
				.findFirst()
				.orElse(null);
		if (detectedClose == null) {
			return false;
		}
		return dismissDetectedCloseableStartupOverlay(detectedClose);
	}

	private boolean dismissDetectedCloseableStartupOverlay(CloseCrossDetector.Detection detectedClose) {
		ImageSearchResultData close = ImageSearchResultData.hit(
				detectedClose.center().getX(), detectedClose.center().getY(), detectedClose.score(),
				detectedClose.width(), detectedClose.height());

		closeableOverlayDismissals++;
		lastVerifiedStartupState = "closeable startup overlay and concrete close control";
		logInfo("Closeable startup overlay verified from a fresh frame"
				+ "; closeCrossScore="
				+ String.format(java.util.Locale.ROOT, "%.1f", close.getMatchScore())
				+ "%"
				+ "; dismissal=" + closeableOverlayDismissals
				+ "/" + MAX_CLOSEABLE_OVERLAY_DISMISSALS + ".");
		if (!tapInside(close)) {
			logWarning("Verified startup-overlay close area was unavailable for a safe tap; no fallback input sent.");
			return false;
		}
		lastVerifiedStartupState = "closeable startup overlay close action sent";
		logInfo("Tapped the verified startup-overlay close control; waiting for a fresh home/world postcondition.");
		return true;
	}

	static java.util.List<CloseCrossDetector.Detection> locateCloseableStartupControls(RawImageData capture) {
		return CloseCrossDetector.locateBrightOrWarm(capture, CLOSEABLE_OVERLAY_SEARCH_AREA)
				.stream()
				.filter(candidate -> candidate.center().getY() <= MAX_CLOSEABLE_OVERLAY_CENTER_Y)
				.toList();
	}

	/**
	 * Generic escape for unknown in-game screens left open after ADB recovery or
	 * a killed task. Only while Whiteout Survival owns the foreground; budget is
	 * shared with {@link #recoverUnknownStartupBlocker()}.
	 */
	private boolean tryBoundedGameBackForUnknownScreen(String phase) {
		boolean gameForeground = isGameForeground();
		if (StartupRecoveryPolicy.forUnknownBlocker(gameForeground, unknownBlockerBackAttempts)
				!= StartupRecoveryPolicy.UnknownBlockerAction.TRY_GAME_BACK) {
			return false;
		}
		unknownBlockerBackAttempts++;
		lastVerifiedStartupState = "unknown in-game screen with Whiteout Survival foreground (" + phase + ")";
		logWarning("Home/world unavailable on an unsupported screen (" + phase
				+ "). Whiteout Survival owns the foreground; sending bounded Android Back "
				+ unknownBlockerBackAttempts + "/"
				+ StartupRecoveryPolicy.MAX_UNKNOWN_BLOCKER_BACK_ATTEMPTS + ".");
		pressBack();
		lastVerifiedStartupState = "bounded Android Back sent (" + phase + ")";
		return true;
	}

	private boolean dismissTrekExitHintIfPresent() {
		if (trekExitHintDismissals >= MAX_TREK_EXIT_HINT_DISMISSALS) {
			return false;
		}

		RawImageData capture = captureStartupFrame("lower Trek exit-hint inspection");
		if (capture == null) {
			return false;
		}

		TrekExitHintClassifier.Evidence evidence;
		try {
			String ocrText = emuManager.readText(
					EMULATOR_NUMBER,
					CommonGameAreas.STARTUP_EXIT_HINT_OCR_AREA.topLeft(),
					CommonGameAreas.STARTUP_EXIT_HINT_OCR_AREA.bottomRight(),
					CommonOCRSettings.STARTUP_EXIT_HINT_SETTINGS,
					true);
			evidence = TrekExitHintClassifier.inspect(ocrText);
		} catch (IOException | OcrException | RuntimeException exception) {
			logDebug("Lower startup exit-hint OCR unavailable: " + exception.getMessage());
			return false;
		}
		if (!evidence.detected()) {
			return false;
		}

		trekExitHintDismissals++;
		lastVerifiedStartupState = "Trek exit hint in lower band";
		logInfo("Trek exit hint verified from one fresh lower-band frame"
				+ "; normalizedEvidence='" + evidence.normalizedText() + "'"
				+ "; dismissal=" + trekExitHintDismissals + "/" + MAX_TREK_EXIT_HINT_DISMISSALS + ".");
		tapInside(CommonGameAreas.STARTUP_EXIT_HINT_TAP_AREA);
		lastVerifiedStartupState = "Trek lower-centre exit action sent";
		logInfo("Tapped the verified lower-centre Trek exit-hint area; outcome pending a fresh home/world frame.");
		return true;
	}

	/**
	 * Android "isn't responding" system dialog. Prefer Wait so the game can recover;
	 * Close app would force a full relaunch and lose mid-session state.
	 */
	private boolean dismissAnrDialogIfPresent() {
		if (anrWaitDismissals >= MAX_ANR_WAIT_DISMISSALS) {
			return false;
		}

		RawImageData capture = captureStartupFrame("Android ANR dialog inspection");
		if (capture == null) {
			return false;
		}

		AnrDialogClassifier.Evidence evidence;
		try {
			String ocrText = emuManager.readText(
					EMULATOR_NUMBER,
					CommonGameAreas.ANR_DIALOG_OCR_AREA.topLeft(),
					CommonGameAreas.ANR_DIALOG_OCR_AREA.bottomRight(),
					CommonOCRSettings.ANR_DIALOG_SETTINGS,
					true);
			evidence = AnrDialogClassifier.inspect(ocrText);
		} catch (IOException | OcrException | RuntimeException exception) {
			logDebug("Android ANR dialog OCR unavailable: " + exception.getMessage());
			return false;
		}
		if (!evidence.detected()) {
			return false;
		}

		anrWaitDismissals++;
		lastVerifiedStartupState = "Android ANR dialog";
		logWarning("Android ANR dialog verified from a fresh frame"
				+ "; normalizedEvidence='" + evidence.normalizedText() + "'"
				+ "; action=Wait"
				+ "; dismissal=" + anrWaitDismissals + "/" + MAX_ANR_WAIT_DISMISSALS + ".");
		tapInside(CommonGameAreas.ANR_WAIT_TAP_AREA);
		lastVerifiedStartupState = "Android ANR Wait action sent";
		return true;
	}

	private StoreRedirectEvidence inspectStoreRedirect(RawImageData capture) {
		boolean playStoreForeground = emuManager.isPackageRunning(EMULATOR_NUMBER, GOOGLE_PLAY_PACKAGE);
		return new StoreRedirectEvidence(
				isVerifiedPlayStoreRedirect(playStoreForeground, capture != null),
				playStoreForeground,
				capture != null);
	}

	static boolean isVerifiedPlayStoreRedirect(
			boolean playStoreForeground, boolean freshFrameCaptured) {
		return playStoreForeground && freshFrameCaptured;
	}

	private record StoreRedirectEvidence(boolean detected,
			boolean playStoreForeground, boolean freshFrameCaptured) {
	}

	private record MandatoryUpdateInspection(
			MandatoryUpdateScreenClassifier.Evidence evidence,
			ImageSearchResultData updateButton) {
	}

	private void deferPlayStoreRedirect(MandatoryUpdateScreenClassifier.Evidence updateEvidence,
			StoreRedirectEvidence storeRedirect) {
		LocalDateTime retryAt = LocalDateTime.now().plus(StartupRecoveryPolicy.PLAY_STORE_REDIRECT_COOLDOWN);
		ProfileCooldownException cooldown = playStoreRedirectCooldown(retryAt);
		StartupBlockerSnapshots.Retention snapshot = retainTerminalSnapshot(
				StartupBlockerSnapshots.TYPE_PLAY_STORE_REDIRECT);
		logError("Initialization requires operator action for profile=" + profile.getName()
				+ ", expected=update completed outside Frostguard or home/world"
				+ ", observed=Google Play foreground package"
				+ ", lastAction=tapped verified in-game Update button"
				+ ", fallback=stop-game-and-release-slot, retryAt=" + retryAt
				+ ". Technical evidence: " + updateEvidence.technicalSummary()
				+ "; foreground package " + GOOGLE_PLAY_PACKAGE + ": "
				+ storeRedirect.playStoreForeground()
				+ "; fresh post-click frame captured: " + storeRedirect.freshFrameCaptured()
				+ "; snapshot=" + snapshot.logToken()
				+ "; snapshotBasis=" + snapshot.basis() + ".");
		throw cooldown.withEvidencePath(snapshot.saved() ? snapshot.relativePath() : "");
	}

	static ProfileCooldownException playStoreRedirectCooldown(LocalDateTime retryAt) {
		String reason = "Google Play is waiting for the game update to be completed.";
		return new ProfileCooldownException(reason, retryAt, new ActionRequiredContext(
				"startup.play-store-redirect",
				"Complete the game update in Google Play",
				"Game update completed outside Frostguard or home/world screen",
				"Google Play foreground package after the in-game update action",
				"Tapped the detected in-game Update button, then captured a fresh frame while Google Play was foreground",
				"Stop the game, release the slot, and retry initialization after one hour"));
	}

	private void deferUnverifiedUpdatePostcondition(String reason, String technicalEvidence) {
		LocalDateTime retryAt = LocalDateTime.now().plus(StartupRecoveryPolicy.UPDATE_FOLLOW_UP_COOLDOWN);
		StartupBlockerSnapshots.Retention snapshot = retainTerminalSnapshot(
				StartupBlockerSnapshots.TYPE_UPDATE_FOLLOW_UP);
		logError("Initialization paused for profile=" + profile.getName()
				+ ", expected=automatic update, resource download, Play Store redirect, or home/world"
				+ ", observed=unsupported update follow-up, lastAction=tapped verified Update button"
				+ ", fallback=stop-game-and-release-slot, retryAt=" + retryAt
				+ ", reason=" + reason + " Technical evidence: " + technicalEvidence
				+ "; snapshot=" + snapshot.logToken()
				+ "; snapshotBasis=" + snapshot.basis() + ".");
		throw new ProfileCooldownException(reason, retryAt)
				.withEvidencePath(snapshot.saved() ? snapshot.relativePath() : "");
	}

	private enum MandatoryUpdateResult {
		NOT_PRESENT,
		COMPLETED
	}

	/**
	 * Searches for home or world screen indicators.
	 * 
	 * @return true if home or world screen is found, false otherwise
	 */
	private boolean searchForHomeScreen() {
		RawImageData frame = captureStartupFrame("home/world pattern inspection");
		ImageSearchResultData home = emuManager.locatePattern(
				EMULATOR_NUMBER, frame, TemplatesEnum.GAME_HOME_FURNACE, STARTUP_PATTERN_THRESHOLD);
		ImageSearchResultData world = emuManager.locatePattern(
				EMULATOR_NUMBER, frame, TemplatesEnum.GAME_HOME_WORLD, STARTUP_PATTERN_THRESHOLD);

		return home.isFound() || world.isFound();
	}

	/**
	 * Chooses the foreground download when a resource-pack prompt blocks startup,
	 * then waits for the game to reach the home or world screen. Pressing Enter
	 * Game would allow automation to run while required assets are still missing.
	 *
	 * @return the observed prompt/download outcome
	 */
	private ResourceDownloadResult downloadRequiredResources() {
		RawImageData frame = captureStartupFrame("resource-download prompt inspection");
		ImageSearchResultData downloadNow = emuManager.locatePattern(
				EMULATOR_NUMBER, frame, TemplatesEnum.GAME_START_DOWNLOAD_NOW, STARTUP_PATTERN_THRESHOLD);

		if (!downloadNow.isFound()) {
			return ResourceDownloadResult.NOT_PRESENT;
		}

		logInfo("Resource download prompt detected. Downloading required resources before entering the game.");
		lastVerifiedStartupState = "resource-download prompt and Download Now button";
		tapInside(downloadNow);
		lastVerifiedStartupState = "resource-download action sent";

		for (int attempt = 1; attempt <= MAX_RESOURCE_DOWNLOAD_ATTEMPTS; attempt++) {
			sleepTask(RESOURCE_DOWNLOAD_POLL_DELAY_MS);
			if (searchForHomeScreen()) {
				logInfo("Required resource download completed.");
				return ResourceDownloadResult.COMPLETED;
			}
			checkForReconnectState();
			if (dismissWelcomeBackIfPresent()) {
				logInfo("Verified Welcome back dialog dismissed after the required resource download.");
			}
			if (dismissCloseableStartupOverlayIfPresent()) {
				logInfo("Verified closeable startup overlay dismissed after the required resource download.");
			}
			if (dismissTrekExitHintIfPresent()) {
				logInfo("Verified Trek exit hint dismissed after the required resource download.");
			}
			if (tryBoundedGameBackForUnknownScreen("resource download")) {
				sleepTask(UNKNOWN_BLOCKER_BACK_SETTLE_MS);
			}
			if (attempt % RESOURCE_DOWNLOAD_PROGRESS_LOG_INTERVAL == 0) {
				logInfo("Required resource download still in progress ("
						+ attempt * RESOURCE_DOWNLOAD_POLL_DELAY_MS / 1000 + " seconds elapsed).");
			}
		}

		logError("Required resource download did not reach the home screen within "
				+ RESOURCE_DOWNLOAD_TIMEOUT_MINUTES + " minutes.");
		return ResourceDownloadResult.TIMED_OUT;
	}

	private enum ResourceDownloadResult {
		NOT_PRESENT,
		COMPLETED,
		TIMED_OUT
	}

	private void deferResourceDownloadTimeout() {
		LocalDateTime retryAt = LocalDateTime.now().plus(StartupRecoveryPolicy.UNKNOWN_BLOCKER_COOLDOWN);
		String reason = "required resources did not finish before the startup timeout";
		StartupBlockerSnapshots.Retention snapshot = retainTerminalSnapshot(
				StartupBlockerSnapshots.TYPE_RESOURCE_DOWNLOAD_TIMEOUT);
		logError("Initialization blocked for profile=" + profile.getName()
				+ ", expected=resource download completed and home/world"
				+ ", observed=resource-download-timeout, lastAction=tapped verified Download Now button"
				+ ", fallback=stop-game-and-release-slot, retryAt=" + retryAt
				+ ", reason=" + reason
				+ ", snapshot=" + snapshot.logToken()
				+ ", snapshotBasis=" + snapshot.basis() + ".");
		throw new ProfileCooldownException(reason, retryAt, new ActionRequiredContext(
				"startup.resource-download-timeout",
				"Required game resources did not finish downloading",
				"Resource download completed and home/world available",
				"Resource download remained incomplete for ten minutes",
				"Tapped the verified Download Now button, then waited without further input",
				"Stop the game, release the slot, and retry initialization after fifteen minutes"))
				.withEvidencePath(snapshot.saved() ? snapshot.relativePath() : "");
	}

	/**
	 * Checks for reconnect state popup.
	 * 
	 * <p>
	 * If the reconnect popup is detected, throws ProfileInReconnectStateException
	 * to notify the queue that the profile needs to reconnect before automation
	 * can continue.
	 * 
	 * @throws ProfileInReconnectStateException if reconnect popup is found
	 */
	private void checkForReconnectState() {
		RawImageData frame = captureStartupFrame("reconnect-state inspection");
		ImageSearchResultData reconnect = emuManager.locatePattern(
				EMULATOR_NUMBER, frame, TemplatesEnum.GAME_HOME_RECONNECT, STARTUP_PATTERN_THRESHOLD);

		if (reconnect.isFound()) {
			throw new ProfileInReconnectStateException(
					"Profile " + profile.getName() + " is in a reconnect state and cannot execute the task: "
							+ taskName);
		}
	}

	/**
	 * Handles the case where home screen was not found after all attempts.
	 * 
	 * <p>
	 * Strategy after known startup-state checks and passive waiting are exhausted:
	 * <ol>
	 * <li>Verify that Whiteout Survival owns the foreground window</li>
	 * <li>Spend remaining bounded Android Back budget (shared with the home-finding loop)</li>
	 * <li>Re-run only verified startup handlers while waiting for home/world</li>
	 * <li>Otherwise enter a cooldown that stops only the game and releases the slot</li>
	 * </ol>
	 */
	private boolean recoverUnknownStartupBlocker() {
		boolean gameForeground = isGameForeground();
		while (tryBoundedGameBackForUnknownScreen("post-loop recovery")) {
			for (int attempt = 1; attempt <= MAX_UNKNOWN_BLOCKER_POSTCONDITION_ATTEMPTS; attempt++) {
				sleepTask(UNKNOWN_BLOCKER_BACK_SETTLE_MS);
				if (searchForHomeScreen()) {
					lastVerifiedStartupState = "home/world screen after bounded Android Back";
					logInfo("Home screen found after bounded Android Back recovery.");
					return true;
				}

				checkForReconnectState();
				if (dismissAnrDialogIfPresent()) {
					logInfo("Verified Android ANR dialog — tapped Wait after bounded Android Back recovery.");
					sleepTask(ANR_WAIT_SETTLE_MS);
					continue;
				}
				if (dismissWelcomeBackIfPresent()) {
					logInfo("Verified Welcome back dialog dismissed after bounded Android Back recovery.");
					continue;
				}
				if (dismissCloseableStartupOverlayIfPresent()) {
					logInfo("Verified closeable startup overlay dismissed after bounded Android Back recovery.");
				}
				if (dismissTrekExitHintIfPresent()) {
					logInfo("Verified Trek exit hint dismissed after bounded Android Back recovery.");
				}
			}
		}

		deferUnknownStartupBlocker(gameForeground);
		return false;
	}

	private boolean isGameForeground() {
		return emuManager.isPackageRunning(EMULATOR_NUMBER, EmulatorController.GAME.getPackageName());
	}

	private void deferUnknownStartupBlocker(boolean gameForeground) {
		LocalDateTime retryAt = LocalDateTime.now().plus(StartupRecoveryPolicy.UNKNOWN_BLOCKER_COOLDOWN);
		String reason = "home/world remained unavailable after bounded in-game recovery";
		StartupBlockerSnapshots.Retention snapshot = retainTerminalSnapshot(
				StartupBlockerSnapshots.TYPE_INITIALIZE_BLOCKED);
		logError("Initialization blocked for profile=" + profile.getName()
				+ ", expected=home/world, observed=unknown-startup-blocker"
				+ ", gameForeground=" + gameForeground
				+ ", lastAction=" + (unknownBlockerBackAttempts > 0 ? "bounded-android-back" : "none")
				+ ", recoveryAttempts=" + unknownBlockerBackAttempts
				+ "/" + StartupRecoveryPolicy.MAX_UNKNOWN_BLOCKER_BACK_ATTEMPTS
				+ ", fallback=stop-game-and-release-slot, retryAt=" + retryAt
				+ ", reason=" + reason
				+ ", snapshot=" + snapshot.logToken()
				+ ", snapshotBasis=" + snapshot.basis() + ".");
		throw new ProfileCooldownException(reason, retryAt, new ActionRequiredContext(
				"startup.home-unavailable-after-game-back",
				"Startup remains blocked after automatic recovery",
				"home/world",
				"unsupported startup screen",
				gameForeground
						? "Bounded Android Back (" + unknownBlockerBackAttempts + "/"
								+ StartupRecoveryPolicy.MAX_UNKNOWN_BLOCKER_BACK_ATTEMPTS
								+ ") sent only while Whiteout Survival owned the foreground window"
						: "No input sent because Whiteout Survival did not own the foreground window",
				"Stop the game, release the slot, and retry initialization after fifteen minutes"))
				.withEvidencePath(snapshot.saved() ? snapshot.relativePath() : "");
	}

	/**
	 * Saves the last decision frame before the queue stops the game.
	 * A missing frame gets one fresh capture, marked best-effort. Failure
	 * does not replace the cooldown.
	 */
	private StartupBlockerSnapshots.Retention retainTerminalSnapshot(String type) {
		StartupBlockerSnapshots.Retention retention = StartupBlockerSnapshots.retain(
				startupSnapshots,
				lastStartupFrame,
				() -> captureStartupFrame("terminal startup snapshot"),
				type,
				Instant.now());
		if (!retention.saved()) {
			logWarning("Startup snapshot was not saved; cooldown continues without screen evidence.");
		}
		return retention;
	}

	/**
	 * Verifies and switches character if needed.
	 * 
	 * <p>
	 * This method:
	 * <ol>
	 * <li>Verifies current character matches profile configuration</li>
	 * <li>If character doesn't match, switches to correct character</li>
	 * <li>If character matches or config not set, continues normally</li>
	 * </ol>
	 * 
	 * <p>
	 * If character switching fails (character not found), the emulator is closed
	 * and the method returns false. The queue will continue to the next profile.
	 * 
	 * <p>
	 * If character switch is successful, waits for game to reload and re-checks
	 * home screen before returning true.
	 * 
	 * @return true if character verification/switching succeeded, false if failed
	 */
	private boolean verifyAndSwitchCharacter() {
		// Check if character configuration is set
		String characterName = profile.getCharacterName();
		String characterId = profile.getCharacterId();
		
		// If no character info is configured, skip verification
		if ((characterName == null || characterName.isEmpty()) &&
			(characterId == null || characterId.isEmpty())) {
			logInfo("No character configuration found. Skipping character verification.");
			return true; // Continue with initialization
		}
		
		// Verify current character
		boolean characterMatches = characterSwitchHelper.verifyCurrentCharacter(profile);
		
		if (!characterMatches) {
			logInfo("Current character does not match profile configuration. Switching character...");
			
			// Switch to correct character
			boolean switchSuccess = characterSwitchHelper.switchToCharacter(profile);
			
			if (!switchSuccess) {
				// Character not found - emulator already closed by helper
				// According to requirements: do not retry, continue to next profile
				logError("Character switching failed. Character not found. Continuing to next profile.");
				// Reset isStarted flag since emulator was closed
				isStarted = false;
				// Do not set recurring=true - let queue continue to next profile
				return false;
			}
			
			// Character switch successful, wait for game to reload
			logInfo("Character switch successful. Waiting for game to reload...");
			sleepTask(CharacterSwitchHelper.CHARACTER_SWITCH_RELOAD_DELAY_MS);
			
			// Re-check home screen after character switch
			if (!waitForHomeScreen()) {
				// Home screen not found after character switch
				return false;
			}
			
			logInfo("Home screen verified after character switch.");
		} else {
			logInfo("Character verification passed - correct character is active.");
		}
		
		return true;
	}

	/**
	 * Coordinates Connect/Reconnect with the paired profile so both emulators end
	 * on their configured characters without the uncoordinated reconnect pause loop.
	 */
	private boolean runSharedLoginHandshake(SharedLoginPair pair) {
		SharedLoginHandshakeCoordinator coordinator = SharedLoginHandshakeCoordinator.shared();
		SharedLoginPairGate.markPending(pair);
		logInfo("Shared login handshake active with peer profile id="
				+ pair.peerOf(profile.getId()).getId()
				+ "; farming blocked until both characters match");
		requestSharedLoginPeerJoin(pair, "initialize handshake");

		int attempt = 0;
		while (true) {
			checkPreemption();
			attempt++;
			// Fresh dismiss budgets each attempt — login ads can reappear after Connect.
			closeableOverlayDismissals = 0;
			welcomeBackDismissals = 0;
			trekExitHintDismissals = 0;
			quitGameDismissals = 0;
			SharedLoginHandshakeSession session = coordinator.join(pair);
			logInfo("Shared login handshake attempt " + attempt);
			boolean ok = runSharedLoginHandshakeAttempt(session);
			if (ok) {
				coordinator.finish(session);
				return true;
			}
			String reason = session.failureReason() == null
					? "handshake attempt failed"
					: session.failureReason();
			coordinator.finish(session);
			logWarning("Shared login handshake attempt " + attempt + " failed (" + reason
					+ "); retrying — farming stays blocked.");
			requestSharedLoginPeerJoin(pair, "handshake retry " + (attempt + 1));
			long backoffMs = Math.min(15_000L, 2_000L + (attempt * 1_000L));
			sleepTask(backoffMs);
		}
	}

	private boolean runSharedLoginHandshakeAttempt(SharedLoginHandshakeSession session) {
		awaitSharedLoginHomeOrReconnect();
		Instant deadline = Instant.now().plus(SharedLoginHandshakeSession.SESSION_TIMEOUT);
		Instant nextPeerKickAt = Instant.now().plusSeconds(20);
		while (Instant.now().isBefore(deadline)) {
			checkPreemption();
			if (session.isFailed()) {
				logError("Shared login handshake failed: " + session.failureReason());
				return false;
			}
			SharedLoginObservation observation = observeSharedLoginScreen(session.pair());
			SharedLoginDirective directive = session.next(profile.getId(), observation);
			logInfo("Shared login directive: " + directive.action()
					+ (directive.detail() == null ? "" : " — " + directive.detail()));

			switch (directive.action()) {
				case WAIT -> {
					if (directive.detail() != null
							&& directive.detail().contains("Waiting for shared-login peer")
							&& !Instant.now().isBefore(nextPeerKickAt)) {
						requestSharedLoginPeerJoin(session.pair(), "peer still missing from handshake");
						nextPeerKickAt = Instant.now().plusSeconds(20);
					}
					// Peer/self stuck on War/Intel/formation/etc. without closeable ads —
					// escalate with bounded Back so handshake does not idle until timeout.
					if (directive.detail() != null
							&& directive.detail().contains("inconclusive polls")) {
						escalateSharedLoginInconclusiveScreen();
					}
					sleepTask(1500);
				}
				case PREP_RECONNECT -> {
					if (!tapReconnectButton()) {
						logWarning("Shared login prep reconnect button not found; retrying.");
						sleepTask(1500);
						break;
					}
					sleepTask(CharacterSwitchHelper.CHARACTER_SWITCH_RELOAD_DELAY_MS);
					session.markPrepReconnectDone(profile.getId());
				}
				case PREPARE_SWITCH_AND_COMMIT -> {
					if (characterSwitchHelper.verifyCurrentCharacter(profile)) {
						logInfo("Shared login switcher already on owned character; skipping Connect");
						session.markCommitFinished();
						break;
					}
					boolean switched = characterSwitchHelper.switchToCharacter(
							directive.targetCharacterName(),
							() -> awaitSharedLoginBarrier(session, true),
							false,
							true);
					if (!switched) {
						session.abort("Switcher could not complete Switch Character / Connect");
						logError("Shared login switcher Connect failed.");
						return false;
					}
					session.markCommitFinished();
				}
				case ARM_RECONNECT_AND_COMMIT -> {
					if (!isReconnectVisible()) {
						if (characterSwitchHelper.verifyCurrentCharacter(profile)) {
							logInfo("Shared login waiter already on owned character (no Reconnect); skipping to verify");
							session.markCommitFinished();
							break;
						}
						logWarning("Shared login waiter expected Reconnect; waiting.");
						sleepTask(1000);
						break;
					}
					if (!awaitSharedLoginBarrier(session, false)) {
						if (!session.isFailed()) {
							session.abort("Reconnect barrier failed");
						}
						logError("Shared login Reconnect barrier failed.");
						return false;
					}
					if (!tapReconnectButton()) {
						session.abort("Waiter failed to tap Reconnect");
						logError("Shared login waiter failed to tap Reconnect.");
						return false;
					}
					sleepTask(CharacterSwitchHelper.CHARACTER_SWITCH_RELOAD_DELAY_MS);
					session.markCommitFinished();
				}
				case VERIFY -> {
					dismissQuitGameIfPresent();
					boolean ok = characterSwitchHelper.verifyCurrentCharacter(profile);
					SharedLoginDirective after = session.reportVerify(profile.getId(), ok);
					logInfo("Shared login verify=" + ok + " next=" + after.action()
							+ (after.detail() == null ? "" : " — " + after.detail()));
					if (after.action() == SharedLoginDirective.Action.DONE) {
						return true;
					}
					if (after.action() == SharedLoginDirective.Action.FAILED) {
						logError("Shared login handshake failed: " + after.detail());
						return false;
					}
					sleepTask(1500);
				}
				case DONE -> {
					return true;
				}
				case FAILED -> {
					logError("Shared login handshake failed: " + directive.detail());
					return false;
				}
			}
		}
		session.abort("Shared login handshake attempt timed out");
		logError("Shared login handshake attempt timed out.");
		return false;
	}

	private void requestSharedLoginPeerJoin(SharedLoginPair pair, String reason) {
		try {
			TaskDispatcher dispatcher = ScheduleService.obtain().getCoordinator();
			if (dispatcher == null) {
				logWarning("Shared login peer kick skipped; schedule coordinator unavailable.");
				return;
			}
			Long peerId = pair.peerOf(profile.getId()).getId();
			TaskQueue peerQueue = dispatcher.getQueue(peerId);
			if (peerQueue == null) {
				logWarning("Shared login peer queue not found for id=" + peerId);
				return;
			}
			peerQueue.joinSharedLoginHandshake(reason + " from " + profile.getName());
		} catch (RuntimeException failure) {
			logWarning("Shared login peer kick failed: " + failure.getMessage());
		}
	}

	/**
	 * Soft-waits for home or Reconnect without throwing {@link ProfileInReconnectStateException},
	 * so the pair coordinator owns reconnect timing.
	 */
	private void awaitSharedLoginHomeOrReconnect() {
		for (int attempt = 0; attempt < MAX_HOME_SCREEN_ATTEMPTS; attempt++) {
			checkPreemption();
			if (isReconnectVisible() || searchForHomeScreen() || searchForSharedLoginGameplayChrome()) {
				return;
			}
			dismissSharedLoginBlockers();
			downloadRequiredResources();
			sleepTask(2000);
		}
		logWarning("Shared login settle ended without home or Reconnect; handshake will keep polling.");
	}

	/**
	 * Ready-path owned-character check with a few OCR retries. Inconclusive sheet reads
	 * (short junk / null) must not force markPending + full handshake.
	 */
	private CharacterSwitchHelper.CharacterVerifyResult classifySharedLoginReadyCharacter() {
		CharacterSwitchHelper.CharacterVerifyResult last =
				CharacterSwitchHelper.CharacterVerifyResult.INCONCLUSIVE;
		for (int attempt = 1; attempt <= 3; attempt++) {
			checkPreemption();
			dismissQuitGameIfPresent();
			last = characterSwitchHelper.classifyCurrentCharacter(profile);
			if (last == CharacterSwitchHelper.CharacterVerifyResult.MATCH
					|| last == CharacterSwitchHelper.CharacterVerifyResult.MISMATCH) {
				return last;
			}
			logInfo("Shared login ready-path verify inconclusive (" + attempt + "/3); retrying");
			dismissQuitGameIfPresent();
			sleepTask(800);
		}
		return last;
	}

	/**
	 * When observe cannot find Home/Reconnect and overlays did not clear, leave mid-task
	 * UI with a few Backs (Quit Game Cancel after each). Avoids handshake timeouts while
	 * one emulator sits on War/Intel/formation with the peer already home.
	 */
	private void escalateSharedLoginInconclusiveScreen() {
		if (isReconnectVisible() || searchForHomeScreen() || searchForSharedLoginGameplayChrome()) {
			return;
		}
		if (dismissSharedLoginBlockers()) {
			logInfo("Shared login: cleared blocker during inconclusive escalate");
			return;
		}
		logInfo("Shared login: inconclusive screen; bounded Back to recover home/Reconnect");
		for (int i = 0; i < 2; i++) {
			pressBack();
			sleepTask(500);
			dismissQuitGameIfPresent();
			if (isReconnectVisible() || searchForHomeScreen() || searchForSharedLoginGameplayChrome()) {
				return;
			}
		}
	}

	private SharedLoginObservation observeSharedLoginScreen(SharedLoginPair pair) {
		dismissQuitGameIfPresent();
		boolean reconnect = isReconnectVisible();
		if (reconnect) {
			return new SharedLoginObservation(true, false, null);
		}
		boolean inGame = searchForHomeScreen() || searchForSharedLoginGameplayChrome();
		if (!inGame) {
			// Login/promo ads block furnace/world chrome. Prefer closeable-overlay dismiss
			// first — generic Back opens Quit Game. Escalate happens on WAIT polls.
			if (dismissSharedLoginBlockers()) {
				logInfo("Shared login: dismissed a startup blocker; re-checking home/Reconnect");
			}
			return new SharedLoginObservation(false, false, null);
		}

		// City/World chrome is enough to count as connected. When both sides report that,
		// the session goes straight to VERIFY (owned-character check) without needing OCR
		// names for role assignment. Only read the avatar on a cooldown, and only trust
		// names that match a paired character (garbage like "Be" / "Cgo I goo" is ignored).
		String trustedName = null;
		if (!Instant.now().isBefore(nextSharedLoginCharacterReadAt)) {
			nextSharedLoginCharacterReadAt = Instant.now()
					.plusMillis(SHARED_LOGIN_CHARACTER_READ_COOLDOWN_MS);
			dismissQuitGameIfPresent();
			CharacterSwitchHelper.LiveCharacter live = characterSwitchHelper.readLiveCharacter();
			dismissQuitGameIfPresent();
			if (live != null && pair.matchesEitherCharacterName(live.name())) {
				trustedName = live.name().trim();
				logInfo("Shared login: trusted live character '" + trustedName + "'");
			} else if (live != null && live.name() != null && !live.name().isBlank()) {
				logInfo("Shared login: ignoring untrusted live OCR '" + live.name() + "'");
			}
		}
		return new SharedLoginObservation(false, true, trustedName);
	}

	/**
	 * Promo/login ads, Welcome back, Trek exit hints, ANR, Quit Game — same handlers as
	 * normal Initialize, without generic Back (shared-login must not thrash Quit Game).
	 *
	 * @return true when any dismiss action was taken
	 */
	private boolean dismissSharedLoginBlockers() {
		boolean acted = false;
		acted |= dismissQuitGameIfPresent();
		acted |= dismissWelcomeBackIfPresent();
		acted |= dismissCloseableStartupOverlayIfPresent();
		acted |= dismissTrekExitHintIfPresent();
		acted |= dismissAnrDialogIfPresent();
		return acted;
	}

	/**
	 * Extra City/World markers for when furnace/world templates miss (zoom, overlays).
	 * Avoids opening the profile just to prove the game is playable.
	 */
	private boolean searchForSharedLoginGameplayChrome() {
		RawImageData frame = captureStartupFrame("shared-login gameplay chrome");
		TemplatesEnum[] markers = {
				TemplatesEnum.GAME_HOME_PETS,
				TemplatesEnum.GAME_HOME_INTEL,
				TemplatesEnum.GAME_HOME_BOTTOM_BAR_SHOP_BUTTON,
				TemplatesEnum.GAME_HOME_BOTTOM_BAR_BACKPACK_BUTTON
		};
		for (TemplatesEnum marker : markers) {
			ImageSearchResultData hit = emuManager.locatePattern(
					EMULATOR_NUMBER, frame, marker, STARTUP_PATTERN_THRESHOLD);
			if (hit != null && hit.isFound()) {
				return true;
			}
		}
		return false;
	}

	/** Dismiss the City/World "Quit Game?" prompt opened by accidental Back presses. */
	private boolean dismissQuitGameIfPresent() {
		if (quitGameDismissals >= MAX_QUIT_GAME_DISMISSALS) {
			return false;
		}
		RawImageData capture = captureStartupFrame("Quit Game dialog inspection");
		if (capture == null) {
			return false;
		}
		String ocrText;
		try {
			ocrText = emuManager.readText(
					EMULATOR_NUMBER,
					CommonGameAreas.QUIT_GAME_DIALOG_OCR_AREA.topLeft(),
					CommonGameAreas.QUIT_GAME_DIALOG_OCR_AREA.bottomRight(),
					CommonOCRSettings.ANR_DIALOG_SETTINGS,
					true);
		} catch (IOException | OcrException | RuntimeException exception) {
			logDebug("Quit Game dialog OCR unavailable: " + exception.getMessage());
			return false;
		}
		if (ocrText == null) {
			return false;
		}
		String normalized = ocrText.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "");
		if (!(normalized.contains("quitgame") || normalized.contains("quit"))) {
			return false;
		}
		quitGameDismissals++;
		logWarning("Quit Game dialog detected; tapping Cancel"
				+ "; dismissal=" + quitGameDismissals + "/" + MAX_QUIT_GAME_DISMISSALS);
		tapInside(CommonGameAreas.QUIT_GAME_CANCEL_TAP_AREA);
		sleepTask(500);
		return true;
	}

	private boolean isReconnectVisible() {
		RawImageData frame = captureStartupFrame("shared-login reconnect inspection");
		ImageSearchResultData reconnect = emuManager.locatePattern(
				EMULATOR_NUMBER, frame, TemplatesEnum.GAME_HOME_RECONNECT, STARTUP_PATTERN_THRESHOLD);
		return reconnect != null && reconnect.isFound();
	}

	private boolean tapReconnectButton() {
		RawImageData frame = captureStartupFrame("shared-login reconnect tap");
		ImageSearchResultData reconnect = emuManager.locatePattern(
				EMULATOR_NUMBER, frame, TemplatesEnum.GAME_HOME_RECONNECT, STARTUP_PATTERN_THRESHOLD);
		if (reconnect == null || !reconnect.isFound()) {
			return false;
		}
		TapInteractionService.forController(emuManager, EMULATOR_NUMBER).tapInside(reconnect);
		return true;
	}

	private boolean awaitSharedLoginBarrier(SharedLoginHandshakeSession session, boolean switcher) {
		try {
			return session.awaitCommitBarrier(profile.getId(), switcher);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/**
	 * Handles successful initialization.
	 * 
	 * <p>
	 * Reads the stamina badge under the chief avatar and stores it
	 * in the StaminaService for use by other tasks.
	 * 
	 * <p>
	 * After this method completes, the task ends without rescheduling
	 * (recurring=false is already set at the start of execute()).
	 */
	private void handleInitializationSuccess() {
		logInfo("Initialization successful. Reading stamina from the city/world badge.");
		staminaHelper.updateStaminaFromHud();
		logInfo("Initialization task completed successfully.");
		notifyInitializeSucceeded();
	}

	/** Operator ping so mid-day recovery can proceed profile-by-profile. */
	private void notifyInitializeSucceeded() {
		try {
			String name = profile.getName();
			if (name == null || name.isBlank()) {
				name = "id " + profile.getId();
			}
			String safeName = name.replace("_", " ").replace("*", "").replace("`", "").replace("[", "");
			TelegramBotService.getInstance().notifyAllowedChat("✅ " + safeName + " initialized");
		} catch (Exception e) {
			logDebug("Telegram initialize notify skipped: " + e.getMessage());
		}
	}

	/**
	 * Specifies that this task can start from any screen location.
	 * Since this is the first task, the screen state is unknown.
	 * 
	 * @return LaunchPoint.ANY
	 */
	@Override
	protected LaunchPoint getRequiredStartLocation() {
		return LaunchPoint.ANY;
	}

	/**
	 * Indicates that this task does not provide daily mission progress.
	 * 
	 * @return false
	 */
	@Override
	public boolean provideDailyMissionProgress() {
		return false;
	}
}
