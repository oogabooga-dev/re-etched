package gg.moonflower.etched.client.radio.source;

import gg.moonflower.etched.client.radio.RadioFailure;
import gg.moonflower.etched.client.radio.net.AudioHttpResponse;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

final class RadioHttpStatus {

    private RadioHttpStatus() {
    }

    static void requireSuccess(AudioHttpResponse response, String description)
            throws RadioSourceException {
        int status = response.statusCode();
        if (status == 200) {
            return;
        }
        boolean recoverable = status == 408 || status == 429 || status == 500
                || status == 502 || status == 503 || status == 504;
        long retryAfterMillis = status == 429
                ? retryAfterMillis(response) : RadioFailure.NO_RETRY_AFTER;
        throw new RadioSourceException(RadioFailure.Code.HTTP_STATUS, recoverable,
                description + " returned HTTP status " + status, null, retryAfterMillis, status);
    }

    private static long retryAfterMillis(AudioHttpResponse response) {
        String value = response.firstHeader("retry-after").orElse(null);
        if (value == null) {
            return RadioFailure.NO_RETRY_AFTER;
        }
        try {
            long seconds = Long.parseLong(value.trim());
            if (seconds < 0) {
                return RadioFailure.NO_RETRY_AFTER;
            }
            return Math.min(seconds, 30L) * 1_000L;
        } catch (NumberFormatException ignored) {
            try {
                Instant retryAt = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant();
                long delay = Duration.between(Instant.now(), retryAt).toMillis();
                return Math.max(0L, Math.min(delay, 30_000L));
            } catch (DateTimeParseException | ArithmeticException invalidDate) {
                return RadioFailure.NO_RETRY_AFTER;
            }
        }
    }
}
