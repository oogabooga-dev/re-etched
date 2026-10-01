package gg.moonflower.etched.client.radio.source;

import gg.moonflower.etched.common.audio.AudioContentProbe;

public record AudioResolveLimits(int sniffBytes, int maxPlaylistBytes, int maxPlaylistEntries,
                                 int maxLineLength, int maxPlaylistDepth, int maxResolutionSteps,
                                 int maxId3PrefixBytes) {

    public static final AudioResolveLimits DEFAULT = new AudioResolveLimits(
            AudioContentProbe.DEFAULT_SNIFF_BYTES, 256 * 1024, 100, 8192, 3, 128,
            AudioContentProbe.DEFAULT_MAX_ID3_PREFIX_BYTES);

    public AudioResolveLimits(int sniffBytes, int maxPlaylistBytes, int maxPlaylistEntries,
                              int maxLineLength, int maxPlaylistDepth, int maxResolutionSteps) {
        this(sniffBytes, maxPlaylistBytes, maxPlaylistEntries,
                maxLineLength, maxPlaylistDepth, maxResolutionSteps, sniffBytes);
    }

    public AudioResolveLimits {
        if (sniffBytes < 4 || maxId3PrefixBytes < 4 || maxId3PrefixBytes > 1 << 20
                || maxPlaylistBytes < 1 || maxPlaylistEntries < 1
                || maxLineLength < 1 || maxPlaylistDepth < 0 || maxResolutionSteps < 1) {
            throw new IllegalArgumentException("Radio source limits must be positive");
        }
        if (sniffBytes > maxPlaylistBytes) {
            throw new IllegalArgumentException("The sniff limit cannot exceed the playlist body limit");
        }
    }
}
