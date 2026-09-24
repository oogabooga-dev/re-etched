package gg.moonflower.etched.client.radio;

import gg.moonflower.etched.client.radio.stream.RadioAudioStream;
import gg.moonflower.etched.common.audio.PlaybackState;

interface PlaybackBackend {

    PlaybackBackend NOOP = new PlaybackBackend() {
        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public void start(PlaybackOwnerKey key, PlaybackState state, PlaybackSession session,
                          PlaybackSession.Attempt attempt, Events events) {
        }

        @Override
        public void stop(PlaybackOwnerKey key, PlaybackSession session) {
        }

        @Override
        public void abort(PlaybackOwnerKey key, PlaybackSession session, PlaybackSession.Attempt attempt) {
        }
    };

    default boolean enabled() {
        return true;
    }

    default boolean supports(PlaybackOwnerKey key, PlaybackState state) {
        return true;
    }

    void start(PlaybackOwnerKey key, PlaybackState state, PlaybackSession session,
               PlaybackSession.Attempt attempt, Events events);

    void stop(PlaybackOwnerKey key, PlaybackSession session);

    void abort(PlaybackOwnerKey key, PlaybackSession session, PlaybackSession.Attempt attempt);

    default void shutdown() {
    }

    interface Events {

        void progress(RadioPlaybackState state);

        void sequenceAdvance(Runnable continuation);

        void completion();

        void failure(Throwable failure);

        void termination(RadioAudioStream.Termination termination);

        void soundEngineStopped();

        void ownerUnavailable(Throwable failure);
    }
}
