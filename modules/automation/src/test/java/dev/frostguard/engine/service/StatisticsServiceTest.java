package dev.frostguard.engine.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.JobMetrics;
import dev.frostguard.api.domain.ProfilesData;

class StatisticsServiceTest {

    @AfterEach
    void restoreSingleton() {
        StatisticsService.replaceForTests(new StatisticsService(Clock.systemUTC()));
    }

    @Test
    void rollsDailyCountersIntoYesterdayAtUtcMidnight() {
        Clock dayOne = Clock.fixed(Instant.parse("2026-09-22T15:00:00Z"), ZoneOffset.UTC);
        StatisticsService service = new StatisticsService(dayOne);
        ProfilesData data = new ProfilesData();
        data.setDailyUtcDate("2026-09-22");
        data.getDailyCounters().put("Gather Marches Deployed", 4);

        assertFalse(service.rollDailyBucketsIfNeeded(data));

        StatisticsService nextDay = new StatisticsService(
                Clock.fixed(Instant.parse("2026-09-23T00:00:01Z"), ZoneOffset.UTC));
        assertTrue(nextDay.rollDailyBucketsIfNeeded(data));
        assertEquals("2026-09-23", data.getDailyUtcDate());
        assertTrue(data.getDailyCounters().isEmpty());
        assertEquals(4, data.getYesterdayCounters().get("Gather Marches Deployed"));
    }

    @Test
    void allTimeStatsListEveryCounterWithoutTruncation() {
        StatisticsService service = new StatisticsService(
                Clock.fixed(Instant.parse("2026-09-23T12:00:00Z"), ZoneOffset.UTC));
        ProfilesData data = new ProfilesData();
        data.setDailyUtcDate("2026-09-23");
        for (int i = 1; i <= 12; i++) {
            data.getCustomCounters().put("Counter " + i, i);
        }
        data.getCustomCounters().put("Gather Marches Deployed", 30);
        JobMetrics noisy = new JobMetrics("Intel");
        noisy.setNumberOfRuns(2);
        noisy.setTotalOcrFailures(4);
        noisy.setTotalTemplateSearchFailures(2);
        data.getTaskStatistics().put("Intel", noisy);

        String body = service.buildTelegramAllTimeStatsBody(List.of(
                new StatisticsService.ProfileStatsSlice("Kevin", data, 2)));

        assertTrue(body.contains("Kevin"));
        assertTrue(body.contains("All-time") || body.contains("All\\-time"));
        assertTrue(body.contains("Gather Marches Deployed"));
        assertTrue(body.contains("Counter 12"));
        assertFalse(body.contains("more"));
        assertTrue(body.contains("Health"));
    }

    @Test
    void dailyStatsListEveryTodayCounterWithoutTruncation() {
        StatisticsService service = new StatisticsService(
                Clock.fixed(Instant.parse("2026-09-23T12:00:00Z"), ZoneOffset.UTC));
        ProfilesData data = new ProfilesData();
        data.setDailyUtcDate("2026-09-23");
        for (int i = 1; i <= 12; i++) {
            data.getDailyCounters().put("Daily " + i, i);
        }

        String body = service.buildTelegramDailyStatsBody(List.of(
                new StatisticsService.ProfileStatsSlice("Kevin", data, 0)));

        assertTrue(body.contains("Daily statistics") || body.contains("Daily"));
        assertTrue(body.contains("Daily 12"));
        assertFalse(body.contains("more"));
    }

    @Test
    void detectAnomaliesFlagsGatherWithoutMarches() {
        Map<String, JobMetrics> tasks = new HashMap<>();
        JobMetrics gather = new JobMetrics("Gather Resources");
        gather.setNumberOfRuns(3);
        tasks.put(gather.getTaskName(), gather);

        List<String> flags = StatisticsService.detectAnomalies(tasks, Map.of());
        assertEquals(1, flags.size());
        assertTrue(flags.get(0).contains("Gather"));
    }

    @Test
    void formatDurationUsesHoursWhenLong() {
        assertEquals("2.0h", StatisticsService.formatDuration(7_200_000L));
        assertEquals("5m", StatisticsService.formatDuration(300_000L));
    }
}
