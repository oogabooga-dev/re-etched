package gg.moonflower.etched.common.audio;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * A classified radio failure that can safely be retained by a session.
 */
public record RadioFailure(Code code, boolean recoverable, String message, @Nullable Throwable cause,
                           long retryAfterMillis) {

    public static final long NO_RETRY_AFTER = -1L;

    public RadioFailure {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        if (retryAfterMillis < NO_RETRY_AFTER) {
            throw new IllegalArgumentException("Retry-After must be non-negative or absent");
        }
    }

    public RadioFailure(Code code, boolean recoverable, String message, @Nullable Throwable cause) {
        this(code, recoverable, message, cause, NO_RETRY_AFTER);
    }

    public static RadioFailure recoverable(Code code, String message, @Nullable Throwable cause) {
        return recoverable(code, message, cause, NO_RETRY_AFTER);
    }

    public static RadioFailure recoverable(Code code, String message, @Nullable Throwable cause,
                                            long retryAfterMillis) {
        return new RadioFailure(code, true, message, cause, retryAfterMillis);
    }

    public static RadioFailure fatal(Code code, String message, @Nullable Throwable cause) {
        return new RadioFailure(code, false, message, cause, NO_RETRY_AFTER);
    }

    public enum Code {
        INVALID_URL,
        BLOCKED_ADDRESS,
        CONNECT_TIMEOUT,
        READ_TIMEOUT,
        HTTP_STATUS,
        TOO_MANY_REDIRECTS,
        PLAYLIST_TOO_LARGE,
        UNSUPPORTED_HLS,
        UNSUPPORTED_AAC,
        UNSUPPORTED_AUDIO,
        DECODER_FAILURE,
        UNEXPECTED_EOF,
        SOUND_ENGINE_STOPPED,
        RESOURCE_LIMIT,
        UNSAFE_HTTP_STATE,
        UNKNOWN
    }
}
