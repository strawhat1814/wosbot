package dev.frostguard.tasks.exploration;

/**
 * City/World HUD trek fraction ({@code current/max}). Complete when current reaches max
 * (e.g. {@code 100/100}). Distinct from the in-event remaining counter ({@code 0/100} done).
 */
public record TrekHudProgress(int current, int max) {

    public boolean isComplete() {
        return max > 0 && current >= max;
    }
}
