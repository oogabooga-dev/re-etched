package gg.moonflower.etched.client.radio;

import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

final class RadioStatusMessages {

    private static final String PREFIX = "screen.etched.radio.";

    private RadioStatusMessages() {
    }

    @Nullable
    static Component forSnapshot(PlaybackSession.Snapshot snapshot) {
        return switch (snapshot.state()) {
            case STOPPED -> null;
            case RESOLVING -> Component.translatable(PREFIX + "status.resolving");
            case CONNECTING -> Component.translatable(PREFIX + "status.connecting");
            case BUFFERING -> Component.translatable(PREFIX + "status.buffering");
            case PLAYING -> snapshot.streamTitle() == null
                    ? Component.translatable("sound_source.etched.radio")
                    : Component.literal(snapshot.streamTitle());
            case RECONNECT_WAIT -> Component.translatable(PREFIX + "status.reconnecting");
            case FAILED -> failure(snapshot.failure());
        };
    }

    private static Component failure(@Nullable RadioFailure failure) {
        if (failure == null) {
            return Component.translatable(PREFIX + "error.playback");
        }
        String suffix = switch (failure.code()) {
            case INVALID_URL -> "invalid_url";
            case BLOCKED_ADDRESS, UNSAFE_HTTP_STATE -> "blocked_address";
            case CONNECT_TIMEOUT, READ_TIMEOUT -> "timeout";
            case UNSUPPORTED_HLS, UNSUPPORTED_AAC, UNSUPPORTED_AUDIO -> "unsupported_audio";
            case PLAYLIST_TOO_LARGE, RESOURCE_LIMIT -> "resource_limit";
            default -> "playback";
        };
        return Component.translatable(PREFIX + "error." + suffix);
    }
}
