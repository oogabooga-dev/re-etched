package gg.moonflower.etched.client.radio;

import gg.moonflower.etched.client.radio.sound.LocalSoundEventSink;
import gg.moonflower.etched.client.radio.stream.PlaybackAudioStream;
import gg.moonflower.etched.common.audio.AudioProgram;
import gg.moonflower.etched.common.audio.AudioTrack;
import gg.moonflower.etched.common.audio.PlaybackState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalSoundEventPlaybackBackendTest {

    static {
        MinecraftTestBootstrap.bootStrap();
    }

    private static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath("etched_test", "local"));
    private static final PlaybackOwnerKey.BlockOwner KEY = PlaybackOwnerKey.block(DIMENSION, BlockPos.ZERO);

    @Test
    void playsTracksInOrderAndIgnoresLateStopFromTheRetiredSound() {
        FakeSink sounds = new FakeSink();
        LocalSoundEventPlaybackBackend backend = new LocalSoundEventPlaybackBackend(sounds, Runnable::run);
        PlaybackState state = localState("minecraft:music_disc.13", "minecraft:music_disc.cat");
        PlaybackSession session = new PlaybackSession();
        PlaybackSession.Attempt attempt = session.start("minecraft:music_disc.13");
        RecordingEvents events = new RecordingEvents(session, attempt);

        backend.start(KEY, state, session, attempt, events);
        assertEquals(List.of(ResourceLocation.parse("minecraft:music_disc.13")), sounds.events);
        sounds.handles.get(0).complete();
        sounds.handles.get(0).complete();
        assertEquals(List.of(ResourceLocation.parse("minecraft:music_disc.13"),
                ResourceLocation.parse("minecraft:music_disc.cat")), sounds.events);
        assertEquals(RadioPlaybackState.PLAYING, session.snapshot().state());
        sounds.handles.get(1).complete();

        assertEquals(RadioPlaybackState.STOPPED, session.snapshot().state());
        assertEquals(List.of(RadioPlaybackState.CONNECTING, RadioPlaybackState.BUFFERING,
                RadioPlaybackState.PLAYING, RadioPlaybackState.CONNECTING,
                RadioPlaybackState.BUFFERING, RadioPlaybackState.PLAYING), events.progress);
        assertEquals(1, events.completions);
        assertTrue(events.failures.isEmpty());
        assertTrue(attempt.cancellation().isCancelled());
        backend.abort(KEY, session, attempt);
        backend.shutdown();
    }

    @Test
    void sharedManagerRoutesLocalEventsWithoutAStreamBackend() {
        FakeSink sounds = new FakeSink();
        LocalSoundEventPlaybackBackend local = new LocalSoundEventPlaybackBackend(sounds, Runnable::run);
        AudioPlaybackManager manager = new AudioPlaybackManager(AudioPlaybackManager.PlaybackDriver.NOOP,
                new RoutingPlaybackBackend(List.of(PlaybackBackend.NOOP, local)));

        assertTrue(manager.update(KEY, localState("minecraft:music_disc.13", "minecraft:music_disc.cat")));
        assertTrue(manager.isPlaying(KEY));
        sounds.handles.get(0).complete();
        sounds.handles.get(1).complete();

        assertEquals(2, sounds.events.size());
        assertEquals(RadioPlaybackState.STOPPED, manager.getSessionSnapshot(KEY).orElseThrow().state());
        assertFalse(manager.isPlaying(KEY));
        manager.shutdown();
    }

    @Test
    void repeatOneReopensTheCurrentSoundAndSkipBypassesIt() {
        FakeSink sounds = new FakeSink();
        AudioPlaybackManager manager = new AudioPlaybackManager(AudioPlaybackManager.PlaybackDriver.NOOP,
                new RoutingPlaybackBackend(List.of(new LocalSoundEventPlaybackBackend(sounds, Runnable::run))));
        PlaybackState state = localState("minecraft:music_disc.13", "minecraft:music_disc.cat");
        manager.update(KEY, state);
        long generation = manager.getSessionSnapshot(KEY).orElseThrow().generation();

        assertTrue(manager.setFiniteLoop(KEY, state.revision(), generation, FiniteLoopMode.ONE));
        sounds.handles.get(0).complete();
        assertEquals(List.of(ResourceLocation.parse("minecraft:music_disc.13"),
                ResourceLocation.parse("minecraft:music_disc.13")), sounds.events);
        assertTrue(manager.skipFiniteTrack(KEY, state.revision(), generation));
        assertEquals(1, sounds.handles.get(1).stops);
        sounds.handles.get(1).complete();
        assertEquals(ResourceLocation.parse("minecraft:music_disc.cat"), sounds.events.get(2));
        assertEquals(RadioPlaybackState.PLAYING, manager.getSessionSnapshot(KEY).orElseThrow().state());
        assertTrue(manager.setFiniteLoop(KEY, state.revision(), generation, FiniteLoopMode.OFF));
        sounds.handles.get(2).complete();

        assertEquals(RadioPlaybackState.STOPPED, manager.getSessionSnapshot(KEY).orElseThrow().state());
        assertEquals(3, sounds.events.size());
        assertFalse(manager.skipFiniteTrack(KEY, state.revision(), generation));
        manager.shutdown();
    }

    @Test
    void repeatAllWrapsOnCompletionAndSkipOfLastTrack() {
        FakeSink sounds = new FakeSink();
        AudioPlaybackManager manager = new AudioPlaybackManager(AudioPlaybackManager.PlaybackDriver.NOOP,
                new LocalSoundEventPlaybackBackend(sounds, Runnable::run));
        PlaybackState state = localState("minecraft:music_disc.13", "minecraft:music_disc.cat");
        manager.update(KEY, state);
        long generation = manager.getSessionSnapshot(KEY).orElseThrow().generation();
        assertTrue(manager.setFiniteLoop(KEY, state.revision(), generation, FiniteLoopMode.ALL));

        sounds.handles.get(0).complete();
        sounds.handles.get(1).complete();
        assertEquals(ResourceLocation.parse("minecraft:music_disc.13"), sounds.events.get(2));
        assertTrue(manager.skipFiniteTrack(KEY, state.revision(), generation));
        assertTrue(manager.skipFiniteTrack(KEY, state.revision(), generation));
        assertEquals(List.of(ResourceLocation.parse("minecraft:music_disc.13"),
                ResourceLocation.parse("minecraft:music_disc.cat"),
                ResourceLocation.parse("minecraft:music_disc.13"),
                ResourceLocation.parse("minecraft:music_disc.cat"),
                ResourceLocation.parse("minecraft:music_disc.13")), sounds.events);
        assertEquals(RadioPlaybackState.PLAYING, manager.getSessionSnapshot(KEY).orElseThrow().state());
        manager.remove(KEY);
        sounds.handles.get(4).complete();
        assertEquals(5, sounds.events.size());
        manager.shutdown();
    }

    @Test
    void controlsRejectStaleOwnerRevisionAndGeneration() {
        FakeSink sounds = new FakeSink();
        AudioPlaybackManager manager = new AudioPlaybackManager(AudioPlaybackManager.PlaybackDriver.NOOP,
                new RoutingPlaybackBackend(List.of(new LocalSoundEventPlaybackBackend(sounds, Runnable::run))));
        PlaybackState first = localState("minecraft:music_disc.13");
        manager.update(KEY, first);
        long oldGeneration = manager.getSessionSnapshot(KEY).orElseThrow().generation();
        PlaybackState replacement = new PlaybackState(1L, first.program(), true);
        manager.update(KEY, replacement);
        long currentGeneration = manager.getSessionSnapshot(KEY).orElseThrow().generation();
        assertEquals(oldGeneration, currentGeneration);

        assertFalse(manager.setFiniteLoop(KEY, first.revision(), oldGeneration, FiniteLoopMode.ALL));
        assertFalse(manager.skipFiniteTrack(KEY, first.revision(), oldGeneration));
        assertFalse(manager.skipFiniteTrack(KEY, replacement.revision(), currentGeneration + 1));
        assertTrue(manager.skipFiniteTrack(KEY, replacement.revision(), currentGeneration));
        assertEquals(RadioPlaybackState.STOPPED, manager.getSessionSnapshot(KEY).orElseThrow().state());
        sounds.handles.get(0).complete();
        assertEquals(2, sounds.events.size());
        manager.shutdown();
    }

    @Test
    void skippingSingleTrackInRepeatOneCompletesInsteadOfReopeningIt() {
        FakeSink sounds = new FakeSink();
        AudioPlaybackManager manager = new AudioPlaybackManager(AudioPlaybackManager.PlaybackDriver.NOOP,
                new LocalSoundEventPlaybackBackend(sounds, Runnable::run));
        manager.update(KEY, localState("minecraft:music_disc.13"));
        long generation = manager.getSessionSnapshot(KEY).orElseThrow().generation();
        assertTrue(manager.setFiniteLoop(KEY, 0L, generation, FiniteLoopMode.ONE));

        assertTrue(manager.skipFiniteTrack(KEY, 0L, generation));

        assertEquals(1, sounds.events.size());
        assertEquals(RadioPlaybackState.STOPPED, manager.getSessionSnapshot(KEY).orElseThrow().state());
        manager.shutdown();
    }

    @Test
    void cancelledAttemptStopsSoundAndCannotAdvanceAfterStaleCallback() {
        FakeSink sounds = new FakeSink();
        LocalSoundEventPlaybackBackend backend = new LocalSoundEventPlaybackBackend(sounds, Runnable::run);
        PlaybackSession session = new PlaybackSession();
        PlaybackSession.Attempt attempt = session.start("minecraft:music_disc.13");
        RecordingEvents events = new RecordingEvents(session, attempt);
        backend.start(KEY, localState("minecraft:music_disc.13", "minecraft:music_disc.cat"),
                session, attempt, events);

        session.stop();
        backend.stop(KEY, session);
        sounds.handles.get(0).complete();
        backend.stop(KEY, session);

        assertEquals(1, sounds.handles.get(0).stops);
        assertTrue(sounds.handles.get(0).stopRequested);
        assertEquals(1, sounds.events.size());
        assertEquals(0, events.completions);
        backend.shutdown();
    }

    @Test
    void immediateStopInsidePlayWaitsForPlayingBeforeCompleting() {
        FakeSink sounds = new FakeSink();
        sounds.stopDuringPlay = true;
        LocalSoundEventPlaybackBackend backend = new LocalSoundEventPlaybackBackend(sounds, Runnable::run);
        PlaybackSession session = new PlaybackSession();
        PlaybackSession.Attempt attempt = session.start("minecraft:music_disc.13");
        RecordingEvents events = new RecordingEvents(session, attempt);

        backend.start(KEY, localState("minecraft:music_disc.13"), session, attempt, events);

        assertEquals(RadioPlaybackState.STOPPED, session.snapshot().state());
        assertEquals(1, events.completions);
        assertTrue(events.failures.isEmpty());
        backend.shutdown();
    }

    @Test
    void failedPlayReportsFailureForTerminalCleanup() {
        FakeSink sounds = new FakeSink();
        sounds.acceptPlay = false;
        LocalSoundEventPlaybackBackend backend = new LocalSoundEventPlaybackBackend(sounds, Runnable::run);
        PlaybackSession session = new PlaybackSession();
        PlaybackSession.Attempt attempt = session.start("minecraft:music_disc.13");
        RecordingEvents events = new RecordingEvents(session, attempt);

        backend.start(KEY, localState("minecraft:music_disc.13"), session, attempt, events);
        assertEquals(1, events.failures.size());
        session.stop();
        backend.abort(KEY, session, attempt);

        assertEquals(1, sounds.handles.get(0).stops);
        assertEquals(0, events.completions);
        backend.shutdown();
    }

    @Test
    void sinkCreationFailureReportsOnceAndDoesNotStartAnotherTrack() {
        FakeSink sounds = new FakeSink();
        sounds.failCreate = true;
        LocalSoundEventPlaybackBackend backend = new LocalSoundEventPlaybackBackend(sounds, Runnable::run);
        PlaybackSession session = new PlaybackSession();
        PlaybackSession.Attempt attempt = session.start("minecraft:music_disc.13");
        RecordingEvents events = new RecordingEvents(session, attempt);

        backend.start(KEY, localState("minecraft:music_disc.13", "minecraft:music_disc.cat"),
                session, attempt, events);

        assertEquals(1, events.failures.size());
        assertTrue(sounds.events.isEmpty());
        session.stop();
        backend.abort(KEY, session, attempt);
        assertEquals(0, events.completions);
        backend.shutdown();
    }

    @Test
    void ownerExecutorRejectionOnlyRequestsStopWithoutCallingSoundManager() {
        FakeSink sounds = new FakeSink();
        LocalSoundEventPlaybackBackend backend = new LocalSoundEventPlaybackBackend(sounds,
                command -> {
                    throw new RejectedExecutionException("owner stopped");
                });
        PlaybackSession session = new PlaybackSession();
        PlaybackSession.Attempt attempt = session.start("minecraft:music_disc.13");
        RecordingEvents events = new RecordingEvents(session, attempt);
        backend.start(KEY, localState("minecraft:music_disc.13"), session, attempt, events);

        sounds.handles.get(0).complete();

        assertEquals(1, events.unavailableOwners);
        assertTrue(sounds.handles.get(0).stopRequested);
        assertEquals(0, sounds.handles.get(0).stops);
        backend.shutdown();
    }

    @Test
    void staleSoundStopAfterCancellationDoesNotReportOwnerFailure() {
        FakeSink sounds = new FakeSink();
        LocalSoundEventPlaybackBackend backend = new LocalSoundEventPlaybackBackend(sounds,
                command -> {
                    throw new RejectedExecutionException("owner stopped");
                });
        PlaybackSession session = new PlaybackSession();
        PlaybackSession.Attempt attempt = session.start("minecraft:music_disc.13");
        RecordingEvents events = new RecordingEvents(session, attempt);
        backend.start(KEY, localState("minecraft:music_disc.13"), session, attempt, events);

        session.stop();
        backend.stop(KEY, session);
        sounds.handles.get(0).complete();

        assertEquals(0, events.unavailableOwners);
        assertEquals(1, sounds.handles.get(0).stops);
        backend.shutdown();
    }

    @Test
    void acceptsOnlyLocalProgramsSupportedByItsSink() {
        FakeSink sounds = new FakeSink();
        LocalSoundEventPlaybackBackend backend = new LocalSoundEventPlaybackBackend(sounds, Runnable::run);
        PlaybackOwnerKey entity = PlaybackOwnerKey.entity(DIMENSION, new UUID(0L, 1L));
        PlaybackState remote = new PlaybackState(0L, Optional.of(new AudioProgram(AudioProgram.Kind.FINITE,
                List.of(new AudioTrack(AudioTrack.SourceType.REMOTE, "https://audio.example/track", "", "")))), true);

        assertTrue(backend.supports(KEY, localState("minecraft:music_disc.13")));
        assertFalse(backend.supports(entity, localState("minecraft:music_disc.13")));
        assertFalse(backend.supports(KEY, remote));
        assertEquals(PlaybackBackend.Admission.LOCAL,
                backend.admission(KEY, localState("minecraft:music_disc.13")));
        backend.shutdown();
    }

    @Test
    void localPlaybackCapacityIsBoundedAndReleasedOnStop() {
        FakeSink sounds = new FakeSink();
        LocalSoundEventPlaybackBackend backend = new LocalSoundEventPlaybackBackend(sounds, Runnable::run);
        PlaybackState state = localState("minecraft:music_disc.13");
        List<PlaybackSession> sessions = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            PlaybackSession session = new PlaybackSession();
            PlaybackSession.Attempt attempt = session.start("minecraft:music_disc.13");
            backend.start(PlaybackOwnerKey.block(DIMENSION, new BlockPos(i, 0, 0)), state,
                    session, attempt, new RecordingEvents(session, attempt));
            sessions.add(session);
        }
        PlaybackOwnerKey next = PlaybackOwnerKey.block(DIMENSION, new BlockPos(32, 0, 0));
        PlaybackSession nextSession = new PlaybackSession();
        PlaybackSession.Attempt nextAttempt = nextSession.start("minecraft:music_disc.13");

        assertThrows(RejectedExecutionException.class, () -> backend.start(next, state,
                nextSession, nextAttempt, new RecordingEvents(nextSession, nextAttempt)));
        PlaybackOwnerKey first = PlaybackOwnerKey.block(DIMENSION, BlockPos.ZERO);
        sessions.get(0).stop();
        backend.stop(first, sessions.get(0));
        backend.start(next, state, nextSession, nextAttempt, new RecordingEvents(nextSession, nextAttempt));

        assertEquals(33, sounds.handles.size());
        backend.shutdown();
    }

    private static PlaybackState localState(String... ids) {
        List<AudioTrack> tracks = new ArrayList<>();
        for (String id : ids) {
            tracks.add(new AudioTrack(AudioTrack.SourceType.SOUND_EVENT, id, "", ""));
        }
        return new PlaybackState(0L, Optional.of(new AudioProgram(AudioProgram.Kind.FINITE, tracks)), true);
    }

    private static final class FakeSink implements LocalSoundEventSink {

        private final List<ResourceLocation> events = new ArrayList<>();
        private final List<FakeHandle> handles = new ArrayList<>();
        private boolean acceptPlay = true;
        private boolean stopDuringPlay;
        private boolean failCreate;

        @Override
        public boolean supports(PlaybackOwnerKey key) {
            return key instanceof PlaybackOwnerKey.BlockOwner;
        }

        @Override
        public Handle create(PlaybackOwnerKey key, ResourceLocation event, AudioCancellation cancellation,
                             Runnable soundStopped) {
            if (this.failCreate) {
                throw new IllegalStateException("Sound manager is unavailable");
            }
            this.events.add(event);
            FakeHandle handle = new FakeHandle(soundStopped);
            this.handles.add(handle);
            return handle;
        }

        private final class FakeHandle implements Handle {

            private final Runnable soundStopped;
            private int stops;
            private boolean stopRequested;

            private FakeHandle(Runnable soundStopped) {
                this.soundStopped = soundStopped;
            }

            @Override
            public boolean play() {
                if (stopDuringPlay) {
                    this.complete();
                }
                return acceptPlay;
            }

            @Override
            public void requestStop() {
                this.stopRequested = true;
            }

            @Override
            public void stop() {
                this.stops++;
                this.complete();
            }

            private void complete() {
                this.soundStopped.run();
            }
        }
    }

    private static final class RecordingEvents implements PlaybackBackend.Events {

        private final PlaybackSession session;
        private final PlaybackSession.Attempt attempt;
        private final List<RadioPlaybackState> progress = new ArrayList<>();
        private final List<Throwable> failures = new ArrayList<>();
        private int completions;
        private int unavailableOwners;

        private RecordingEvents(PlaybackSession session, PlaybackSession.Attempt attempt) {
            this.session = session;
            this.attempt = attempt;
        }

        @Override
        public void progress(RadioPlaybackState state) {
            if (this.session.advance(this.attempt, state, 0L)) {
                this.progress.add(state);
            }
        }

        @Override
        public void sequenceAdvance(Runnable continuation) {
            if (this.session.advanceToNextTrack(this.attempt)) {
                this.progress.add(RadioPlaybackState.CONNECTING);
                continuation.run();
            }
        }

        @Override
        public void completion() {
            if (this.session.complete(this.attempt)) {
                this.completions++;
            }
        }

        @Override
        public void failure(Throwable failure) {
            this.failures.add(failure);
        }

        @Override
        public void termination(PlaybackAudioStream.Termination termination) {
            throw new AssertionError("Local playback must not decode a stream");
        }

        @Override
        public void soundEngineStopped() {
            throw new AssertionError("Local playback uses its native stop callback");
        }

        @Override
        public void ownerUnavailable(Throwable failure) {
            this.unavailableOwners++;
        }
    }
}
