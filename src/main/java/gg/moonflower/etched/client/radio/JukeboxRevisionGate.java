package gg.moonflower.etched.client.radio;

/** Retained jukebox watermarks; retired history is compressed without permitting stale resurrection. */
final class JukeboxRevisionGate {

    private final PlaybackRevisionHistory<PlaybackOwnerKey.BlockOwner> history = new PlaybackRevisionHistory<>();

    boolean accept(PlaybackOwnerKey.BlockOwner key, long revision) {
        return this.accept(key, revision, true);
    }

    boolean accept(PlaybackOwnerKey.BlockOwner key, long revision, boolean retain) {
        return this.history.accept(key, revision, retain);
    }

    void release(PlaybackOwnerKey.BlockOwner key) {
        this.history.release(key);
    }

    void clearAll() {
        this.history.clearAll();
    }
}
