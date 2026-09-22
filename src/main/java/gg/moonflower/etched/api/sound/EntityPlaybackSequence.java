package gg.moonflower.etched.api.sound;

final class EntityPlaybackSequence {

    private EntityPlaybackSequence() {
    }

    static int getLoopFallbackTrack(int requestedTrack, boolean loop) {
        return loop && requestedTrack != 0 ? 0 : -1;
    }
}
