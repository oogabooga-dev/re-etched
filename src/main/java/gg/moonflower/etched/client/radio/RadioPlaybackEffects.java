package gg.moonflower.etched.client.radio;

/** Applies client-only presentation state for session-backed radio playback. */
interface RadioPlaybackEffects {

    RadioPlaybackEffects NOOP = new RadioPlaybackEffects() {
        @Override
        public void update(PlaybackOwnerKey.BlockOwner key, RadioSession.Snapshot snapshot) {
        }

        @Override
        public void stop(PlaybackOwnerKey.BlockOwner key) {
        }
    };

    void update(PlaybackOwnerKey.BlockOwner key, RadioSession.Snapshot snapshot);

    void stop(PlaybackOwnerKey.BlockOwner key);
}
