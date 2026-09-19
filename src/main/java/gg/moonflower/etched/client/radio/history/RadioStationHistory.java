package gg.moonflower.etched.client.radio.history;

import gg.moonflower.etched.common.radio.RadioUrlValidator;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Stores bounded, per-server recent radio stations in most-recent-first order. */
public final class RadioStationHistory {

    public static final int MAX_ENTRIES = 20;
    static final int MAX_CONTEXTS = 64;

    private final Clock clock;
    private final Map<String, ContextData> contexts = new LinkedHashMap<>();

    public RadioStationHistory() {
        this(Clock.systemUTC());
    }

    RadioStationHistory(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized boolean record(String contextKey, String url) {
        if (!RadioHistoryContext.isValidKey(contextKey)) {
            return false;
        }
        RadioUrlValidator.Result validation = RadioUrlValidator.validate(url);
        if (!validation.valid() || validation.normalized().isEmpty()) {
            return false;
        }

        String normalized = validation.normalized();
        ContextData context = this.contexts.computeIfAbsent(contextKey, key -> new ContextData());
        if (!context.stations.isEmpty() && context.stations.get(0).equals(normalized)) {
            return false;
        }

        context.stations.remove(normalized);
        context.stations.add(0, normalized);
        if (context.stations.size() > MAX_ENTRIES) {
            context.stations.subList(MAX_ENTRIES, context.stations.size()).clear();
        }
        context.modified = this.clock.millis();
        this.trimContexts();
        return true;
    }

    public synchronized List<String> entries(String contextKey) {
        ContextData context = this.contexts.get(contextKey);
        return context == null ? List.of() : List.copyOf(context.stations);
    }

    public synchronized boolean clear(String contextKey) {
        return this.contexts.remove(contextKey) != null;
    }

    synchronized Snapshot snapshot() {
        List<ContextSnapshot> snapshots = this.contexts.entrySet().stream()
                .map(entry -> new ContextSnapshot(entry.getKey(), entry.getValue().modified,
                        List.copyOf(entry.getValue().stations)))
                .toList();
        return new Snapshot(snapshots);
    }

    synchronized void restore(Snapshot snapshot) {
        this.contexts.clear();
        snapshot.contexts().stream()
                .filter(context -> RadioHistoryContext.isValidKey(context.key()))
                .sorted(Comparator.comparingLong(ContextSnapshot::modified).reversed())
                .forEach(context -> {
                    if (this.contexts.size() >= MAX_CONTEXTS || this.contexts.containsKey(context.key())) {
                        return;
                    }
                    ContextData data = new ContextData();
                    for (String url : context.stations()) {
                        RadioUrlValidator.Result validation = RadioUrlValidator.validate(url);
                        if (!validation.valid() || validation.normalized().isEmpty()
                                || data.stations.contains(validation.normalized())) {
                            continue;
                        }
                        data.stations.add(validation.normalized());
                        if (data.stations.size() == MAX_ENTRIES) {
                            break;
                        }
                    }
                    if (!data.stations.isEmpty()) {
                        data.modified = Math.max(0L, context.modified());
                        this.contexts.put(context.key(), data);
                    }
                });
    }

    synchronized boolean removeOldestContext() {
        String oldest = this.contexts.entrySet().stream()
                .min(Comparator.comparingLong(entry -> entry.getValue().modified))
                .map(Map.Entry::getKey)
                .orElse(null);
        return oldest != null && this.contexts.remove(oldest) != null;
    }

    private void trimContexts() {
        while (this.contexts.size() > MAX_CONTEXTS) {
            this.removeOldestContext();
        }
    }

    record Snapshot(List<ContextSnapshot> contexts) {

        Snapshot {
            contexts = List.copyOf(contexts);
        }
    }

    record ContextSnapshot(String key, long modified, List<String> stations) {

        ContextSnapshot {
            Objects.requireNonNull(key, "key");
            stations = List.copyOf(stations);
        }
    }

    private static final class ContextData {

        private final List<String> stations = new ArrayList<>();
        private long modified;
    }
}
