package gg.moonflower.etched.client.radio;

import gg.moonflower.etched.common.audio.PlaybackState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;

/** Pins each session to the backend that accepted its first attempt. */
final class RoutingPlaybackBackend implements PlaybackBackend {

    private final List<PlaybackBackend> backends;
    private final Map<PlaybackOwnerKey, Binding> bindings = new HashMap<>();
    private boolean closed;

    RoutingPlaybackBackend(List<PlaybackBackend> backends) {
        this.backends = List.copyOf(backends);
        if (this.backends.isEmpty()) {
            throw new IllegalArgumentException("At least one playback backend is required");
        }
    }

    @Override
    public synchronized boolean supports(PlaybackOwnerKey key, PlaybackState state) {
        return !this.closed && this.backends.stream().anyMatch(backend ->
                backend.enabled() && backend.supports(key, state));
    }

    @Override
    public void start(PlaybackOwnerKey key, PlaybackState state, PlaybackSession session,
                      PlaybackSession.Attempt attempt, Events events) {
        PlaybackBackend backend;
        synchronized (this) {
            if (this.closed) {
                throw new RejectedExecutionException("Playback backends are shut down");
            }
            Binding binding = this.bindings.get(key);
            if (binding == null) {
                backend = this.backends.stream().filter(candidate ->
                                candidate.enabled() && candidate.supports(key, state))
                        .findFirst().orElseThrow(() -> new IllegalArgumentException(
                                "No playback backend supports " + key));
                binding = new Binding(session, backend);
                this.bindings.put(key, binding);
            } else {
                if (binding.session != session) {
                    throw new IllegalStateException("A different playback session owns " + key);
                }
                backend = binding.backend;
            }
            if (binding.attempt != null) {
                throw new IllegalStateException("A playback attempt is already active for " + key);
            }
            binding.attempt = attempt;
        }
        // Keep the binding if start fails: the manager must still abort any partially acquired resources.
        backend.start(key, state, session, attempt, events);
    }

    @Override
    public void stop(PlaybackOwnerKey key, PlaybackSession session) {
        PlaybackBackend backend;
        synchronized (this) {
            Binding binding = this.bindings.get(key);
            if (binding == null || binding.session != session) {
                return;
            }
            this.bindings.remove(key);
            backend = binding.backend;
        }
        backend.stop(key, session);
    }

    @Override
    public void abort(PlaybackOwnerKey key, PlaybackSession session, PlaybackSession.Attempt attempt) {
        PlaybackBackend backend;
        synchronized (this) {
            Binding binding = this.bindings.get(key);
            if (binding == null || binding.session != session || binding.attempt != attempt) {
                return;
            }
            binding.attempt = null;
            backend = binding.backend;
        }
        backend.abort(key, session, attempt);
    }

    @Override
    public void shutdown() {
        List<Map.Entry<PlaybackOwnerKey, Binding>> active;
        synchronized (this) {
            if (this.closed) {
                return;
            }
            this.closed = true;
            active = new ArrayList<>(this.bindings.entrySet());
            this.bindings.clear();
        }
        RuntimeException failure = null;
        for (Map.Entry<PlaybackOwnerKey, Binding> entry : active) {
            try {
                entry.getValue().backend.stop(entry.getKey(), entry.getValue().session);
            } catch (RuntimeException exception) {
                failure = addFailure(failure, exception);
            }
        }
        for (PlaybackBackend backend : this.backends) {
            try {
                backend.shutdown();
            } catch (RuntimeException exception) {
                failure = addFailure(failure, exception);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static RuntimeException addFailure(RuntimeException failure, RuntimeException exception) {
        if (failure == null) {
            return exception;
        }
        failure.addSuppressed(exception);
        return failure;
    }

    private static final class Binding {

        private final PlaybackSession session;
        private final PlaybackBackend backend;
        private PlaybackSession.Attempt attempt;

        private Binding(PlaybackSession session, PlaybackBackend backend) {
            this.session = Objects.requireNonNull(session, "session");
            this.backend = Objects.requireNonNull(backend, "backend");
        }
    }
}
