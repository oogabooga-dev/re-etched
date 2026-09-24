package gg.moonflower.etched.client.radio;

import gg.moonflower.etched.client.radio.stream.RadioAudioStream;
import gg.moonflower.etched.common.audio.PlaybackRevision;
import gg.moonflower.etched.common.radio.RadioClientBridge;
import gg.moonflower.etched.common.radio.RadioConfiguration;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Owns the client-side set of loaded radios and deduplicates configuration changes.
 */
public final class RadioPlaybackManager implements RadioClientBridge.Listener {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final int MAX_ACTIVE_RADIOS = 8;
    private static final int MAX_QUEUED_RADIOS = 32;
    private static final RadioPlaybackManager INSTANCE = new RadioPlaybackManager(
            PlaybackDriver.NOOP, new ProductionRadioSessionDriver(), new MinecraftRadioPlaybackEffects(),
            RadioReconnectController.createDefault(command -> Minecraft.getInstance().execute(command)));

    private final Map<PlaybackOwnerKey.BlockOwner, ManagedRadio> radios;
    private final PlaybackDriver playback;
    private final SessionDriver sessions;
    private final RadioPlaybackEffects effects;
    private final RadioReconnectController reconnects;
    private final RadioConnectionScheduler connections;
    private boolean closed;

    RadioPlaybackManager(PlaybackDriver playback) {
        this(playback, SessionDriver.NOOP, RadioPlaybackEffects.NOOP);
    }

    RadioPlaybackManager(PlaybackDriver playback, SessionDriver sessions) {
        this(playback, sessions, RadioPlaybackEffects.NOOP);
    }

    RadioPlaybackManager(PlaybackDriver playback, SessionDriver sessions, RadioPlaybackEffects effects) {
        this(playback, sessions, effects, RadioReconnectController.createDefault(Runnable::run));
    }

    RadioPlaybackManager(PlaybackDriver playback, SessionDriver sessions, RadioPlaybackEffects effects,
                         RadioReconnectController reconnects) {
        this(playback, sessions, effects, reconnects,
                new RadioConnectionScheduler(MAX_ACTIVE_RADIOS, MAX_QUEUED_RADIOS, reconnects::execute));
    }

    RadioPlaybackManager(PlaybackDriver playback, SessionDriver sessions, RadioPlaybackEffects effects,
                         RadioReconnectController reconnects, RadioConnectionScheduler connections) {
        this.radios = new HashMap<>();
        this.playback = Objects.requireNonNull(playback, "playback");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.effects = Objects.requireNonNull(effects, "effects");
        this.reconnects = Objects.requireNonNull(reconnects, "reconnects");
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    public static RadioPlaybackManager getInstance() {
        return INSTANCE;
    }

    @Override
    public void update(ResourceKey<Level> dimension, BlockPos pos, RadioConfiguration configuration) {
        this.update(PlaybackOwnerKey.block(dimension, pos), configuration);
    }

    public boolean update(PlaybackOwnerKey.BlockOwner key, RadioConfiguration configuration) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(configuration, "configuration");
        if (this.closed) {
            return false;
        }
        ManagedRadio previous = this.radios.get(key);
        if (previous != null) {
            long previousRevision = previous.configuration().revision();
            if (configuration.revision() == previousRevision) {
                if (!configuration.equals(previous.configuration())) {
                    LOGGER.warn("Ignoring conflicting radio state at {} for revision {}",
                            key, configuration.revision());
                }
                return false;
            }
            if (!PlaybackRevision.isNewer(configuration.revision(), previousRevision)) {
                return false;
            }
        }

        if (previous != null) {
            this.radios.remove(key);
            this.stopSession(key, previous);
        }
        RadioSession session = new RadioSession();
        ManagedRadio radio = new ManagedRadio(configuration, session);
        this.radios.put(key, radio);
        String source = configuration.url();
        if (this.sessions.enabled()) {
            if (configuration.isEnabled()) {
                RadioSession.Attempt attempt = session.start(source);
                this.startSession(key, radio, attempt);
            }
            this.effects.update(key, session.snapshot());
        } else {
            this.playback.apply(key, configuration);
        }
        return true;
    }

    @Override
    public void remove(ResourceKey<Level> dimension, BlockPos pos) {
        this.remove(PlaybackOwnerKey.block(dimension, pos));
    }

    public boolean remove(PlaybackOwnerKey.BlockOwner key) {
        Objects.requireNonNull(key, "key");
        ManagedRadio removed = this.radios.remove(key);
        if (removed == null) {
            return false;
        }

        if (this.sessions.enabled()) {
            this.stopSession(key, removed);
        } else {
            removed.session().stop();
            this.playback.stop(key);
        }
        return true;
    }

    @Override
    public void tick(ResourceKey<Level> dimension, BlockPos pos, RadioConfiguration configuration) {
        this.tick(PlaybackOwnerKey.block(dimension, pos), configuration);
    }

    public void tick(PlaybackOwnerKey.BlockOwner key, RadioConfiguration configuration) {
        if (this.closed) {
            return;
        }
        this.update(key, configuration);
        ManagedRadio radio = this.radios.get(key);
        if (radio == null) {
            return;
        }
        if (this.sessions.enabled()) {
            radio.session().applyPendingStreamTitle();
            this.effects.update(key, radio.session().snapshot());
        } else {
            this.playback.tick(key, radio.configuration());
        }
    }

    @Override
    public boolean isPlaying(ResourceKey<Level> dimension, BlockPos pos) {
        return this.isPlaying(PlaybackOwnerKey.block(dimension, pos));
    }

    public boolean isPlaying(PlaybackOwnerKey.BlockOwner key) {
        ManagedRadio radio = this.radios.get(key);
        if (radio == null) {
            return false;
        }
        return this.sessions.enabled()
                ? radio.session().snapshot().state() == RadioPlaybackState.PLAYING
                : this.playback.isPlaying(key);
    }

    public Optional<RadioConfiguration> getConfiguration(PlaybackOwnerKey.BlockOwner key) {
        ManagedRadio radio = this.radios.get(key);
        return radio == null ? Optional.empty() : Optional.of(radio.configuration());
    }

    public Optional<RadioSession.Snapshot> getSessionSnapshot(PlaybackOwnerKey.BlockOwner key) {
        ManagedRadio radio = this.radios.get(Objects.requireNonNull(key, "key"));
        return radio == null ? Optional.empty() : Optional.of(radio.session().snapshot());
    }

    public boolean retry(PlaybackOwnerKey.BlockOwner key) {
        Objects.requireNonNull(key, "key");
        if (this.closed || !this.sessions.enabled()) {
            return false;
        }
        ManagedRadio radio = this.radios.get(key);
        if (radio == null || !radio.configuration().isEnabled()) {
            return false;
        }
        Optional<RadioSession.Attempt> retried = radio.session().retry(radio.session().snapshot().generation());
        if (retried.isEmpty()) {
            return false;
        }
        RadioSession.Attempt attempt = retried.orElseThrow();
        this.effects.update(key, radio.session().snapshot());
        this.startSession(key, radio, attempt);
        return true;
    }

    public void clearAll() {
        ArrayList<Map.Entry<PlaybackOwnerKey.BlockOwner, ManagedRadio>> entries =
                new ArrayList<>(this.radios.entrySet());
        this.radios.clear();
        RuntimeException failure = null;
        for (Map.Entry<PlaybackOwnerKey.BlockOwner, ManagedRadio> entry : entries) {
            try {
                if (this.sessions.enabled()) {
                    this.stopSession(entry.getKey(), entry.getValue());
                } else {
                    entry.getValue().session().stop();
                    this.playback.stop(entry.getKey());
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

    private void stopSession(PlaybackOwnerKey.BlockOwner key, ManagedRadio radio) {
        RadioSession session = radio.session();
        session.stop();
        if (this.sessions.enabled()) {
            try {
                this.sessions.stop(key, session);
            } finally {
                try {
                    radio.releaseAttempt(null);
                } finally {
                    this.effects.stop(key);
                }
            }
        }
    }

    private void startSession(PlaybackOwnerKey.BlockOwner key, ManagedRadio radio,
                              RadioSession.Attempt attempt) {
        boolean accepted = this.connections.submit(attempt.cancellation(), lease -> {
            if (this.radios.get(key) != radio || attempt.cancellation().isCancelled()) {
                lease.close();
                return;
            }
            radio.ownAttempt(attempt, lease);
            try {
                this.startAdmittedSession(key, radio, attempt);
            } catch (RuntimeException exception) {
                try {
                    this.sessions.abort(key, radio.session(), attempt);
                } catch (RuntimeException abortException) {
                    exception.addSuppressed(abortException);
                } finally {
                    radio.releaseAttempt(attempt);
                }
                this.reconnects.failure(radio.session(), attempt, exception,
                        retry -> this.startCurrentSession(key, radio, retry),
                        () -> this.terminalStateChanged(key, radio, attempt));
            }
        }, () -> this.admissionDispatchFailed(radio, attempt));
        if (!accepted) {
            this.reconnects.failure(radio.session(), attempt, RadioConnectionScheduler.limitFailure(),
                    retry -> this.startCurrentSession(key, radio, retry),
                    () -> this.terminalStateChanged(key, radio, attempt));
        }
    }

    private void admissionDispatchFailed(ManagedRadio radio, RadioSession.Attempt attempt) {
        radio.session().fail(attempt, RadioConnectionScheduler.unavailableFailure());
    }

    private void startAdmittedSession(PlaybackOwnerKey.BlockOwner key, ManagedRadio radio,
                                      RadioSession.Attempt attempt) {
        this.sessions.start(key, radio.configuration(), radio.session(), attempt,
                new SessionEvents() {
                    @Override
                    public void progress(RadioPlaybackState state) {
                        reconnects.progress(radio.session(), attempt, state,
                                () -> updateEffects(key, radio));
                    }

                    @Override
                    public void sequenceAdvance(Runnable continuation) {
                        reconnects.sequenceAdvance(radio.session(), attempt, continuation,
                                () -> updateEffects(key, radio));
                    }

                    @Override
                    public void completion() {
                        reconnects.execute(() -> {
                            if (radio.session().complete(attempt)) {
                                terminalStateChanged(key, radio, attempt);
                            }
                        });
                    }

                    @Override
                    public void failure(Throwable failure) {
                        reconnects.failure(radio.session(), attempt, failure,
                                retry -> startCurrentSession(key, radio, retry),
                                () -> terminalStateChanged(key, radio, attempt));
                    }

                    @Override
                    public void termination(RadioAudioStream.Termination termination) {
                        reconnects.termination(radio.session(), attempt, termination,
                                retry -> startCurrentSession(key, radio, retry),
                                () -> terminalStateChanged(key, radio, attempt));
                    }

                    @Override
                    public void soundEngineStopped() {
                        reconnects.soundEngineStopped(radio.session(), attempt,
                                retry -> startCurrentSession(key, radio, retry),
                                () -> terminalStateChanged(key, radio, attempt));
                    }

                    @Override
                    public void ownerUnavailable(Throwable failure) {
                        RadioFailure unavailable = RadioFailure.fatal(RadioFailure.Code.RESOURCE_LIMIT,
                                "Radio client executor is unavailable", failure);
                        if (!radio.session().fail(attempt, unavailable)) {
                            return;
                        }
                        try {
                            sessions.abort(key, radio.session(), attempt);
                        } finally {
                            radio.releaseAttempt(attempt);
                        }
                    }
                });
    }

    private void terminalStateChanged(PlaybackOwnerKey.BlockOwner key, ManagedRadio radio,
                                      RadioSession.Attempt attempt) {
        RadioSession.Snapshot snapshot = radio.session().snapshot();
        if (snapshot.failure() != null) {
            RadioFailure failure = snapshot.failure();
            LOGGER.warn("Radio {} generation {} for host {} ended with {} (recoverable={}): {}",
                    key, attempt.generation(), sourceHost(attempt.source()), failure.code(),
                    failure.recoverable(), failure.message(), failure.cause());
        }
        try {
            if (radio.ownsAttempt(attempt)) {
                this.sessions.abort(key, radio.session(), attempt);
            }
        } finally {
            try {
                radio.releaseAttempt(attempt);
            } finally {
                this.updateEffects(key, radio);
            }
        }
    }

    private void startCurrentSession(PlaybackOwnerKey.BlockOwner key, ManagedRadio radio,
                                     RadioSession.Attempt attempt) {
        if (this.radios.get(key) != radio) {
            attempt.cancellation().cancel();
            return;
        }
        this.startSession(key, radio, attempt);
    }

    private void updateEffects(PlaybackOwnerKey.BlockOwner key, ManagedRadio radio) {
        if (this.radios.get(key) == radio) {
            this.effects.update(key, radio.session().snapshot());
        }
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
            public void apply(PlaybackOwnerKey.BlockOwner key, RadioConfiguration configuration) {
            }

            @Override
            public void stop(PlaybackOwnerKey.BlockOwner key) {
            }

            @Override
            public void tick(PlaybackOwnerKey.BlockOwner key, RadioConfiguration configuration) {
            }

            @Override
            public boolean isPlaying(PlaybackOwnerKey.BlockOwner key) {
                return false;
            }
        };

        void apply(PlaybackOwnerKey.BlockOwner key, RadioConfiguration configuration);

        void stop(PlaybackOwnerKey.BlockOwner key);

        void tick(PlaybackOwnerKey.BlockOwner key, RadioConfiguration configuration);

        boolean isPlaying(PlaybackOwnerKey.BlockOwner key);
    }

    interface SessionDriver {

        SessionDriver NOOP = new SessionDriver() {
            @Override
            public boolean enabled() {
                return false;
            }

            @Override
            public void start(PlaybackOwnerKey.BlockOwner key, RadioConfiguration configuration,
                              RadioSession session,
                              RadioSession.Attempt attempt, SessionEvents events) {
            }

            @Override
            public void stop(PlaybackOwnerKey.BlockOwner key, RadioSession session) {
            }

            @Override
            public void abort(PlaybackOwnerKey.BlockOwner key, RadioSession session,
                              RadioSession.Attempt attempt) {
            }
        };

        default boolean enabled() {
            return true;
        }

        void start(PlaybackOwnerKey.BlockOwner key, RadioConfiguration configuration,
                   RadioSession session,
                   RadioSession.Attempt attempt, SessionEvents events);

        void stop(PlaybackOwnerKey.BlockOwner key, RadioSession session);

        void abort(PlaybackOwnerKey.BlockOwner key, RadioSession session,
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

    private static final class ManagedRadio {

        private final RadioConfiguration configuration;
        private final RadioSession session;
        private AttemptLease attemptLease;

        private ManagedRadio(RadioConfiguration configuration, RadioSession session) {
            this.configuration = configuration;
            this.session = session;
        }

        private RadioConfiguration configuration() {
            return this.configuration;
        }

        private RadioSession session() {
            return this.session;
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
