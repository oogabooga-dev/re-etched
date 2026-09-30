package gg.moonflower.etched.client.radio.stream;

import gg.moonflower.etched.common.audio.RadioFailure;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Objects;

/** A stream-pipeline failure with an explicit retry disposition. */
public final class RadioStreamException extends IOException {

    private final RadioFailure.Code code;
    private final boolean recoverable;

    public RadioStreamException(RadioFailure.Code code, boolean recoverable, String message,
                                @Nullable Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
        this.recoverable = recoverable;
    }

    public RadioFailure toFailure() {
        return new RadioFailure(this.code, this.recoverable, this.getMessage(), this);
    }
}
