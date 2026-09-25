package gg.moonflower.etched.client.radio;

import gg.moonflower.etched.common.audio.PlaybackState;

import java.util.Objects;

/** Sequential remote finite-track playback using the live backend's proven stream lifecycle. */
final class FiniteRemotePlaybackBackend implements PlaybackBackend {

    private final LiveStreamPlaybackBackend remote;

    FiniteRemotePlaybackBackend() {
        this(LiveStreamPlaybackBackend.finiteRemote());
    }

    FiniteRemotePlaybackBackend(LiveStreamPlaybackBackend remote) {
        this.remote = Objects.requireNonNull(remote, "remote");
    }

    @Override
    public boolean supports(PlaybackOwnerKey key, PlaybackState state) {
        return this.remote.supports(key, state);
    }

    @Override
    public void start(PlaybackOwnerKey key, PlaybackState state, PlaybackSession session,
                      PlaybackSession.Attempt attempt, Events events) {
        this.remote.start(key, state, session, attempt, events);
    }

    @Override
    public void stop(PlaybackOwnerKey key, PlaybackSession session) {
        this.remote.stop(key, session);
    }

    @Override
    public void abort(PlaybackOwnerKey key, PlaybackSession session, PlaybackSession.Attempt attempt) {
        this.remote.abort(key, session, attempt);
    }

    @Override
    public boolean setFiniteLoop(PlaybackOwnerKey key, PlaybackSession session,
                                 PlaybackSession.Attempt attempt, FiniteLoopMode mode) {
        return this.remote.setFiniteLoop(key, session, attempt, mode);
    }

    @Override
    public boolean skipFiniteTrack(PlaybackOwnerKey key, PlaybackSession session,
                                   PlaybackSession.Attempt attempt) {
        return this.remote.skipFiniteTrack(key, session, attempt);
    }

    @Override
    public void shutdown() {
        this.remote.shutdown();
    }
}
