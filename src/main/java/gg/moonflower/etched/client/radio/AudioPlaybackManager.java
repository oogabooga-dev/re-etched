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
            PlaybackDriver.NOOP, new ProductionRadioSessionDriver(), new MinecraftRadioPlaybackEffects(),
            RadioReconnectController.createDefault(command -> Minecraft.getInstance().execute(command)));

    private final Map<PlaybackOwnerKey, ManagedPlayback> playbacks;
    private final PlaybackDriver playback;
    private final SessionDriver sessions;
    private final RadioPlaybackEffects effects;
    private final RadioReconnectController reconnects;
    private final RadioConnectionScheduler connections;
    private boolean closed;

    AudioPlaybackManager(PlaybackDriver playback) {
        this(playback, SessionDriver.NOOP, RadioPlaybackEffects.NOOP);
    }

    AudioPlaybackManager(PlaybackDriver playback, SessionDriver sessions) {
        this(playback, sessions, RadioPlaybackEffects.NOOP);
    }

    AudioPlaybackManager(PlaybackDriver playback, SessionDriver sessions, RadioPlaybackEffects effects) {
        this(playback, sessions, effects, RadioReconnectController.createDefault(Runnable::run));
    }

    AudioPlaybackManager(PlaybackDriver playback, SessionDriver sessions, RadioPlaybackEffects effects,
                         RadioReconnectController reconnects) {
        this(playback, sessions, effects, reconnects,
                new RadioConnectionScheduler(MAX_ACTIVE_PLAYBACKS, MAX_QUEUED_PLAYBACKS, reconnects::execute));
    }

    AudioPlaybackManager(PlaybackDriver playback, SessionDriver sessions, RadioPlaybackEffects effects,
                         RadioReconnectController reconnects, RadioConnectionScheduler connections) {
        this.playbacks = new HashMap<>();
        this.playback = Objects.requireNonNull(playback, "playback");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
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
        RadioSession session = new RadioSession();
        boolean backendSupported = this.supportsLiveBackend(key, state);
        ManagedPlayback playback = new ManagedPlayback(state, session, backendSupported);
        this.playbacks.put(key, playback);
        if (this.sessions.enabled()) {
            if (state.enabled() && backendSupported) {
                RadioSession.Attempt attempt = session.start(liveSource(state));
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

        if (this.sessions.enabled() && removed.backendSupported()) {
            this.stopSession(key, removed);
        } else {
            removed.session().stop();
            if (!this.sessions.enabled()) {
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
        if (this.sessions.enabled()) {
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
        return this.sessions.enabled()
                ? managed.session().snapshot().state() == RadioPlaybackState.PLAYING
                : this.playback.isPlaying(key);
    }

    public Optional<PlaybackState> getPlaybackState(PlaybackOwnerKey key) {
        ManagedPlayback managed = this.playbacks.get(key);
        return managed == null ? Optional.empty() : Optional.of(managed.state());
    }

    public Optional<RadioSession.Snapshot> getSessionSnapshot(PlaybackOwnerKey key) {
        ManagedPlayback managed = this.playbacks.get(Objects.requireNonNull(key, "key"));
        return managed == null ? Optional.empty() : Optional.of(managed.session().snapshot());
    }

    public boolean retry(PlaybackOwnerKey key) {
        Objects.requireNonNull(key, "key");
        if (this.closed || !this.sessions.enabled()) {
            return false;
        }
        ManagedPlayback managed = this.playbacks.get(key);
        if (managed == null || !managed.backendSupported() || !managed.state().enabled()) {
            return false;
        }
        Optional<RadioSession.Attempt> retried = managed.session().retry(managed.session().snapshot().generation());
        if (retried.isEmpty()) {
            return false;
        }
        RadioSession.Attempt attempt = retried.orElseThrow();
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
                if (this.sessions.enabled() && entry.getValue().backendSupported()) {
                    this.stopSession(entry.getKey(), entry.getValue());
                } else {
                    entry.getValue().session().stop();
                    if (!this.sessions.enabled()) {
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
                this.sessions.shutdown();
            } finally {
                this.connections.close();
                this.reconnects.close();
            }
        }
    }

    private void stopSession(PlaybackOwnerKey key, ManagedPlayback playback) {
        RadioSession session = playback.session();
        session.stop();
        if (this.sessions.enabled()) {
            try {
                this.sessions.stop(key, session);
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
                              RadioSession.Attempt attempt) {
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
                    this.sessions.abort(key, playback.session(), attempt);
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

    private void admissionDispatchFailed(ManagedPlayback playback, RadioSession.Attempt attempt) {
        playback.session().fail(attempt, RadioConnectionScheduler.unavailableFailure());
    }

    private void startAdmittedSession(PlaybackOwnerKey key, ManagedPlayback playback,
                                      RadioSession.Attempt attempt) {
        this.sessions.start(key, playback.state(), playback.session(), attempt,
                new SessionEvents() {
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
                            sessions.abort(key, playback.session(), attempt);
                        } finally {
                            playback.releaseAttempt(attempt);
                        }
                    }
                });
    }

    private void terminalStateChanged(PlaybackOwnerKey key, ManagedPlayback playback,
                                      RadioSession.Attempt attempt) {
        RadioSession.Snapshot snapshot = playback.session().snapshot();
        if (snapshot.failure() != null) {
            RadioFailure failure = snapshot.failure();
            LOGGER.warn("Radio {} generation {} for host {} ended with {} (recoverable={}): {}",
                    key, attempt.generation(), sourceHost(attempt.source()), failure.code(),
                    failure.recoverable(), failure.message(), failure.cause());
        }
        try {
            if (playback.ownsAttempt(attempt)) {
                this.sessions.abort(key, playback.session(), attempt);
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
                                     RadioSession.Attempt attempt) {
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
                && this.sessions.supports(key, state);
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

    interface SessionDriver {

        SessionDriver NOOP = new SessionDriver() {
            @Override
            public boolean enabled() {
                return false;
            }

            @Override
            public void start(PlaybackOwnerKey key, PlaybackState state,
                              RadioSession session,
                              RadioSession.Attempt attempt, SessionEvents events) {
            }

            @Override
            public void stop(PlaybackOwnerKey key, RadioSession session) {
            }

            @Override
            public void abort(PlaybackOwnerKey key, RadioSession session,
                              RadioSession.Attempt attempt) {
            }
        };

        default boolean enabled() {
            return true;
        }

        default boolean supports(PlaybackOwnerKey key, PlaybackState state) {
            return true;
        }

        void start(PlaybackOwnerKey key, PlaybackState state,
                   RadioSession session,
                   RadioSession.Attempt attempt, SessionEvents events);

        void stop(PlaybackOwnerKey key, RadioSession session);

        void abort(PlaybackOwnerKey key, RadioSession session,
                   RadioSession.Attempt attempt);

        default void shutdown() {
        }
    }

    interface SessionEvents {

        void progress(RadioPlaybackState state);

        void sequenceAdvance(Runnable continuation);

        void completion();

        void failure(Throwable failure);

        void termination(RadioAudioStream.Termination termination);

        void soundEngineStopped();

        void ownerUnavailable(Throwable failure);
    }

    private static final class ManagedPlayback {

        private final PlaybackState state;
        private final RadioSession session;
        private final boolean backendSupported;
        private AttemptLease attemptLease;

        private ManagedPlayback(PlaybackState state, RadioSession session, boolean backendSupported) {
            this.state = state;
            this.session = session;
            this.backendSupported = backendSupported;
        }

        private PlaybackState state() {
            return this.state;
        }

        private RadioSession session() {
            return this.session;
        }

        private boolean backendSupported() {
            return this.backendSupported;
        }

        private synchronized void ownAttempt(RadioSession.Attempt attempt,
                                             RadioConnectionScheduler.Lease lease) {
            this.releaseAttempt(null);
            this.attemptLease = new AttemptLease(attempt, lease);
        }

        private synchronized boolean ownsAttempt(RadioSession.Attempt attempt) {
            return this.attemptLease != null && this.attemptLease.attempt() == attempt;
        }

        private synchronized void releaseAttempt(
                @org.jetbrains.annotations.Nullable RadioSession.Attempt attempt) {
            if (this.attemptLease == null
                    || attempt != null && this.attemptLease.attempt() != attempt) {
                return;
            }
            RadioConnectionScheduler.Lease lease = this.attemptLease.lease();
            this.attemptLease = null;
            lease.close();
        }
    }

    private record AttemptLease(RadioSession.Attempt attempt, RadioConnectionScheduler.Lease lease) {
    }
}
