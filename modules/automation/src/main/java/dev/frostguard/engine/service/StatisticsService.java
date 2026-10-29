package dev.frostguard.engine.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.JobMetrics;
import dev.frostguard.api.domain.ProfilesData;
import dev.frostguard.api.stats.StatisticsCategories;

/**
 * Maintains per-profile counters and task execution aggregates.
 * Named counters write to both lifetime and Today (UTC) buckets;
 * Today rolls into Yesterday at UTC midnight.
 * <p>
 * Telegram reads apply the UTC roll in memory only and do not flush to the DB,
 * so {@code /allstats} / {@code /dailystats} stay off the Hikari hot path.
 */
public class StatisticsService {

	private static final Logger LOG = LoggerFactory.getLogger(StatisticsService.class);
	private static final DateTimeFormatter RUN_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static final DateTimeFormatter UTC_DAY = DateTimeFormatter.ISO_LOCAL_DATE;
	private static final int TELEGRAM_HEALTH_TASKS = 5;
	private static final int TELEGRAM_MAX_MESSAGE_CHARS = 3900;

	private static volatile StatisticsService holder;

	private final ObjectMapper mapper = new ObjectMapper();
	private final Clock clock;

	private StatisticsService() {
		this(Clock.systemUTC());
	}

	StatisticsService(Clock clock) {
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	public static StatisticsService obtain() {
		StatisticsService service = holder;
		if (service != null) {
			return service;
		}
		synchronized (StatisticsService.class) {
			if (holder == null) {
				holder = new StatisticsService();
			}
			return holder;
		}
	}

	/** Test-only: replace the singleton so UTC roll can be exercised with a fixed clock. */
	static void replaceForTests(StatisticsService service) {
		holder = service;
	}

	public synchronized void addToCounter(AccountDescriptor profile, String counterName, int delta) {
		if (profile == null || counterName == null || delta == 0) {
			return;
		}
		mutate(profile, data -> {
			rollDailyBucketsIfNeeded(data);
			data.getCustomCounters().merge(counterName, delta, Integer::sum);
			data.getDailyCounters().merge(counterName, delta, Integer::sum);
		});
	}

	public synchronized void logJobExecution(AccountDescriptor profile, String jobName,
			long durationMs, int ocrMisses, int templateMisses) {
		if (profile == null || jobName == null) {
			return;
		}
		mutate(profile, data -> {
			rollDailyBucketsIfNeeded(data);
			JobMetrics metrics = data.getTaskStatistics().computeIfAbsent(jobName, JobMetrics::new);
			metrics.setNumberOfRuns(metrics.getNumberOfRuns() + 1);
			metrics.setTotalExecutionTimeMs(metrics.getTotalExecutionTimeMs() + durationMs);
			metrics.setTotalOcrFailures(metrics.getTotalOcrFailures() + ocrMisses);
			metrics.setTotalTemplateSearchFailures(metrics.getTotalTemplateSearchFailures() + templateMisses);
			metrics.setLastRunTime(LocalDateTime.now(clock).format(RUN_STAMP));
		});
	}

	public synchronized void clearMetrics(AccountDescriptor profile) {
		if (profile == null) {
			return;
		}
		store(profile, new ProfilesData());
		LOG.info("Metrics reset for profile id={}", profile.getId());
	}

	/**
	 * Loads metrics and applies a UTC day roll in memory when needed.
	 * Does not persist on read — rolls are flushed on the next counter/job write.
	 */
	public synchronized ProfilesData loadMetrics(AccountDescriptor profile) {
		if (profile == null) {
			return new ProfilesData();
		}
		ProfilesData data = readRaw(profile);
		rollDailyBucketsIfNeeded(data);
		return data;
	}

	public String currentUtcDay() {
		return LocalDate.now(clock.withZone(ZoneOffset.UTC)).format(UTC_DAY);
	}

	/**
	 * Full all-time counters per profile (no truncation). Prefer {@code /stats}.
	 */
	public String buildTelegramAllTimeStatsBody(List<ProfileStatsSlice> slices) {
		if (slices == null || slices.isEmpty()) {
			return "📚 *All\\-time statistics*\n\n_No enabled profiles\\._";
		}
		StringBuilder sb = new StringBuilder();
		sb.append("📚 *All\\-time statistics*\n");
		sb.append("_UTC day ").append(escapeMdV2(currentUtcDay())).append("_\n");
		sb.append("━━━━━━━━━━━━━━━━━━━━━━\n\n");

		for (int i = 0; i < slices.size(); i++) {
			appendAllTimeProfileSection(sb, slices.get(i));
			if (i < slices.size() - 1) {
				sb.append("\n━━━━━━━━━━━━━━━━━━━━━━\n\n");
			}
		}
		return sb.toString();
	}

	/**
	 * Full Today (UTC) counters per profile (no truncation). Prefer {@code /dailystats}.
	 */
	public String buildTelegramDailyStatsBody(List<ProfileStatsSlice> slices) {
		if (slices == null || slices.isEmpty()) {
			return "📊 *Daily statistics*\n\n_No enabled profiles\\._";
		}
		StringBuilder sb = new StringBuilder();
		sb.append("📊 *Daily statistics*\n");
		sb.append("_UTC day ").append(escapeMdV2(currentUtcDay())).append("_\n");
		sb.append("━━━━━━━━━━━━━━━━━━━━━━\n\n");

		for (int i = 0; i < slices.size(); i++) {
			ProfileStatsSlice slice = slices.get(i);
			Map<String, Integer> today = slice.data().getDailyCounters();
			sb.append("✨ *").append(escapeMdV2(slice.profileName())).append("*\n");
			int todayActions = sum(today);
			sb.append("Actions today `").append(todayActions).append("`\n");
			if (slice.overdueTasks() > 0) {
				sb.append("⏰ Overdue tasks: `").append(slice.overdueTasks()).append("`\n");
			}
			if (today != null && !today.isEmpty()) {
				sb.append('\n');
				appendGroupedCounters(sb, today, Integer.MAX_VALUE);
			} else {
				sb.append("\n_No activity recorded today\\._\n");
			}
			if (i < slices.size() - 1) {
				sb.append("\n━━━━━━━━━━━━━━━━━━━━━━\n\n");
			}
		}
		return sb.toString();
	}

	/** @deprecated Prefer {@link #buildTelegramAllTimeStatsBody(List)}. */
	@Deprecated
	public String buildTelegramStatsBody(List<ProfileStatsSlice> slices) {
		return buildTelegramAllTimeStatsBody(slices);
	}

	/**
	 * Splits a MarkdownV2 body into Telegram-safe chunks (under ~4096 chars),
	 * preferring breaks at profile separators.
	 */
	public List<String> splitTelegramBodies(String body) {
		if (body == null || body.isBlank()) {
			return List.of();
		}
		if (body.length() <= TELEGRAM_MAX_MESSAGE_CHARS) {
			return List.of(body);
		}
		List<String> chunks = new ArrayList<>();
		String separator = "\n━━━━━━━━━━━━━━━━━━━━━━\n\n";
		String[] parts = body.split("(?=" + java.util.regex.Pattern.quote(separator) + ")|(?<="
				+ java.util.regex.Pattern.quote(separator) + ")");
		StringBuilder current = new StringBuilder();
		for (String part : parts) {
			if (part.isEmpty()) {
				continue;
			}
			if (current.length() + part.length() > TELEGRAM_MAX_MESSAGE_CHARS && current.length() > 0) {
				chunks.add(current.toString().trim());
				current.setLength(0);
			}
			if (part.length() > TELEGRAM_MAX_MESSAGE_CHARS) {
				if (current.length() > 0) {
					chunks.add(current.toString().trim());
					current.setLength(0);
				}
				int offset = 0;
				while (offset < part.length()) {
					int end = Math.min(offset + TELEGRAM_MAX_MESSAGE_CHARS, part.length());
					chunks.add(part.substring(offset, end));
					offset = end;
				}
			} else {
				current.append(part);
			}
		}
		if (current.length() > 0) {
			chunks.add(current.toString().trim());
		}
		return chunks.isEmpty() ? List.of(body) : chunks;
	}

	public record ProfileStatsSlice(String profileName, ProfilesData data, int overdueTasks) {}

	boolean rollDailyBucketsIfNeeded(ProfilesData data) {
		String today = currentUtcDay();
		String stamped = data.getDailyUtcDate();
		if (today.equals(stamped)) {
			ensureMaps(data);
			return false;
		}
		ensureMaps(data);
		if (stamped != null && !stamped.isBlank()) {
			data.setYesterdayCounters(new HashMap<>(data.getDailyCounters()));
		} else {
			data.setYesterdayCounters(new HashMap<>());
		}
		data.setDailyCounters(new HashMap<>());
		data.setDailyUtcDate(today);
		return true;
	}

	private ProfilesData readRaw(AccountDescriptor profile) {
		String raw = profile.getConfig(ConfigurationKeyEnum.STATISTICS_JSON_STRING, String.class);
		if (raw == null || raw.isBlank() || "{}".equals(raw.trim())) {
			return new ProfilesData();
		}
		try {
			ProfilesData data = mapper.readValue(raw, ProfilesData.class);
			ensureMaps(data);
			return data;
		} catch (JsonProcessingException ex) {
			LOG.error("Metrics payload ignored for profile id={}: {}", profile.getId(), ex.getMessage());
			return new ProfilesData();
		}
	}

	private void mutate(AccountDescriptor profile, Consumer<ProfilesData> change) {
		ProfilesData data = readRaw(profile);
		change.accept(data);
		store(profile, data);
	}

	private void store(AccountDescriptor profile, ProfilesData data) {
		try {
			String payload = mapper.writeValueAsString(data);
			ConfigService.obtain().writeAccountSetting(profile, ConfigurationKeyEnum.STATISTICS_JSON_STRING, payload);
		} catch (JsonProcessingException ex) {
			LOG.error("Metrics payload could not be saved for profile id={}: {}", profile.getId(), ex.getMessage());
		}
	}

	private static void ensureMaps(ProfilesData data) {
		if (data.getNamedCounters() == null) {
			data.setNamedCounters(new HashMap<>());
		}
		if (data.getDailyCounters() == null) {
			data.setDailyCounters(new HashMap<>());
		}
		if (data.getYesterdayCounters() == null) {
			data.setYesterdayCounters(new HashMap<>());
		}
		if (data.getRoutineStats() == null) {
			data.setRoutineStats(new HashMap<>());
		}
	}

	private void appendAllTimeProfileSection(StringBuilder sb, ProfileStatsSlice slice) {
		ProfilesData stats = slice.data();
		sb.append("✨ *").append(escapeMdV2(slice.profileName())).append("*\n");

		int runs = totalRuns(stats);
		long timeMs = totalTimeMs(stats);
		int lifetimeActions = sum(stats.getCustomCounters());
		sb.append("Runs `").append(runs).append("` · Time `")
				.append(escapeMdV2(formatDuration(timeMs))).append("`\n");
		sb.append("All\\-time actions `").append(lifetimeActions).append("`\n");
		if (slice.overdueTasks() > 0) {
			sb.append("⏰ Overdue tasks: `").append(slice.overdueTasks()).append("`\n");
		}

		appendHealth(sb, stats);

		Map<String, Integer> lifetime = stats.getCustomCounters();
		if (lifetime != null && !lifetime.isEmpty()) {
			sb.append("\n📚 *Counters:*\n");
			appendGroupedCounters(sb, lifetime, Integer.MAX_VALUE);
		} else {
			sb.append("\n_No all\\-time counters yet\\._\n");
		}
	}

	private void appendHealth(StringBuilder sb, ProfilesData stats) {
		List<JobMetrics> noisy = stats.getTaskStatistics().values().stream()
				.filter(m -> m.getNumberOfRuns() > 0)
				.filter(m -> m.getAverageOcrFailures() >= 1.0 || m.getAverageTemplateFailures() >= 1.0)
				.sorted(Comparator
						.comparingDouble((JobMetrics m) -> m.getAverageOcrFailures() + m.getAverageTemplateFailures())
						.reversed())
				.limit(TELEGRAM_HEALTH_TASKS)
				.toList();
		if (noisy.isEmpty()) {
			return;
		}
		sb.append("\n🩺 *Health:*\n");
		for (JobMetrics m : noisy) {
			sb.append("  ▫️ *").append(escapeMdV2(m.getTaskName())).append("*: OCR `")
					.append(String.format(Locale.ROOT, "%.1f", m.getAverageOcrFailures()))
					.append("` Img `")
					.append(String.format(Locale.ROOT, "%.1f", m.getAverageTemplateFailures()))
					.append("`\n");
		}
	}

	private void appendGroupedCounters(StringBuilder sb, Map<String, Integer> counters, int maxTotal) {
		Map<String, Map<String, Integer>> grouped = groupCounters(counters);
		int emitted = 0;
		for (String category : StatisticsCategories.CATEGORY_ORDER) {
			Map<String, Integer> inCat = grouped.get(category);
			if (inCat == null || inCat.isEmpty()) {
				continue;
			}
			sb.append('_').append(escapeMdV2(category)).append("_\n");
			for (Map.Entry<String, Integer> entry : sortedEntries(inCat)) {
				if (emitted >= maxTotal) {
					int remaining = countRemaining(grouped, category, entry.getKey());
					if (remaining > 0) {
						sb.append("  _\\+").append(remaining).append(" more_\n");
					}
					return;
				}
				sb.append("  ▫️ *").append(escapeMdV2(entry.getKey())).append("*: `")
						.append(entry.getValue()).append("`\n");
				emitted++;
			}
		}
	}

	private static Map<String, Map<String, Integer>> groupCounters(Map<String, Integer> counters) {
		Map<String, Map<String, Integer>> grouped = new LinkedHashMap<>();
		for (String cat : StatisticsCategories.CATEGORY_ORDER) {
			grouped.put(cat, new LinkedHashMap<>());
		}
		for (Map.Entry<String, Integer> entry : counters.entrySet()) {
			String category = StatisticsCategories.categoryFor(entry.getKey());
			grouped.computeIfAbsent(category, k -> new LinkedHashMap<>()).put(entry.getKey(), entry.getValue());
		}
		return grouped;
	}

	private static List<Map.Entry<String, Integer>> sortedEntries(Map<String, Integer> counters) {
		List<Map.Entry<String, Integer>> list = new ArrayList<>(counters.entrySet());
		list.sort(Comparator
				.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
				.reversed()
				.thenComparing(Map.Entry::getKey));
		return list;
	}

	private static int countRemaining(Map<String, Map<String, Integer>> grouped, String fromCategory, String fromKey) {
		boolean counting = false;
		int remaining = 0;
		for (String category : StatisticsCategories.CATEGORY_ORDER) {
			Map<String, Integer> inCat = grouped.get(category);
			if (inCat == null) {
				continue;
			}
			for (Map.Entry<String, Integer> entry : sortedEntries(inCat)) {
				if (!counting) {
					if (category.equals(fromCategory) && entry.getKey().equals(fromKey)) {
						counting = true;
					}
					continue;
				}
				remaining++;
			}
		}
		return remaining;
	}

	static List<String> detectAnomalies(Map<String, JobMetrics> tasks, Map<String, Integer> dayCounters) {
		List<String> flags = new ArrayList<>();
		int runs = tasks == null ? 0 : tasks.values().stream().mapToInt(JobMetrics::getNumberOfRuns).sum();
		if (runs <= 0) {
			return flags;
		}
		if (counterOrZero(dayCounters, "Gather Marches Deployed") == 0
				&& tasksContain(tasks, "Gather")) {
			flags.add("Gather ran but 0 marches deployed");
		}
		if (counterOrZero(dayCounters, "Intel Beast")
				+ counterOrZero(dayCounters, "Intel Journeys")
				+ counterOrZero(dayCounters, "Intel Survivor Camps") == 0
				&& tasksContain(tasks, "Intel")) {
			flags.add("Intel ran but 0 intel actions");
		}
		return flags;
	}

	private static boolean tasksContain(Map<String, JobMetrics> tasks, String needle) {
		if (tasks == null) {
			return false;
		}
		String lower = needle.toLowerCase(Locale.ROOT);
		return tasks.keySet().stream().anyMatch(name -> name != null && name.toLowerCase(Locale.ROOT).contains(lower));
	}

	private static int counterOrZero(Map<String, Integer> counters, String name) {
		if (counters == null) {
			return 0;
		}
		return counters.getOrDefault(name, 0);
	}

	private static int sum(Map<String, Integer> counters) {
		if (counters == null || counters.isEmpty()) {
			return 0;
		}
		return counters.values().stream().mapToInt(Integer::intValue).sum();
	}

	private static int totalRuns(ProfilesData stats) {
		return stats.getTaskStatistics().values().stream().mapToInt(JobMetrics::getNumberOfRuns).sum();
	}

	private static long totalTimeMs(ProfilesData stats) {
		return stats.getTaskStatistics().values().stream().mapToLong(JobMetrics::getTotalExecutionTimeMs).sum();
	}

	static String formatDuration(long totalTimeMs) {
		double totalHours = totalTimeMs / 3_600_000.0;
		if (totalHours >= 1.0) {
			return String.format(Locale.ROOT, "%.1fh", totalHours);
		}
		return String.format(Locale.ROOT, "%.0fm", totalTimeMs / 60_000.0);
	}

	static String escapeMdV2(String text) {
		if (text == null || text.isEmpty()) {
			return "";
		}
		StringBuilder out = new StringBuilder(text.length() * 2);
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if ("_*[]()~`>#+-=|{}.!\\".indexOf(c) >= 0) {
				out.append('\\');
			}
			out.append(c);
		}
		return out.toString();
	}
}
