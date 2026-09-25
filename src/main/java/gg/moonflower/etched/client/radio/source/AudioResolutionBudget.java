package gg.moonflower.etched.client.radio.source;

import gg.moonflower.etched.client.radio.RadioFailure;

/** Shared request and entry budget for one source-resolution operation. */
public final class AudioResolutionBudget {

    private final AudioResolveLimits limits;
    private int steps;
    private int entries;

    public AudioResolutionBudget(AudioResolveLimits limits) {
        this.limits = java.util.Objects.requireNonNull(limits, "limits");
    }

    public synchronized int remainingSteps() {
        return this.limits.maxResolutionSteps() - this.steps;
    }

    public synchronized void consumeSteps(int count) throws RadioSourceException {
        if (count < 0 || count > this.limits.maxResolutionSteps() - this.steps) {
            throw limit("Radio source resolution exceeded the configured step limit");
        }
        this.steps += count;
    }

    public synchronized void consumeEntries(int count) throws RadioSourceException {
        if (count < 0 || count > this.limits.maxPlaylistEntries() - this.entries) {
            throw limit("Radio source resolution exceeded the entry limit");
        }
        this.entries += count;
    }

    private static RadioSourceException limit(String message) {
        return new RadioSourceException(RadioFailure.Code.RESOURCE_LIMIT, false, message, null);
    }
}
