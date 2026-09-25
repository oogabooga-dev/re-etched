package gg.moonflower.etched.client.radio;

import gg.moonflower.etched.client.radio.sound.LocalSoundEventSink;
import gg.moonflower.etched.client.radio.sound.MinecraftLocalSoundEventSink;
import gg.moonflower.etched.common.audio.AudioProgram;
import gg.moonflower.etched.common.audio.AudioTrack;
import gg.moonflower.etched.common.audio.PlaybackState;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Finite vanilla sound events, opened independently without remote work. */
final class LocalSoundEventPlaybackBackend implements PlaybackBackend {

    private static final int MAX_ACTIVE_LOCAL_PLAYBACKS = 32;

    private final Object lock = new Object();
    private final Map<PlaybackOwnerKey, Active> attempts = new HashMap<>();
    private final LocalSoundEventSink sounds;
    private final Executor ownerExecutor;
    private boolean closed;

    LocalSoundEventPlaybackBackend() {
        this(new MinecraftLocalSoundEventSink(), command -> Minecraft.getInstance().tell(command));
    }

    LocalSoundEventPlaybackBackend(LocalSoundEventSink sounds, Executor ownerExecutor) {
        this.sounds = Objects.requireNonNull(sounds, "sounds");
        this.ownerExecutor = Objects.requireNonNull(ownerExecutor, "ownerExecutor");
    }

    @Override
    public boolean supports(PlaybackOwnerKey key, PlaybackState state) {
        return this.sounds.supports(key) && state.program().filter(program ->
                program.kind() == AudioProgram.Kind.FINITE && program.tracks().stream()
                        .allMatch(track -> track.sourceType() == AudioTrack.SourceType.SOUND_EVENT)).isPresent();
    }

    @Override
    public Admission admission(PlaybackOwnerKey key, PlaybackState state) {
        return Admission.LOCAL;
    }

    @Override
    public void start(PlaybackOwnerKey key, PlaybackState state, PlaybackSession session,
                      PlaybackSession.Attempt attempt, Events events) {
        if (!this.supports(key, state)) {
            throw new IllegalArgumentException("Local playback requires a finite sound-event program");
        }
        Active active = new Active(key, session, attempt, events, state.program().orElseThrow().tracks());
        synchronized (this.lock) {
            if (this.closed) {
                throw new RejectedExecutionException("Local sound-event backend is shut down");
            }
            if (this.attempts.containsKey(key)) {
                throw new IllegalStateException("A local playback attempt is already active for " + key);
            }
            if (this.attempts.size() >= MAX_ACTIVE_LOCAL_PLAYBACKS) {
                throw new RejectedExecutionException("Too many local sound events are active");
            }
            this.attempts.put(key, active);
        }
        attempt.cancellation().onCancel(() -> this.requestStop(active));
        active.events.progress(RadioPlaybackState.CONNECTING);
        this.openTrack(active, 0);
    }

    @Override
    public void stop(PlaybackOwnerKey key, PlaybackSession session) {
        Active active;
        synchronized (this.lock) {
            active = this.attempts.get(key);
            if (active == null || active.session != session) {
                return;
            }
        }
        this.close(active);
    }

    @Override
    public void abort(PlaybackOwnerKey key, PlaybackSession session, PlaybackSession.Attempt attempt) {
        Active active;
        synchronized (this.lock) {
            active = this.attempts.get(key);
            if (active == null || active.session != session || active.attempt != attempt) {
                return;
            }
        }
        this.close(active);
    }

    @Override
    public void shutdown() {
        List<Active> active;
        synchronized (this.lock) {
            if (this.closed) {
                return;
            }
            this.closed = true;
            active = new ArrayList<>(this.attempts.values());
        }
        active.forEach(this::close);
    }

    private void openTrack(Active active, int index) {
        Track track = new Track(index);
        synchronized (this.lock) {
            if (!this.isCurrent(active)) {
                return;
            }
            active.track = track;
        }
        try {
            active.attempt.cancellation().throwIfCancelled();
            ResourceLocation event = ResourceLocation.tryParse(active.tracks.get(index).source());
            if (event == null) {
                throw new IllegalArgumentException("Invalid local sound event");
            }
            LocalSoundEventSink.Handle handle = this.sounds.create(active.key, event,
                    active.attempt.cancellation(), () -> this.dispatchStopped(active, track));
            boolean accepted;
            synchronized (this.lock) {
                accepted = this.isCurrentTrack(active, track);
                if (accepted) {
                    track.handle = handle;
                }
            }
            if (!accepted) {
                handle.requestStop();
                handle.stop();
                return;
            }
            active.attempt.cancellation().throwIfCancelled();
            active.events.progress(RadioPlaybackState.BUFFERING);
            if (!handle.play()) {
                throw new IllegalStateException("SoundManager did not accept local playback");
            }
            active.events.progress(RadioPlaybackState.PLAYING);
            boolean stopped;
            synchronized (this.lock) {
                track.starting = false;
                stopped = track.stopPending;
            }
            if (stopped) {
                this.soundStopped(active, track);
            }
        } catch (RuntimeException failure) {
            LocalSoundEventSink.Handle handle;
            synchronized (this.lock) {
                if (!this.isCurrentTrack(active, track) || track.terminal) {
                    return;
                }
                track.terminal = true;
                handle = track.handle;
            }
            if (handle != null) {
                handle.requestStop();
            }
            active.events.failure(failure);
        }
    }

    private void dispatchStopped(Active active, Track track) {
        AtomicBoolean started = new AtomicBoolean();
        try {
            this.ownerExecutor.execute(() -> {
                started.set(true);
                this.soundStopped(active, track);
            });
        } catch (RuntimeException exception) {
            if (started.get()) {
                throw exception;
            }
            synchronized (this.lock) {
                if (!this.isCurrentTrack(active, track)) {
                    return;
                }
                active.soundOutputAvailable = false;
            }
            this.requestStop(active);
            this.close(active);
            active.events.ownerUnavailable(exception);
        }
    }

    private void soundStopped(Active active, Track track) {
        synchronized (this.lock) {
            if (!this.isCurrentTrack(active, track) || track.terminal) {
                return;
            }
            if (track.starting) {
                track.stopPending = true;
                return;
            }
            track.terminal = true;
            active.track = null;
        }
        int next = track.index + 1;
        if (next < active.tracks.size()) {
            active.events.sequenceAdvance(() -> this.openTrack(active, next));
        } else {
            active.events.completion();
        }
    }

    private void requestStop(Active active) {
        LocalSoundEventSink.Handle handle;
        synchronized (this.lock) {
            handle = active.track == null ? null : active.track.handle;
        }
        if (handle != null) {
            handle.requestStop();
        }
    }

    private void close(Active active) {
        LocalSoundEventSink.Handle handle;
        synchronized (this.lock) {
            if (!this.isCurrent(active)) {
                return;
            }
            active.closed = true;
            this.attempts.remove(active.key, active);
            handle = active.track == null ? null : active.track.handle;
            active.track = null;
        }
        if (handle != null) {
            handle.requestStop();
            if (active.soundOutputAvailable) {
                handle.stop();
            }
        }
    }

    private boolean isCurrent(Active active) {
        return !active.closed && this.attempts.get(active.key) == active;
    }

    private boolean isCurrentTrack(Active active, Track track) {
        return this.isCurrent(active) && active.track == track;
    }

    private static final class Active {

        private final PlaybackOwnerKey key;
        private final PlaybackSession session;
        private final PlaybackSession.Attempt attempt;
        private final Events events;
        private final List<AudioTrack> tracks;
        private Track track;
        private boolean closed;
        private volatile boolean soundOutputAvailable = true;

        private Active(PlaybackOwnerKey key, PlaybackSession session, PlaybackSession.Attempt attempt,
                       Events events, List<AudioTrack> tracks) {
            this.key = key;
            this.session = Objects.requireNonNull(session, "session");
            this.attempt = Objects.requireNonNull(attempt, "attempt");
            this.events = Objects.requireNonNull(events, "events");
            this.tracks = tracks;
        }
    }

    private static final class Track {

        private final int index;
        private LocalSoundEventSink.Handle handle;
        private boolean starting = true;
        private boolean stopPending;
        private boolean terminal;

        private Track(int index) {
            this.index = index;
        }
    }
}
