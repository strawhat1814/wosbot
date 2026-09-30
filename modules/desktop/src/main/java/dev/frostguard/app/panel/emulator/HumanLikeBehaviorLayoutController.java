package dev.frostguard.app.panel.emulator;

import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.app.shared.SettingValidator;
import dev.frostguard.app.shared.SettingValidators;
import dev.frostguard.app.shared.ValidatedTextFieldBinding;
import dev.frostguard.engine.schedule.DailyIdlePausePolicy;
import dev.frostguard.engine.service.ConfigService;
import dev.frostguard.engine.service.ProfileService;
import dev.frostguard.engine.service.ScheduleService;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HumanLikeBehaviorLayoutController {
   private static final Logger logger = LoggerFactory.getLogger(HumanLikeBehaviorLayoutController.class);
   private static final String KEY_POINT_JITTER = "TAP_POINT_JITTER_RADIUS_INT";
   private static final String KEY_DELAY_PERCENT = "TAP_DELAY_JITTER_PERCENT_INT";
   private static final String KEY_DELAY_CAP = "TAP_DELAY_JITTER_CAP_MS_INT";
   private static final String KEY_IDLE_START_HOUR = "DAILY_IDLE_PAUSE_START_HOUR_INT";
   private static final String KEY_IDLE_START_MINUTE = "DAILY_IDLE_PAUSE_START_MINUTE_INT";
   private static final String KEY_IDLE_DURATION_HOURS = "DAILY_IDLE_PAUSE_DURATION_HOURS_INT";
   @FXML
   private CheckBox checkboxDailyIdlePause;
   @FXML
   private Button buttonCancelDailyIdle;
   @FXML
   private Button buttonRefreshIdleStatus;
   @FXML
   private Label labelIdleStatus;
   @FXML
   private TextField textfieldIdleStartHour;
   @FXML
   private Label labelIdleStartHourError;
   @FXML
   private TextField textfieldIdleStartMinute;
   @FXML
   private Label labelIdleStartMinuteError;
   @FXML
   private TextField textfieldIdleDurationHours;
   @FXML
   private Label labelIdleDurationHoursError;
   @FXML
   private TextField textfieldPointJitterRadius;
   @FXML
   private Label labelPointJitterRadiusError;
   @FXML
   private TextField textfieldDelayJitterPercent;
   @FXML
   private Label labelDelayJitterPercentError;
   @FXML
   private TextField textfieldDelayJitterCapMs;
   @FXML
   private Label labelDelayJitterCapMsError;

   @FXML
   private void initialize() {
      Map<String, String> cfg = ConfigService.obtain().loadGlobalSettings();
      if (cfg == null) {
         cfg = Map.of();
      }

      boolean dailyIdleOn = Boolean.parseBoolean(cfg.getOrDefault("DAILY_IDLE_PAUSE_BOOL", "true"));
      this.checkboxDailyIdlePause.setSelected(dailyIdleOn);
      this.checkboxDailyIdlePause.selectedProperty().addListener((obs, prev, active) -> {
         ScheduleService.obtain().persistEmulatorPath("DAILY_IDLE_PAUSE_BOOL", String.valueOf(active));
         this.refreshIdleStatus();
      });
      this.bindIdleTimingInteger(
         this.textfieldIdleStartHour, this.labelIdleStartHourError, "DAILY_IDLE_PAUSE_START_HOUR_INT", String.valueOf(0), "Idle start hour (UTC)", 0, 23, cfg
      );
      this.bindIdleTimingInteger(
         this.textfieldIdleStartMinute,
         this.labelIdleStartMinuteError,
         "DAILY_IDLE_PAUSE_START_MINUTE_INT",
         String.valueOf(30),
         "Idle start minute (UTC)",
         0,
         59,
         cfg
      );
      this.bindIdleTimingInteger(
         this.textfieldIdleDurationHours,
         this.labelIdleDurationHoursError,
         "DAILY_IDLE_PAUSE_DURATION_HOURS_INT",
         String.valueOf(7),
         "Idle duration hours",
         1,
         8,
         cfg
      );
      this.bindRangedInteger(
         this.textfieldPointJitterRadius, this.labelPointJitterRadiusError, "TAP_POINT_JITTER_RADIUS_INT", String.valueOf(3), "Point tap jitter", 0, 20, cfg
      );
      this.bindRangedInteger(
         this.textfieldDelayJitterPercent,
         this.labelDelayJitterPercentError,
         "TAP_DELAY_JITTER_PERCENT_INT",
         String.valueOf(15),
         "Delay jitter percent",
         0,
         50,
         cfg
      );
      this.bindRangedInteger(
         this.textfieldDelayJitterCapMs, this.labelDelayJitterCapMsError, "TAP_DELAY_JITTER_CAP_MS_INT", "120", "Delay jitter cap", 0, 1000, cfg
      );
      this.refreshIdleStatus();
   }

   @FXML
   private void handleCancelDailyIdle() {
      this.checkboxDailyIdlePause.setSelected(false);
      ScheduleService.obtain().persistEmulatorPath("DAILY_IDLE_PAUSE_BOOL", "false");
      this.refreshIdleStatus();
   }

   @FXML
   private void handleRefreshIdleStatus() {
      this.refreshIdleStatus();
   }

   private void refreshIdleStatus() {
      try {
         List<AccountDescriptor> profiles = ProfileService.obtain().fetchAllAccounts();
         List<DailyIdlePausePolicy.ProfileIdleStatus> statuses = DailyIdlePausePolicy.statusFor(profiles);
         if (statuses.isEmpty()) {
            this.labelIdleStatus.setText("No profiles loaded.");
            return;
         }

         String body = statuses.stream().map(status -> status.profileName() + ": " + status.detail()).collect(Collectors.joining("\n"));
         boolean anyPaused = statuses.stream().anyMatch(DailyIdlePausePolicy.ProfileIdleStatus::paused);
         String header = DailyIdlePausePolicy.isGloballyEnabled()
            ? (anyPaused ? "Daily idle is active for at least one profile." : "Daily idle is enabled; no profile is inside its window right now.")
            : "Daily idle pause is turned off.";
         this.labelIdleStatus.setText(header + "\n" + body);
      } catch (Error | RuntimeException var6) {
         logger.warn("Could not refresh daily idle status: {}", var6.toString());
         this.labelIdleStatus.setText("Could not load idle status: " + var6.getMessage());
      }
   }

   private ValidatedTextFieldBinding<Integer> bindIdleTimingInteger(
      TextField field, Label errorLabel, String keyName, String defaultValue, String label, int min, int max, Map<String, String> cfg
   ) {
      if (field.getTextFormatter() == null) {
         field.setTextFormatter(new TextFormatter<String>(change -> change.getControlNewText().matches("\\d*") ? change : null));
      }

      ValidatedTextFieldBinding<Integer> binding = new ValidatedTextFieldBinding(
         field, errorLabel, SettingValidators.rangedInteger(label, min, max), String::valueOf, value -> {
            ScheduleService.obtain().persistEmulatorPath(keyName, String.valueOf(value));
            this.refreshIdleStatus();
         }
      );
      String persisted = cfg.getOrDefault(keyName, defaultValue);
      binding.loadPersisted(
         persisted,
         defaultValue,
         reason -> logger.warn("Invalid persisted setting; key={}, value={}, fallback={}, reason={}", new Object[]{keyName, persisted, defaultValue, reason})
      );
      return binding;
   }

   private ValidatedTextFieldBinding<Integer> bindRangedInteger(
      TextField field, Label errorLabel, String keyName, String defaultValue, String label, int min, int max, Map<String, String> cfg
   ) {
      return this.bindInteger(field, errorLabel, keyName, defaultValue, label, SettingValidators.rangedInteger(label, min, max), cfg);
   }

   private ValidatedTextFieldBinding<Integer> bindInteger(
      TextField field, Label errorLabel, String keyName, String defaultValue, String label, SettingValidator<Integer> validator, Map<String, String> cfg
   ) {
      if (field.getTextFormatter() == null) {
         field.setTextFormatter(new TextFormatter<String>(change -> change.getControlNewText().matches("\\d*") ? change : null));
      }

      ValidatedTextFieldBinding<Integer> binding = new ValidatedTextFieldBinding(
         field, errorLabel, validator, String::valueOf, value -> ScheduleService.obtain().persistEmulatorPath(keyName, String.valueOf(value))
      );
      String persisted = cfg.getOrDefault(keyName, defaultValue);
      binding.loadPersisted(
         persisted,
         defaultValue,
         reason -> logger.warn("Invalid persisted setting; key={}, value={}, fallback={}, reason={}", new Object[]{keyName, persisted, defaultValue, reason})
      );
      return binding;
   }
}
