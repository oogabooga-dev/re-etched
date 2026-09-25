package gg.moonflower.etched.client.radio;

import gg.moonflower.etched.client.radio.net.RadioTransportException;
import gg.moonflower.etched.client.radio.source.RadioSourceException;
import gg.moonflower.etched.client.radio.stream.PlaybackAudioStream;
import gg.moonflower.etched.client.radio.stream.RadioStreamException;

import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/** Classifies terminal outcomes and calculates bounded reconnect delays. */
public final class RadioReconnectPolicy {

    static final long MAX_DELAY_MILLIS = 30_000L;
    private static final long[] DEFAULT_DELAYS_MILLIS = {1_000L, 2_000L, 5_000L, 10_000L, 20_000L, 30_000L};
    private static final long DEFAULT_SUSTAINED_PLAYBACK_MILLIS = 30_000L;
    private static final double DEFAULT_JITTER_FRACTION = 0.2D;

    private final long[] delaysMillis;
    private final long sustainedPlaybackMillis;
    private final double jitterFraction;
    private final DoubleSupplier random;

    public RadioReconnectPolicy() {
        this(DEFAULT_DELAYS_MILLIS, DEFAULT_SUSTAINED_PLAYBACK_MILLIS,
                DEFAULT_JITTER_FRACTION, () -> ThreadLocalRandom.current().nextDouble());
    }

    RadioReconnectPolicy(long[] delaysMillis, long sustainedPlaybackMillis,
                         double jitterFraction, DoubleSupplier random) {
        if (delaysMillis.length == 0) {
            throw new IllegalArgumentException("At least one reconnect delay is required");
        }
        this.delaysMillis = delaysMillis.clone();
        for (long delay : this.delaysMillis) {
            if (delay < 0 || delay > MAX_DELAY_MILLIS) {
                throw new IllegalArgumentException("Reconnect delays must be between zero and 30 seconds");
            }
        }
        if (sustainedPlaybackMillis < 0) {
            throw new IllegalArgumentException("Sustained playback duration must be non-negative");
        }
        if (!Double.isFinite(jitterFraction) || jitterFraction < 0.0D || jitterFraction > 1.0D) {
            throw new IllegalArgumentException("Jitter fraction must be between zero and one");
        }
        this.sustainedPlaybackMillis = sustainedPlaybackMillis;
        this.jitterFraction = jitterFraction;
        this.random = java.util.Objects.requireNonNull(random, "random");
    }

    public long retryDelayMillis(int attemptNumber, RadioFailure failure) {
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("Attempt number must be positive");
        }
        java.util.Objects.requireNonNull(failure, "failure");
        long base = this.delaysMillis[Math.min(attemptNumber - 1, this.delaysMillis.length - 1)];
        double sample = this.random.getAsDouble();
        if (!Double.isFinite(sample)) {
            sample = 0.5D;
        }
        sample = Math.max(0.0D, Math.min(1.0D, sample));
        double factor = 1.0D + (sample * 2.0D - 1.0D) * this.jitterFraction;
        long jittered = Math.max(0L, Math.min(MAX_DELAY_MILLIS, Math.round(base * factor)));
        if (failure.retryAfterMillis() == RadioFailure.NO_RETRY_AFTER) {
            return jittered;
        }
        return Math.max(jittered, Math.min(failure.retryAfterMillis(), MAX_DELAY_MILLIS));
    }

    public boolean isSustainedPlayback(long playbackMillis) {
        return playbackMillis >= this.sustainedPlaybackMillis;
    }

    public Optional<RadioFailure> classify(PlaybackAudioStream.Termination termination) {
        java.util.Objects.requireNonNull(termination, "termination");
        return switch (termination.state()) {
            case EOF -> Optional.of(RadioFailure.recoverable(RadioFailure.Code.UNEXPECTED_EOF,
                    "Radio stream ended unexpectedly", null));
            case FAILED -> this.classify(termination.failure());
            case CANCELLED, CLOSED -> Optional.empty();
        };
    }

    public Optional<RadioFailure> classify(Throwable throwable) {
        if (throwable == null) {
            return Optional.of(RadioFailure.fatal(RadioFailure.Code.UNKNOWN,
                    "Radio playback failed without an error", null));
        }
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = throwable;
        IOException decoderFailure = null;
        while (current != null && visited.add(current)) {
            if (current instanceof CancellationException || current instanceof InterruptedException) {
                return Optional.empty();
            }
            if (current instanceof RadioSourceException source) {
                return Optional.of(source.toFailure());
            }
            if (current instanceof RadioTransportException transport) {
                return Optional.of(transport.toFailure());
            }
            if (current instanceof RadioStreamException stream) {
                return Optional.of(stream.toFailure());
            }
            if (current instanceof RejectedExecutionException) {
                return Optional.of(RadioFailure.recoverable(RadioFailure.Code.RESOURCE_LIMIT,
                        "Radio playback workers are busy", current));
            }
            if (current instanceof IOException exception && decoderFailure == null) {
                decoderFailure = exception;
            }
            current = current.getCause();
        }
        if (decoderFailure != null) {
            return Optional.of(RadioFailure.fatal(RadioFailure.Code.DECODER_FAILURE,
                    "Radio decoder failed", decoderFailure));
        }
        return Optional.of(RadioFailure.fatal(RadioFailure.Code.UNKNOWN,
                "Unexpected radio playback failure", throwable));
    }

    public RadioFailure soundEngineStopped() {
        return RadioFailure.recoverable(RadioFailure.Code.SOUND_ENGINE_STOPPED,
                "Minecraft sound engine stopped the radio", null);
    }
}
