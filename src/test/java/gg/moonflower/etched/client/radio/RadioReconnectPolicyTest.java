package gg.moonflower.etched.client.radio;

import gg.moonflower.etched.client.radio.source.RadioSourceException;
import gg.moonflower.etched.client.radio.stream.PlaybackAudioStream;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RadioReconnectPolicyTest {

    private static final RadioFailure TRANSIENT = RadioFailure.recoverable(
            RadioFailure.Code.READ_TIMEOUT, "Read timed out", null);

    @Test
    void followsTheBoundedBackoffSequence() {
        RadioReconnectPolicy policy = policy(0.5D);

        assertEquals(1_000L, policy.retryDelayMillis(1, TRANSIENT));
        assertEquals(2_000L, policy.retryDelayMillis(2, TRANSIENT));
        assertEquals(5_000L, policy.retryDelayMillis(3, TRANSIENT));
        assertEquals(10_000L, policy.retryDelayMillis(4, TRANSIENT));
        assertEquals(20_000L, policy.retryDelayMillis(5, TRANSIENT));
        assertEquals(30_000L, policy.retryDelayMillis(6, TRANSIENT));
        assertEquals(30_000L, policy.retryDelayMillis(Integer.MAX_VALUE, TRANSIENT));
    }

    @Test
    void appliesBoundedJitterAndRetryAfterFloor() {
        assertEquals(800L, policy(0.0D).retryDelayMillis(1, TRANSIENT));
        assertEquals(1_200L, policy(1.0D).retryDelayMillis(1, TRANSIENT));
        RadioFailure retryAfter = RadioFailure.recoverable(
                RadioFailure.Code.HTTP_STATUS, "Rate limited", null, 5_000L);

        assertEquals(5_000L, policy(0.0D).retryDelayMillis(1, retryAfter));
        assertEquals(30_000L, policy(1.0D).retryDelayMillis(6, retryAfter));
    }

    @Test
    void preservesTypedFailuresThroughAsyncWrappers() {
        RadioSourceException source = new RadioSourceException(
                RadioFailure.Code.HTTP_STATUS, true, "Unavailable", null, 4_000L);

        RadioFailure failure = policy(0.5D).classify(new CompletionException(source)).orElseThrow();

        assertEquals(RadioFailure.Code.HTTP_STATUS, failure.code());
        assertTrue(failure.recoverable());
        assertEquals(4_000L, failure.retryAfterMillis());
    }

    @Test
    void classifiesOnlyExplicitlyRecoverableTerminalOutcomes() {
        RadioReconnectPolicy policy = policy(0.5D);
        RadioFailure eof = policy.classify(new PlaybackAudioStream.Termination(
                PlaybackAudioStream.TerminalState.EOF, null)).orElseThrow();
        RadioFailure decoder = policy.classify(new CompletionException(new IOException("bad frame")))
                .orElseThrow();
        RadioFailure busy = policy.classify(new RejectedExecutionException("busy")).orElseThrow();

        assertEquals(RadioFailure.Code.UNEXPECTED_EOF, eof.code());
        assertTrue(eof.recoverable());
        assertEquals(RadioFailure.Code.DECODER_FAILURE, decoder.code());
        assertFalse(decoder.recoverable());
        assertEquals(RadioFailure.Code.RESOURCE_LIMIT, busy.code());
        assertTrue(busy.recoverable());
        assertTrue(policy.classify(new CancellationException()).isEmpty());
        assertTrue(policy.classify(new PlaybackAudioStream.Termination(
                PlaybackAudioStream.TerminalState.CLOSED, null)).isEmpty());
    }

    @Test
    void recognizesSustainedPlaybackAtTheBoundary() {
        RadioReconnectPolicy policy = policy(0.5D);

        assertFalse(policy.isSustainedPlayback(29_999L));
        assertTrue(policy.isSustainedPlayback(30_000L));
    }

    @Test
    void treatsAnUnexpectedSoundEngineStopAsRecoverable() {
        RadioFailure failure = policy(0.5D).soundEngineStopped();

        assertEquals(RadioFailure.Code.SOUND_ENGINE_STOPPED, failure.code());
        assertTrue(failure.recoverable());
    }

    private static RadioReconnectPolicy policy(double random) {
        return new RadioReconnectPolicy(
                new long[]{1_000L, 2_000L, 5_000L, 10_000L, 20_000L, 30_000L},
                30_000L, 0.2D, () -> random);
    }
}
