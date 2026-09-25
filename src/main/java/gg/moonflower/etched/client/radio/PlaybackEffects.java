package gg.moonflower.etched.client.radio;

/**
 * Applies client-only presentation state for session-backed playback.
 * Implementations ignore owner kinds they do not support.
 */
interface PlaybackEffects {

    PlaybackEffects NOOP = new PlaybackEffects() {
        @Override
        public void update(PlaybackOwnerKey key, PlaybackSession.Snapshot snapshot) {
        }

        @Override
        public void stop(PlaybackOwnerKey key) {
        }
    };

    void update(PlaybackOwnerKey key, PlaybackSession.Snapshot snapshot);

    void stop(PlaybackOwnerKey key);
}
