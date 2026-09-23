package gg.moonflower.etched.common.audio;

/** Wrap-safe ordering for monotonically increasing playback revisions. */
public final class PlaybackRevision {

    private PlaybackRevision() {
    }

    public static long next(long revision) {
        return revision + 1;
    }

    public static boolean isNewer(long candidate, long current) {
        return candidate != current && candidate - current > 0;
    }
}
