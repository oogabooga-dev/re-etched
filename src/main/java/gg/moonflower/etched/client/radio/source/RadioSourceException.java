package gg.moonflower.etched.client.radio.source;

import gg.moonflower.etched.common.audio.RadioFailure;
import gg.moonflower.etched.client.radio.net.RadioTransportException;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Objects;

public final class RadioSourceException extends IOException {

    private final RadioFailure.Code code;
    private final boolean recoverable;
    private final long retryAfterMillis;
    private final int httpStatus;

    public RadioSourceException(RadioFailure.Code code, boolean recoverable, String message,
                                 @Nullable Throwable cause) {
        this(code, recoverable, message, cause, RadioFailure.NO_RETRY_AFTER, -1);
    }

    public RadioSourceException(RadioFailure.Code code, boolean recoverable, String message,
                                @Nullable Throwable cause, long retryAfterMillis) {
        this(code, recoverable, message, cause, retryAfterMillis, -1);
    }

    public RadioSourceException(RadioFailure.Code code, boolean recoverable, String message,
                                @Nullable Throwable cause, long retryAfterMillis, int httpStatus) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
        this.recoverable = recoverable;
        if (retryAfterMillis < RadioFailure.NO_RETRY_AFTER) {
            throw new IllegalArgumentException("Retry-After must be non-negative or absent");
        }
        this.retryAfterMillis = retryAfterMillis;
        if (httpStatus != -1 && (httpStatus < 100 || httpStatus > 999)) {
            throw new IllegalArgumentException("HTTP status must be absent or a three-digit value");
        }
        this.httpStatus = httpStatus;
    }

    public RadioFailure.Code code() {
        return this.code;
    }

    public boolean recoverable() {
        return this.recoverable;
    }

    public long retryAfterMillis() {
        return this.retryAfterMillis;
    }

    public int httpStatus() {
        return this.httpStatus;
    }

    public RadioFailure toFailure() {
        return new RadioFailure(this.code, this.recoverable, this.getMessage(), this, this.retryAfterMillis);
    }

    static RadioSourceException fromTransport(RadioTransportException exception) {
        return new RadioSourceException(exception.code(), exception.recoverable(),
                exception.getMessage(), exception);
    }
}
