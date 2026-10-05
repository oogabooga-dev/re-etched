package gg.moonflower.etched.common.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RadioFailureTest {

    @Test
    void preservesClassificationCauseAndRetryDelay() {
        Exception cause = new Exception("test failure");
        RadioFailure failure = RadioFailure.recoverable(
                RadioFailure.Code.HTTP_STATUS, "limited", cause, 7000L);

        assertEquals(RadioFailure.Code.HTTP_STATUS, failure.code());
        assertTrue(failure.recoverable());
        assertEquals("limited", failure.message());
        assertSame(cause, failure.cause());
        assertEquals(7000L, failure.retryAfterMillis());
        assertFalse(RadioFailure.fatal(RadioFailure.Code.BLOCKED_ADDRESS, "blocked", null).recoverable());
    }

    @Test
    void defaultDelayAndValidationRemainUnchanged() {
        RadioFailure failure = RadioFailure.recoverable(RadioFailure.Code.UNKNOWN, "unknown", null);
        assertEquals(RadioFailure.NO_RETRY_AFTER, failure.retryAfterMillis());
        assertThrows(IllegalArgumentException.class,
                () -> new RadioFailure(RadioFailure.Code.UNKNOWN, true, "unknown", null, -2L));
        assertThrows(NullPointerException.class,
                () -> new RadioFailure(null, true, "unknown", null));
    }
}
