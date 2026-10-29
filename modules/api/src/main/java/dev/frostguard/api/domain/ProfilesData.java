package dev.frostguard.api.domain;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Aggregated performance metrics for a single managed profile.
 * Holds per-routine execution stats and arbitrary named counters.
 * Daily counters roll at UTC midnight; yesterday is preserved for digests.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProfilesData implements Serializable {

    private Map<String, JobMetrics> routineStats = new HashMap<>();
    private Map<String, Integer> namedCounters = new HashMap<>();
    private Map<String, Integer> dailyCounters = new HashMap<>();
    private Map<String, Integer> yesterdayCounters = new HashMap<>();
    /** UTC calendar day (yyyy-MM-dd) that {@link #dailyCounters} currently accumulate. */
    private String dailyUtcDate;

    public ProfilesData() {}

    public Map<String, JobMetrics> getRoutineStats() { return routineStats; }
    public void setRoutineStats(Map<String, JobMetrics> routineStats) { this.routineStats = routineStats; }

    public Map<String, Integer> getNamedCounters() { return namedCounters; }
    public void setNamedCounters(Map<String, Integer> namedCounters) { this.namedCounters = namedCounters; }

    public Map<String, Integer> getDailyCounters() { return dailyCounters; }
    public void setDailyCounters(Map<String, Integer> dailyCounters) { this.dailyCounters = dailyCounters; }

    public Map<String, Integer> getYesterdayCounters() { return yesterdayCounters; }
    public void setYesterdayCounters(Map<String, Integer> yesterdayCounters) {
        this.yesterdayCounters = yesterdayCounters;
    }

    public String getDailyUtcDate() { return dailyUtcDate; }
    public void setDailyUtcDate(String dailyUtcDate) { this.dailyUtcDate = dailyUtcDate; }

    // Legacy compatibility
    public Map<String, JobMetrics> getTaskStatistics() { return routineStats; }
    public void setTaskStatistics(Map<String, JobMetrics> ts) { this.routineStats = ts; }
    public Map<String, Integer> getCustomCounters() { return namedCounters; }
    public void setCustomCounters(Map<String, Integer> cc) { this.namedCounters = cc; }
}
