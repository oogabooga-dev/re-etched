package gg.moonflower.etched.client.radio;

import gg.moonflower.etched.client.radio.stream.RadioAudioStream;
import gg.moonflower.etched.common.audio.AudioProgram;
import gg.moonflower.etched.common.audio.PlaybackState;
import gg.moonflower.etched.common.audio.PlaybackRevision;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Owns client-side authoritative playback state and the sessions derived from it.
 */
public final class AudioPlaybackManager {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final int MAX_ACTIVE_PLAYBACKS = 8;
    private static final int MAX_QUEUED_PLAYBACKS = 32;
    private static final AudioPlaybackManager INSTANCE = new AudioPlaybackManager(
            PlaybackDriver.NOOP, new LiveStreamPlaybackBackend(), new MinecraftRadioPlaybackEffects(),
            RadioReconnectController.createDefault(command -> Minecraft.getInstance().execute(command)));

    private final Map<PlaybackOwnerKey, ManagedPlayback> playbacks;
    private final PlaybackDriver playback;
    private final PlaybackBackend backend;
    private final RadioPlaybackEffects effects;
    private final RadioReconnectController reconnects;
    private final RadioConnectionScheduler connections;
    private boolean closed;

    AudioPlaybackManager(PlaybackDriver playback) {
        this(playback, PlaybackBackend.NOOP, RadioPlaybackEffects.NOOP);
    }

    AudioPlaybackManager(PlaybackDriver playback, PlaybackBackend backend) {
        this(playback, backend, RadioPlaybackEffects.NOOP);
    }

    AudioPlaybackManager(PlaybackDriver playback, PlaybackBackend backend, RadioPlaybackEffects effects) {
        this(playback, backend, effects, RadioReconnectController.createDefault(Runnable::run));
    }

    AudioPlaybackManager(PlaybackDriver playback, PlaybackBackend backend, RadioPlaybackEffects effects,
                         RadioReconnectController reconnects) {
        this(playback, backend, effects, reconnects,
                new RadioConnectionScheduler(MAX_ACTIVE_PLAYBACKS, MAX_QUEUED_PLAYBACKS, reconnects::execute));
    }

    AudioPlaybackManager(PlaybackDriver playback, PlaybackBackend backend, RadioPlaybackEffects effects,
                         RadioReconnectController reconnects, RadioConnectionScheduler connections) {
        this.playbacks = new HashMap<>();
        this.playback = Objects.requireNonNull(playback, "playback");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.effects = Objects.requireNonNull(effects, "effects");
        this.reconnects = Objects.requireNonNull(reconnects, "reconnects");
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    public static AudioPlaybackManager getInstance() {
        return INSTANCE;
    }

    public boolean update(PlaybackOwnerKey key, PlaybackState state) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(state, "state");
        if (this.closed) {
            return false;
        }
        ManagedPlayback previous = this.playbacks.get(key);
        if (previous != null) {
            long previousRevision = previous.state().revision();
            if (state.revision() == previousRevision) {
                if (!state.equals(previous.state())) {
                    LOGGER.warn("Ignoring conflicting playback state at {} for revision {}",
                            key, state.revision());
                }
                return false;
            }
            if (!PlaybackRevision.isNewer(state.revision(), previousRevision)) {
                return false;
            }
        }

        if (previous != null) {
            this.playbacks.remove(key);
            this.stopSession(key, previous);
        }
        PlaybackSession session = new PlaybackSession();
        boolean backendSupported = this.supportsLiveBackend(key, state);
        ManagedPlayback playback = new ManagedPlayback(state, session, backendSupported);
        this.playbacks.put(key, playback);
        if (this.backend.enabled()) {
            if (state.enabled() && backendSupported) {
                PlaybackSession.Attempt attempt = session.start(liveSource(state));
                this.startSession(key, playback, attempt);
            } else if (state.enabled()) {
                LOGGER.warn("No playback backend supports enabled state for {}", key);
            }
            if (backendSupported) {
                this.updateEffects(key, playback);
            }
        } else {
            this.playback.apply(key, state);
        }
        return true;
    }

    public boolean remove(PlaybackOwnerKey key) {
        Objects.requireNonNull(key, "key");
        ManagedPlayback removed = this.playbacks.remove(key);
        if (removed == null) {
            return false;
        }

        if (this.backend.enabled() && removed.backendSupported()) {
            this.stopSession(key, removed);
        } else {
            removed.session().stop();
            if (!this.backend.enabled()) {
                this.playback.stop(key);
            }
        }
        return true;
    }

    public boolean tick(PlaybackOwnerKey key, PlaybackState state) {
        if (this.closed) {
            return false;
        }
        boolean updated = this.update(key, state);
        ManagedPlayback managed = this.playbacks.get(key);
        if (managed == null) {
            return updated;
        }
        if (this.backend.enabled()) {
            if (managed.backendSupported()) {
                managed.session().applyPendingStreamTitle();
                this.updateEffects(key, managed);
            }
        } else {
            this.playback.tick(key, managed.state());
        }
        return updated;
    }

    public boolean isPlaying(PlaybackOwnerKey key) {
        ManagedPlayback managed = this.playbacks.get(key);
        if (managed == null) {
            return false;
        }
        return this.backend.enabled()
                ? managed.session().snapshot().state() == RadioPlaybackState.PLAYING
                : this.playback.isPlaying(key);
    }

    public Optional<PlaybackState> getPlaybackState(PlaybackOwnerKey key) {
        ManagedPlayback managed = this.playbacks.get(key);
        return managed == null ? Optional.empty() : Optional.of(managed.state());
    }

    public Optional<PlaybackSession.Snapshot> getSessionSnapshot(PlaybackOwnerKey key) {
        ManagedPlayback managed = this.playbacks.get(Objects.requireNonNull(key, "key"));
        return managed == null ? Optional.empty() : Optional.of(managed.session().snapshot());
    }

    public boolean retry(PlaybackOwnerKey key) {
        Objects.requireNonNull(key, "key");
        if (this.closed || !this.backend.enabled()) {
            return false;
        }
        ManagedPlayback managed = this.playbacks.get(key);
        if (managed == null || !managed.backendSupported() || !managed.state().enabled()) {
            return false;
        }
        Optional<PlaybackSession.Attempt> retried = managed.session().retry(managed.session().snapshot().generation());
        if (retried.isEmpty()) {
            return false;
        }
        PlaybackSession.Attempt attempt = retried.orElseThrow();
        this.updateEffects(key, managed);
        this.startSession(key, managed, attempt);
        return true;
    }

    public void clearAll() {
        ArrayList<Map.Entry<PlaybackOwnerKey, ManagedPlayback>> entries =
                new ArrayList<>(this.playbacks.entrySet());
        this.playbacks.clear();
        RuntimeException failure = null;
        for (Map.Entry<PlaybackOwnerKey, ManagedPlayback> entry : entries) {
            try {
                if (this.backend.enabled() && entry.getValue().backendSupported()) {
                    this.stopSession(entry.getKey(), entry.getValue());
                } else {
                    entry.getValue().session().stop();
                    if (!this.backend.enabled()) {
                        this.playback.stop(entry.getKey());
                    }
                }
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    public void shutdown() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        try {
            this.clearAll();
        } finally {
            try {
                this.backend.shutdown();
            } finally {
                this.connections.close();
                this.reconnects.close();
            }
        }
    }

    private void stopSession(PlaybackOwnerKey key, ManagedPlayback playback) {
        PlaybackSession session = playback.session();
        session.stop();
        if (this.backend.enabled()) {
            try {
                this.backend.stop(key, session);
            } finally {
                try {
                    playback.releaseAttempt(null);
                } finally {
                    this.stopEffects(key);
                }
            }
        }
    }

    private void startSession(PlaybackOwnerKey key, ManagedPlayback playback,
                              PlaybackSession.Attempt attempt) {
        boolean accepted = this.connections.submit(attempt.cancellation(), lease -> {
            if (this.playbacks.get(key) != playback || attempt.cancellation().isCancelled()) {
                lease.close();
                return;
            }
            playback.ownAttempt(attempt, lease);
            try {
                this.startAdmittedSession(key, playback, attempt);
            } catch (RuntimeException exception) {
                try {
                    this.backend.abort(key, playback.session(), attempt);
                } catch (RuntimeException abortException) {
                    exception.addSuppressed(abortException);
                } finally {
                    playback.releaseAttempt(attempt);
                }
                this.reconnects.failure(playback.session(), attempt, exception,
                        retry -> this.startCurrentSession(key, playback, retry),
                        () -> this.terminalStateChanged(key, playback, attempt));
            }
        }, () -> this.admissionDispatchFailed(playback, attempt));
        if (!accepted) {
            this.reconnects.failure(playback.session(), attempt, RadioConnectionScheduler.limitFailure(),
                    retry -> this.startCurrentSession(key, playback, retry),
                    () -> this.terminalStateChanged(key, playback, attempt));
        }
    }

    private void admissionDispatchFailed(ManagedPlayback playback, PlaybackSession.Attempt attempt) {
        playback.session().fail(attempt, RadioConnectionScheduler.unavailableFailure());
    }

    private void startAdmittedSession(PlaybackOwnerKey key, ManagedPlayback playback,
                                      PlaybackSession.Attempt attempt) {
        this.backend.start(key, playback.state(), playback.session(), attempt,
                new PlaybackBackend.Events() {
                    @Override
                    public void progress(RadioPlaybackState state) {
                        reconnects.progress(playback.session(), attempt, state,
                                () -> updateEffects(key, playback));
                    }

                    @Override
                    public void sequenceAdvance(Runnable continuation) {
                        reconnects.sequenceAdvance(playback.session(), attempt, continuation,
                                () -> updateEffects(key, playback));
                    }

                    @Override
                    public void completion() {
                        reconnects.execute(() -> {
                            if (playback.session().complete(attempt)) {
                                terminalStateChanged(key, playback, attempt);
                            }
                        });
                    }

                    @Override
                    public void failure(Throwable failure) {
                        reconnects.failure(playback.session(), attempt, failure,
                                retry -> startCurrentSession(key, playback, retry),
                                () -> terminalStateChanged(key, playback, attempt));
                    }

                    @Override
                    public void termination(RadioAudioStream.Termination termination) {
                        reconnects.termination(playback.session(), attempt, termination,
                                retry -> startCurrentSession(key, playback, retry),
                                () -> terminalStateChanged(key, playback, attempt));
                    }

                    @Override
                    public void soundEngineStopped() {
                        reconnects.soundEngineStopped(playback.session(), attempt,
                                retry -> startCurrentSession(key, playback, retry),
                                () -> terminalStateChanged(key, playback, attempt));
                    }

                    @Override
                    public void ownerUnavailable(Throwable failure) {
                        RadioFailure unavailable = RadioFailure.fatal(RadioFailure.Code.RESOURCE_LIMIT,
                                "Radio client executor is unavailable", failure);
                        if (!playback.session().fail(attempt, unavailable)) {
                            return;
                        }
                        try {
                            backend.abort(key, playback.session(), attempt);
                        } finally {
                            playback.releaseAttempt(attempt);
                        }
                    }
                });
    }

    private void terminalStateChanged(PlaybackOwnerKey key, ManagedPlayback playback,
                                      PlaybackSession.Attempt attempt) {
        PlaybackSession.Snapshot snapshot = playback.session().snapshot();
        if (snapshot.failure() != null) {
            RadioFailure failure = snapshot.failure();
            LOGGER.warn("Radio {} generation {} for host {} ended with {} (recoverable={}): {}",
                    key, attempt.generation(), sourceHost(attempt.source()), failure.code(),
                    failure.recoverable(), failure.message(), failure.cause());
        }
        try {
            if (playback.ownsAttempt(attempt)) {
                this.backend.abort(key, playback.session(), attempt);
            }
        } finally {
            try {
                playback.releaseAttempt(attempt);
            } finally {
                this.updateEffects(key, playback);
            }
        }
    }

    private void startCurrentSession(PlaybackOwnerKey key, ManagedPlayback playback,
                                     PlaybackSession.Attempt attempt) {
        if (this.playbacks.get(key) != playback) {
            attempt.cancellation().cancel();
            return;
        }
        this.startSession(key, playback, attempt);
    }

    private void updateEffects(PlaybackOwnerKey key, ManagedPlayback playback) {
        if (this.playbacks.get(key) == playback && key instanceof PlaybackOwnerKey.BlockOwner blockOwner) {
            this.effects.update(blockOwner, playback.session().snapshot());
        }
    }

    private void stopEffects(PlaybackOwnerKey key) {
        if (key instanceof PlaybackOwnerKey.BlockOwner blockOwner) {
            this.effects.stop(blockOwner);
        }
    }

    private boolean supportsLiveBackend(PlaybackOwnerKey key, PlaybackState state) {
        return state.program().filter(program -> program.kind() == AudioProgram.Kind.LIVE).isPresent()
                && this.backend.supports(key, state);
    }

    private static String liveSource(PlaybackState state) {
        return state.program().orElseThrow().tracks().get(0).source();
    }

    private static String sourceHost(String source) {
        try {
            String host = URI.create(source).getHost();
            return host == null ? "<invalid>" : host;
        } catch (IllegalArgumentException exception) {
            return "<invalid>";
        }
    }

    interface PlaybackDriver {

        PlaybackDriver NOOP = new PlaybackDriver() {
            @Override
            public void apply(PlaybackOwnerKey key, PlaybackState state) {
            }

            @Override
            public void stop(PlaybackOwnerKey key) {
            }

            @Override
            public void tick(PlaybackOwnerKey key, PlaybackState state) {
            }

            @Override
            public boolean isPlaying(PlaybackOwnerKey key) {
                return false;
            }
        };

        void apply(PlaybackOwnerKey key, PlaybackState state);

        void stop(PlaybackOwnerKey key);

        void tick(PlaybackOwnerKey key, PlaybackState state);

        boolean isPlaying(PlaybackOwnerKey key);
    }

    private static final class ManagedPlayback {

        private final PlaybackState state;
        private final PlaybackSession session;
        private final boolean backendSupported;
        private AttemptLease attemptLease;

        private ManagedPlayback(PlaybackState state, PlaybackSession session, boolean backendSupported) {
            this.state = state;
            this.session = session;
            this.backendSupported = backendSupported;
        }

        private PlaybackState state() {
            return this.state;
        }

        private PlaybackSession session() {
            return this.session;
        }

        private boolean backendSupported() {
            return this.backendSupported;
        }

        private synchronized void ownAttempt(PlaybackSession.Attempt attempt,
                                             RadioConnectionScheduler.Lease lease) {
            this.releaseAttempt(null);
            this.attemptLease = new AttemptLease(attempt, lease);
        }

        private synchronized boolean ownsAttempt(PlaybackSession.Attempt attempt) {
            return this.attemptLease != null && this.attemptLease.attempt() == attempt;
        }

        private synchronized void releaseAttempt(
                @org.jetbrains.annotations.Nullable PlaybackSession.Attempt attempt) {
            if (this.attemptLease == null
                    || attempt != null && this.attemptLease.attempt() != attempt) {
                return;
            }
            RadioConnectionScheduler.Lease lease = this.attemptLease.lease();
            this.attemptLease = null;
            lease.close();
        }
    }

    private record AttemptLease(PlaybackSession.Attempt attempt, RadioConnectionScheduler.Lease lease) {
    }
}
