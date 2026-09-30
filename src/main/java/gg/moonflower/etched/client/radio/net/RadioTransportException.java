package gg.moonflower.etched.client.radio.net;

import gg.moonflower.etched.common.audio.RadioFailure;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Objects;

/**
 * A classified HTTP or network-policy failure.
 */
public final class RadioTransportException extends IOException {

    private final RadioFailure.Code code;
    private final boolean recoverable;
    private final int redirectCount;

    public RadioTransportException(RadioFailure.Code code, boolean recoverable, String message,
                                   @Nullable Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
        this.recoverable = recoverable;
        this.redirectCount = 0;
    }

    public RadioFailure.Code code() {
        return this.code;
    }

    public boolean recoverable() {
        return this.recoverable;
    }

    public int redirectCount() {
        return this.redirectCount;
    }

    RadioTransportException withRedirectCount(int redirectCount) {
        if (redirectCount == this.redirectCount) {
            return this;
        }
        return new RadioTransportException(this.code, this.recoverable, this.getMessage(),
                this.getCause(), redirectCount);
    }

    public RadioFailure toFailure() {
        return new RadioFailure(this.code, this.recoverable, this.getMessage(), this);
    }

    private RadioTransportException(RadioFailure.Code code, boolean recoverable, String message,
                                    @Nullable Throwable cause, int redirectCount) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
        this.recoverable = recoverable;
        this.redirectCount = redirectCount;
    }
}
