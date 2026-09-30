package dev.frostguard.engine.schedule;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.service.ConfigService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Random;

public final class DailyIdlePausePolicy {
   public static final int DEFAULT_START_HOUR_UTC = 0;
   public static final int DEFAULT_START_MINUTE_UTC = 30;
   public static final int DEFAULT_DURATION_HOURS = 7;
   public static final int MIN_START_HOUR_UTC = 0;
   public static final int MAX_START_HOUR_UTC = 23;
   public static final int MIN_START_MINUTE_UTC = 0;
   public static final int MAX_START_MINUTE_UTC = 59;
   public static final int MIN_DURATION_HOURS = 1;
   public static final int MAX_DURATION_HOURS = 8;
   public static final int START_JITTER_MINUTES = 20;
   public static final int DURATION_JITTER_MINUTES = 20;
   public static final int RELEASE_BUFFER_SECONDS = 5;
   private static final String KEY_START_HOUR = "DAILY_IDLE_PAUSE_START_HOUR_INT";
   private static final String KEY_START_MINUTE = "DAILY_IDLE_PAUSE_START_MINUTE_INT";
   private static final String KEY_DURATION_HOURS = "DAILY_IDLE_PAUSE_DURATION_HOURS_INT";
   private static final DateTimeFormatter LOCAL_RELEASE = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");

   private DailyIdlePausePolicy() {
   }

   public static boolean isActive(AccountDescriptor profile) {
      return evaluate(profile, Clock.systemUTC()).blocked();
   }

   public static DailyIdlePausePolicy.Decision evaluate(AccountDescriptor profile) {
      return evaluate(profile, Clock.systemUTC());
   }

   public static DailyIdlePausePolicy.Decision evaluate(AccountDescriptor profile, Clock clock) {
      return evaluate(profile, isGloballyEnabled(), clock);
   }

   static DailyIdlePausePolicy.Decision evaluate(AccountDescriptor profile, boolean enabled, Clock clock) {
      return evaluate(profile, enabled, clock, timingFromConfig());
   }

   static DailyIdlePausePolicy.Decision evaluate(AccountDescriptor profile, boolean enabled, Clock clock, DailyIdlePausePolicy.Timing timing) {
      if (enabled && profile != null && profile.getId() != null && clock != null) {
         DailyIdlePausePolicy.Timing safe = timing == null ? DailyIdlePausePolicy.Timing.defaults() : timing.sanitized();
         ZonedDateTime nowUtc = ZonedDateTime.now(clock).withZoneSameInstant(ZoneOffset.UTC);
         LocalDate today = nowUtc.toLocalDate();
         Instant now = nowUtc.toInstant();
         DailyIdlePausePolicy.IdleWindow todayWindow = windowFor(profile.getId(), today, safe);
         if (contains(todayWindow, now)) {
            return DailyIdlePausePolicy.Decision.blocked(todayWindow.end().plusSeconds(5L));
         } else {
            DailyIdlePausePolicy.IdleWindow yesterdayWindow = windowFor(profile.getId(), today.minusDays(1L), safe);
            return contains(yesterdayWindow, now)
               ? DailyIdlePausePolicy.Decision.blocked(yesterdayWindow.end().plusSeconds(5L))
               : DailyIdlePausePolicy.Decision.allowed();
         }
      } else {
         return DailyIdlePausePolicy.Decision.allowed();
      }
   }

   public static List<DailyIdlePausePolicy.ProfileIdleStatus> statusFor(Collection<AccountDescriptor> profiles) {
      return statusFor(profiles, isGloballyEnabled(), Clock.systemUTC(), ZoneId.systemDefault());
   }

   static List<DailyIdlePausePolicy.ProfileIdleStatus> statusFor(Collection<AccountDescriptor> profiles, boolean enabled, Clock clock, ZoneId displayZone) {
      List<DailyIdlePausePolicy.ProfileIdleStatus> lines = new ArrayList<>();
      if (profiles != null && clock != null) {
         ZoneId zone = displayZone == null ? ZoneId.systemDefault() : displayZone;
         DailyIdlePausePolicy.Timing timing = timingFromConfig();

         for (AccountDescriptor profile : profiles) {
            if (profile != null && profile.getId() != null) {
               String name = profile.getName() != null && !profile.getName().isBlank() ? profile.getName() : "Profile " + profile.getId();
               if (!enabled) {
                  lines.add(new DailyIdlePausePolicy.ProfileIdleStatus(profile.getId(), name, false, null, "Daily idle pause is off"));
               } else {
                  DailyIdlePausePolicy.Decision decision = evaluate(profile, true, clock);
                  if (!decision.blocked()) {
                     LocalDate today = ZonedDateTime.now(clock).withZoneSameInstant(ZoneOffset.UTC).toLocalDate();
                     DailyIdlePausePolicy.IdleWindow window = windowFor(profile.getId(), today, timing);
                     Instant now = ZonedDateTime.now(clock).toInstant();
                     String detail;
                     if (now.isBefore(window.start())) {
                        detail = "Starts at " + LOCAL_RELEASE.format(window.start().atZone(zone));
                     } else {
                        detail = "Window already finished for today";
                     }

                     lines.add(new DailyIdlePausePolicy.ProfileIdleStatus(profile.getId(), name, false, null, detail));
                  } else {
                     String until = LOCAL_RELEASE.format(decision.releaseAt().atZone(zone));
                     lines.add(new DailyIdlePausePolicy.ProfileIdleStatus(profile.getId(), name, true, decision.releaseAt(), "Paused until " + until));
                  }
               }
            }
         }

         return lines;
      } else {
         return lines;
      }
   }

   public static DailyIdlePausePolicy.IdleWindow windowFor(long profileId, LocalDate utcDate) {
      return windowFor(profileId, utcDate, timingFromConfig());
   }

   public static DailyIdlePausePolicy.IdleWindow windowFor(long profileId, LocalDate utcDate, DailyIdlePausePolicy.Timing timing) {
      DailyIdlePausePolicy.Timing safe = timing == null ? DailyIdlePausePolicy.Timing.defaults() : timing.sanitized();
      Random rng = new Random(seedFor(profileId, utcDate));
      int startJitterMinutes = rng.nextInt(41) - 20;
      int durationJitterMinutes = rng.nextInt(41) - 20;
      int durationMinutes = Math.max(1, safe.durationHours() * 60 + durationJitterMinutes);
      Instant start = utcDate.atTime(LocalTime.of(safe.startHourUtc(), safe.startMinuteUtc()))
         .toInstant(ZoneOffset.UTC)
         .plus(Duration.ofMinutes(startJitterMinutes));
      Instant end = start.plus(Duration.ofMinutes(durationMinutes));
      return new DailyIdlePausePolicy.IdleWindow(start, end, startJitterMinutes, durationMinutes);
   }

   static long seedFor(long profileId, LocalDate utcDate) {
      return profileId * 31L + utcDate.toEpochDay();
   }

   public static boolean isGloballyEnabled() {
      Map<String, String> cfg = ConfigService.obtain().loadGlobalSettings();
      if (cfg == null) {
         return Boolean.parseBoolean(ConfigurationKeyEnum.DAILY_IDLE_PAUSE_BOOL.getDefaultValue());
      } else {
         String raw = cfg.getOrDefault(ConfigurationKeyEnum.DAILY_IDLE_PAUSE_BOOL.name(), ConfigurationKeyEnum.DAILY_IDLE_PAUSE_BOOL.getDefaultValue());
         return Boolean.parseBoolean(raw);
      }
   }

   public static DailyIdlePausePolicy.Timing timingFromConfig() {
      return new DailyIdlePausePolicy.Timing(
            readClampedInt("DAILY_IDLE_PAUSE_START_HOUR_INT", 0, 0, 23),
            readClampedInt("DAILY_IDLE_PAUSE_START_MINUTE_INT", 30, 0, 59),
            readClampedInt("DAILY_IDLE_PAUSE_DURATION_HOURS_INT", 7, 1, 8)
         )
         .sanitized();
   }

   private static boolean contains(DailyIdlePausePolicy.IdleWindow window, Instant now) {
      return !now.isBefore(window.start()) && now.isBefore(window.end());
   }

   private static int readClampedInt(String keyName, int fallback, int minInclusive, int maxInclusive) {
      int raw = fallback;

      try {
         Map<String, String> cfg = ConfigService.obtain().loadGlobalSettings();
         if (cfg != null) {
            String value = cfg.getOrDefault(keyName, String.valueOf(fallback));
            raw = Integer.parseInt(value.trim());
         }
      } catch (Error | RuntimeException var7) {
         raw = fallback;
      }

      return Math.max(minInclusive, Math.min(maxInclusive, raw));
   }

   private static int clamp(int value, int min, int max) {
      return Math.max(min, Math.min(max, value));
   }

   public record Decision(boolean blocked, Instant releaseAt) {
      static DailyIdlePausePolicy.Decision allowed() {
         return new DailyIdlePausePolicy.Decision(false, null);
      }

      static DailyIdlePausePolicy.Decision blocked(Instant releaseAt) {
         return new DailyIdlePausePolicy.Decision(true, releaseAt);
      }
   }

   public record IdleWindow(Instant start, Instant end, int startJitterMinutes, int durationMinutes) {
   }

   public record ProfileIdleStatus(long profileId, String profileName, boolean paused, Instant releaseAt, String detail) {
   }

   public record Timing(int startHourUtc, int startMinuteUtc, int durationHours) {
      public static DailyIdlePausePolicy.Timing defaults() {
         return new DailyIdlePausePolicy.Timing(0, 30, 7);
      }

      public DailyIdlePausePolicy.Timing sanitized() {
         return new DailyIdlePausePolicy.Timing(
            DailyIdlePausePolicy.clamp(this.startHourUtc, 0, 23),
            DailyIdlePausePolicy.clamp(this.startMinuteUtc, 0, 59),
            DailyIdlePausePolicy.clamp(this.durationHours, 1, 8)
         );
      }
   }
}
