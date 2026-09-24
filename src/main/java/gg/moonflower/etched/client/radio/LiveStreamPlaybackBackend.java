package gg.moonflower.etched.client.radio;

import gg.moonflower.etched.client.radio.sound.MinecraftSoundEngineSink;
import gg.moonflower.etched.client.radio.sound.SoundEngineSink;
import gg.moonflower.etched.client.radio.source.AudioResolveContext;
import gg.moonflower.etched.client.radio.source.AudioSourceResolver;
import gg.moonflower.etched.client.radio.source.BandcampRadioSourceResolver;
import gg.moonflower.etched.client.radio.source.CompositeRadioSourceResolver;
import gg.moonflower.etched.client.radio.source.DirectRadioSourceResolver;
import gg.moonflower.etched.client.radio.source.RadioResolvedSource;
import gg.moonflower.etched.client.radio.source.RadioSourceException;
import gg.moonflower.etched.client.radio.source.RadioSourceProgram;
import gg.moonflower.etched.client.radio.source.SoundCloudRadioSourceResolver;
import gg.moonflower.etched.client.radio.stream.RadioAudioStream;
import gg.moonflower.etched.client.radio.stream.RadioStreamPipeline;
import gg.moonflower.etched.client.radio.stream.RadioStreamException;
import gg.moonflower.etched.common.audio.AudioProgram;
import gg.moonflower.etched.common.audio.PlaybackState;
import gg.moonflower.etched.core.Etched;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Playback backend for resolved, independently buffered live streams. */
public final class LiveStreamPlaybackBackend implements PlaybackBackend {

    private static final int WORK_QUEUE_CAPACITY = 32;

    private final Object lock = new Object();
    private final Map<PlaybackOwnerKey, ActiveAttempt> attempts = new HashMap<>();
    private final Map<PlaybackOwnerKey, Integer> serviceCursors = new HashMap<>();
    private final AudioSourceResolver resolver;
    private final ContextFactory contexts;
    private final ExecutorService resolverExecutor;
    private final ExecutorService producerExecutor;
    private final ExecutorService decoderExecutor;
    private final Executor ownerExecutor;
    private final SoundEngineSink sounds;
    private final BooleanSupplier forceStereo;
    private boolean closed;

    public LiveStreamPlaybackBackend() {
        this(new CompositeRadioSourceResolver(List.of(
                        new SoundCloudRadioSourceResolver(),
                        new BandcampRadioSourceResolver(),
                        new DirectRadioSourceResolver())),
                AudioResolveContext::createDefault,
                boundedExecutor("Etched radio resolver", 2),
                boundedExecutor("Etched radio producer", 8),
                boundedExecutor("Etched radio decoder", 2),
                command -> Minecraft.getInstance().execute(command),
                new MinecraftSoundEngineSink(),
                () -> Etched.CLIENT_CONFIG.forceStereo.get());
    }

    LiveStreamPlaybackBackend(AudioSourceResolver resolver, ContextFactory contexts,
                              ExecutorService resolverExecutor, ExecutorService producerExecutor,
                              ExecutorService decoderExecutor, Executor ownerExecutor,
                              SoundEngineSink sounds, BooleanSupplier forceStereo) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.resolverExecutor = Objects.requireNonNull(resolverExecutor, "resolverExecutor");
        this.producerExecutor = Objects.requireNonNull(producerExecutor, "producerExecutor");
        this.decoderExecutor = Objects.requireNonNull(decoderExecutor, "decoderExecutor");
        if (producerExecutor == decoderExecutor || resolverExecutor == producerExecutor
                || resolverExecutor == decoderExecutor) {
            throw new IllegalArgumentException("Radio worker executors must be distinct");
        }
        this.ownerExecutor = Objects.requireNonNull(ownerExecutor, "ownerExecutor");
        this.sounds = Objects.requireNonNull(sounds, "sounds");
        this.forceStereo = Objects.requireNonNull(forceStereo, "forceStereo");
    }

    @Override
    public boolean supports(PlaybackOwnerKey key, PlaybackState state) {
        return this.sounds.supports(key)
                && state.program().filter(program -> program.kind() == AudioProgram.Kind.LIVE).isPresent();
    }

    @Override
    public void start(PlaybackOwnerKey key, PlaybackState state,
                      PlaybackSession session,
                      PlaybackSession.Attempt attempt, PlaybackBackend.Events events) {
        Objects.requireNonNull(state, "state");
        if (!this.supports(key, state)) {
            throw new IllegalArgumentException("The live backend does not support this playback owner or state");
        }
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(events, "events");

        ActiveAttempt active = new ActiveAttempt(key, session, attempt, events,
                this.contexts.create(attempt.cancellation()));
        synchronized (this.lock) {
            if (this.closed) {
                throw new RejectedExecutionException("Radio session driver is shut down");
            }
            if (session.snapshot().attemptNumber() == 1) {
                this.serviceCursors.remove(key);
            }
            ActiveAttempt previous = this.attempts.put(key, active);
            if (previous != null) {
                this.attempts.put(key, previous);
                throw new IllegalStateException("A live playback attempt is already active for " + key);
            }
        }
        attempt.cancellation().onCancel(() -> this.closeAttempt(active));

        Future<?> worker = this.resolverExecutor.submit(() -> this.resolve(active));
        synchronized (this.lock) {
            if (this.isCurrentLocked(active)) {
                active.worker = worker;
            } else {
                cancel(this.resolverExecutor, worker);
            }
        }
    }

    @Override
    public void stop(PlaybackOwnerKey key, PlaybackSession session) {
        ActiveAttempt active;
        synchronized (this.lock) {
            active = this.attempts.get(key);
            if (active == null) {
                this.serviceCursors.remove(key);
                return;
            }
            if (active.session != session) {
                return;
            }
        }
        this.closeAttempt(active, false);
    }

    @Override
    public void abort(PlaybackOwnerKey key, PlaybackSession session,
                      PlaybackSession.Attempt attempt) {
        ActiveAttempt active;
        synchronized (this.lock) {
            active = this.attempts.get(key);
            if (active == null || active.session != session || active.attempt != attempt) {
                return;
            }
        }
        this.closeAttempt(active, session.snapshot().state() == RadioPlaybackState.RECONNECT_WAIT);
    }

    @Override
    public void shutdown() {
        List<ActiveAttempt> active;
        synchronized (this.lock) {
            if (this.closed) {
                return;
            }
            this.closed = true;
            active = new ArrayList<>(this.attempts.values());
            this.serviceCursors.clear();
        }
        active.forEach(attempt -> this.closeAttempt(attempt, false));
        shutdown(this.resolverExecutor);
        shutdown(this.producerExecutor);
        shutdown(this.decoderExecutor);
    }

    private void resolve(ActiveAttempt active) {
        try {
            active.attempt.cancellation().throwIfCancelled();
            URI input;
            try {
                input = URI.create(active.attempt.source());
            } catch (IllegalArgumentException exception) {
                throw new RadioSourceException(RadioFailure.Code.INVALID_URL, false,
                        "Radio source is not a valid URI", exception);
            }
            RadioSourceProgram program = this.resolver.resolveProgram(input, active.context);
            this.dispatch(() -> {
                if (!this.isCurrent(active)) {
                    return;
                }
                active.program = program;
                int initialTrack;
                synchronized (this.lock) {
                    initialTrack = program.kind() == RadioSourceProgram.Kind.SERVICE_TRACKS
                            ? Math.min(this.serviceCursors.getOrDefault(active.key, 0),
                            program.tracks().size() - 1) : 0;
                    if (program.kind() == RadioSourceProgram.Kind.STATION) {
                        this.serviceCursors.remove(active.key);
                    }
                }
                active.events.progress(RadioPlaybackState.CONNECTING);
                this.openTrack(active, initialTrack);
            }, active);
        } catch (Exception failure) {
            this.reportFailure(active, null, failure);
        }
    }

    private void openTrack(ActiveAttempt active, int index) {
        if (!this.isCurrent(active)) {
            return;
        }
        TrackPlayback track = new TrackPlayback(index);
        synchronized (this.lock) {
            if (!this.isCurrentLocked(active)) {
                return;
            }
            active.track = track;
            if (active.program.kind() == RadioSourceProgram.Kind.SERVICE_TRACKS) {
                this.serviceCursors.put(active.key, index);
            }
        }
        try {
            Future<?> worker = this.resolverExecutor.submit(() -> this.prepareTrack(active, track));
            synchronized (this.lock) {
                if (this.isCurrentTrackLocked(active, track)) {
                    track.worker = worker;
                } else {
                    worker.cancel(true);
                }
            }
        } catch (RuntimeException failure) {
            this.reportFailure(active, track, failure);
        }
    }

    private void prepareTrack(ActiveAttempt active, TrackPlayback track) {
        RadioResolvedSource source = null;
        try {
            active.attempt.cancellation().throwIfCancelled();
            track.cancellation.throwIfCancelled();
            AudioResolveContext trackContext = this.contexts.create(track.cancellation);
            source = active.program.openTrack(track.index, trackContext);
            if (!this.isCurrentTrack(active, track)) {
                source.close();
                return;
            }

            RadioStreamPipeline.Preparation preparation = RadioStreamPipeline.prepare(
                    source, track.cancellation, this.producerExecutor, this.decoderExecutor,
                    this.forceStereo.getAsBoolean(),
                    title -> active.session.offerStreamTitle(active.attempt, title));
            boolean accepted;
            synchronized (this.lock) {
                accepted = this.isCurrentTrackLocked(active, track);
                if (accepted) {
                    track.preparation = preparation;
                }
            }
            if (!accepted) {
                preparation.close();
                source = null;
                return;
            }
            source = null;
            preparation.stream().whenComplete((audio, failure) -> this.dispatch(
                    () -> this.prepared(active, track, preparation, audio, failure), active));
        } catch (Exception failure) {
            if (source != null) {
                source.close();
            }
            this.reportFailure(active, track, failure);
        }
    }

    private void prepared(ActiveAttempt active, TrackPlayback track,
                          RadioStreamPipeline.Preparation preparation,
                          RadioAudioStream audio, Throwable failure) {
        if (!this.isCurrentTrack(active, track)) {
            preparation.close();
            return;
        }
        if (failure != null) {
            this.reportFailure(active, track, failure);
            return;
        }

        SoundEngineSink.Handle sound;
        try {
            sound = this.sounds.create(active.key, active.attempt.generation(), audio,
                    active.attempt.cancellation(),
                    () -> this.streamHandedOff(active, track, preparation, audio),
                    () -> this.dispatch(() -> this.soundStopped(active, track), active));
        } catch (RuntimeException exception) {
            this.reportFailure(active, track, exception);
            return;
        }
        boolean accepted;
        synchronized (this.lock) {
            accepted = this.isCurrentTrackLocked(active, track) && track.preparation == preparation;
            if (accepted) {
                track.audio = audio;
                track.sound = sound;
            }
        }
        if (!accepted) {
            sound.requestStop();
            preparation.close();
            return;
        }
        audio.termination().whenComplete((termination, terminalFailure) ->
                this.observeTermination(active, track, termination, terminalFailure));
        String title = active.program.tracks().get(track.index).title();
        if (title != null) {
            active.session.offerStreamTitle(active.attempt, title);
        }
        active.events.progress(RadioPlaybackState.BUFFERING);
        try {
            if (!sound.play()) {
                this.reportFailure(active, track, new RadioStreamException(
                        RadioFailure.Code.SOUND_ENGINE_STOPPED, false,
                        "SoundManager did not accept radio playback", null));
            }
        } catch (RuntimeException exception) {
            this.reportFailure(active, track, exception);
        }
    }

    private void streamHandedOff(ActiveAttempt active, TrackPlayback track,
                                 RadioStreamPipeline.Preparation preparation,
                                 RadioAudioStream audio) {
        synchronized (this.lock) {
            if (!this.isCurrentTrackLocked(active, track) || track.preparation != preparation
                    || !preparation.transfer(audio)) {
                throw new IllegalStateException("Radio stream handoff no longer belongs to the active track");
            }
            track.preparation = null;
            track.transferred = true;
        }
        preparation.close();
        active.events.progress(RadioPlaybackState.PLAYING);
    }

    private void observeTermination(ActiveAttempt active, TrackPlayback track,
                                    RadioAudioStream.Termination termination, Throwable failure) {
        if (failure != null) {
            if (!this.claimTerminal(active, track)) {
                return;
            }
            this.dispatch(() -> {
                if (!active.attempt.cancellation().isCancelled()) {
                    this.reportClaimedFailure(active, failure);
                }
            }, active);
            return;
        }
        if (termination.state() == RadioAudioStream.TerminalState.EOF
                && active.program.kind() == RadioSourceProgram.Kind.SERVICE_TRACKS) {
            boolean advance;
            synchronized (this.lock) {
                if (this.isCurrentTrackLocked(active, track) && !track.terminal) {
                    track.eof = true;
                    advance = track.soundStopReported;
                    if (advance) {
                        track.terminal = true;
                    }
                } else {
                    advance = false;
                }
            }
            if (advance) {
                this.dispatch(() -> this.finishServiceTrack(active, track), active);
            }
            return;
        }
        if (!this.claimTerminal(active, track)) {
            return;
        }
        this.dispatch(() -> {
            if (active.attempt.cancellation().isCancelled()) {
                return;
            }
            if (termination.state() == RadioAudioStream.TerminalState.CLOSED) {
                active.events.soundEngineStopped();
            } else {
                active.events.termination(termination);
            }
        }, active);
    }

    private void soundStopped(ActiveAttempt active, TrackPlayback track) {
        boolean expectedEof;
        synchronized (this.lock) {
            if (!this.isCurrentTrackLocked(active, track) || track.terminal || track.soundStopReported) {
                return;
            }
            expectedEof = track.eof;
            if (expectedEof) {
                track.terminal = true;
            } else {
                track.soundStopReported = true;
            }
        }
        if (!expectedEof) {
            active.events.soundEngineStopped();
            return;
        }
        this.finishServiceTrack(active, track);
    }

    private void finishServiceTrack(ActiveAttempt active, TrackPlayback track) {
        if (!this.isCurrentTrack(active, track)) {
            return;
        }
        int next = track.index + 1;
        this.closeTrack(active, track);
        if (next < active.program.tracks().size()) {
            active.events.sequenceAdvance(() -> this.openTrack(active, next));
        } else {
            synchronized (this.lock) {
                this.serviceCursors.remove(active.key);
            }
            active.events.completion();
        }
    }

    private void reportFailure(ActiveAttempt active, TrackPlayback track, Throwable failure) {
        this.dispatch(() -> {
            if (track == null) {
                if (!this.isCurrent(active)) {
                    return;
                }
            } else if (!this.claimTerminal(active, track)) {
                return;
            }
            this.reportClaimedFailure(active, failure);
        }, active);
    }

    private void reportClaimedFailure(ActiveAttempt active, Throwable failure) {
        active.events.failure(unwrap(failure));
    }

    private boolean claimTerminal(ActiveAttempt active, TrackPlayback track) {
        synchronized (this.lock) {
            if (!this.isCurrentTrackLocked(active, track) || track.terminal) {
                return false;
            }
            track.terminal = true;
            return true;
        }
    }

    private boolean isCurrent(ActiveAttempt active) {
        synchronized (this.lock) {
            return this.isCurrentLocked(active);
        }
    }

    private boolean isCurrentTrack(ActiveAttempt active, TrackPlayback track) {
        synchronized (this.lock) {
            return this.isCurrentTrackLocked(active, track);
        }
    }

    private boolean isCurrentLocked(ActiveAttempt active) {
        return !this.closed && !active.closed && this.attempts.get(active.key) == active
                && !active.attempt.cancellation().isCancelled();
    }

    private boolean isCurrentTrackLocked(ActiveAttempt active, TrackPlayback track) {
        return this.isCurrentLocked(active) && active.track == track;
    }

    private void closeAttempt(ActiveAttempt active) {
        this.closeAttempt(active,
                active.session.snapshot().state() == RadioPlaybackState.RECONNECT_WAIT);
    }

    private void closeAttempt(ActiveAttempt active, boolean preserveServiceCursor) {
        this.closeAttempt(active, preserveServiceCursor, true);
    }

    private void closeAttempt(ActiveAttempt active, boolean preserveServiceCursor, boolean stopSound) {
        TrackPlayback track;
        Future<?> worker;
        synchronized (this.lock) {
            if (active.closed) {
                return;
            }
            active.closed = true;
            this.attempts.remove(active.key, active);
            if (!preserveServiceCursor) {
                this.serviceCursors.remove(active.key);
            }
            track = active.track;
            active.track = null;
            worker = active.worker;
            active.worker = null;
        }
        if (worker != null) {
            cancel(this.resolverExecutor, worker);
        }
        this.closeTrack(active, track, stopSound);
    }

    private void closeTrack(ActiveAttempt active, TrackPlayback track) {
        this.closeTrack(active, track, true);
    }

    private void closeTrack(ActiveAttempt active, TrackPlayback track, boolean stopSound) {
        if (track == null) {
            return;
        }
        SoundEngineSink.Handle sound;
        RadioStreamPipeline.Preparation preparation;
        RadioAudioStream audio;
        Future<?> worker;
        boolean transferred;
        synchronized (this.lock) {
            track.terminal = true;
            if (active.track == track) {
                active.track = null;
            }
            sound = track.sound;
            track.sound = null;
            preparation = track.preparation;
            track.preparation = null;
            audio = track.audio;
            track.audio = null;
            worker = track.worker;
            track.worker = null;
            transferred = track.transferred;
        }
        if (worker != null) {
            cancel(this.resolverExecutor, worker);
        }
        track.cancellation.cancel();
        boolean soundOutputOwnsAudio = false;
        if (sound != null) {
            sound.requestStop();
            if (stopSound) {
                synchronized (active) {
                    if (active.soundOutputAvailable) {
                        try {
                            sound.stop();
                            soundOutputOwnsAudio = transferred;
                        } catch (RuntimeException ignored) {
                        }
                    }
                }
            }
        }
        if (preparation != null) {
            preparation.close();
        } else if (transferred && audio != null && !soundOutputOwnsAudio) {
            track.cancellation.cancel();
            RadioAudioStream orphaned = audio;
            RadioResourceDisposer.dispose(() -> closeQuietly(orphaned));
        }
    }

    private void dispatch(Runnable action, ActiveAttempt active) {
        AtomicBoolean actionStarted = new AtomicBoolean();
        try {
            this.ownerExecutor.execute(() -> {
                actionStarted.set(true);
                action.run();
            });
        } catch (RuntimeException exception) {
            if (actionStarted.get()) {
                throw exception;
            }
            synchronized (active) {
                active.soundOutputAvailable = false;
            }
            this.closeAttempt(active, false, false);
            active.events.ownerUnavailable(exception);
        }
    }

    private static ExecutorService boundedExecutor(String name, int threads) {
        return new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(WORK_QUEUE_CAPACITY), daemonFactory(name),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static ThreadFactory daemonFactory(String name) {
        AtomicInteger number = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, name + " " + number.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static void shutdown(ExecutorService executor) {
        executor.shutdownNow();
    }

    private static void cancel(ExecutorService executor, Future<?> future) {
        future.cancel(true);
        if (executor instanceof ThreadPoolExecutor pool && future instanceof Runnable task) {
            pool.remove(task);
            pool.purge();
        }
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static void closeQuietly(RadioAudioStream audio) {
        try {
            audio.close();
        } catch (IOException ignored) {
        }
    }

    @FunctionalInterface
    interface ContextFactory {
        AudioResolveContext create(AudioCancellation cancellation);
    }

    private static final class ActiveAttempt {
        private final PlaybackOwnerKey key;
        private final PlaybackSession session;
        private final PlaybackSession.Attempt attempt;
        private final PlaybackBackend.Events events;
        private final AudioResolveContext context;
        private Future<?> worker;
        private RadioSourceProgram program;
        private TrackPlayback track;
        private boolean closed;
        private boolean soundOutputAvailable = true;

        private ActiveAttempt(PlaybackOwnerKey key, PlaybackSession session,
                              PlaybackSession.Attempt attempt,
                              PlaybackBackend.Events events, AudioResolveContext context) {
            this.key = key;
            this.session = session;
            this.attempt = attempt;
            this.events = events;
            this.context = context;
        }
    }

    private static final class TrackPlayback {
        private final int index;
        private final AudioCancellation cancellation = new AudioCancellation();
        private Future<?> worker;
        private RadioStreamPipeline.Preparation preparation;
        private RadioAudioStream audio;
        private SoundEngineSink.Handle sound;
        private boolean transferred;
        private boolean terminal;
        private boolean soundStopReported;
        private boolean eof;

        private TrackPlayback(int index) {
            this.index = index;
        }
    }
}
