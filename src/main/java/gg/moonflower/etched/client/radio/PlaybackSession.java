package gg.moonflower.etched.client.radio;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;

/**
 * Thread-safe lifecycle state for one client-side playback owner.
 *
 * <p>Every start or retry creates a new generation. Asynchronous work must
 * include that generation when reporting progress so stale work is ignored.</p>
 */
public final class PlaybackSession {

    private long generation;
    private String source = "";
    private RadioPlaybackState state = RadioPlaybackState.STOPPED;
    private RadioFailure failure;
    private AudioCancellation cancellation;
    private String streamTitle;
    private PendingStreamTitle pendingStreamTitle;
    private int attemptNumber;
    private long playingSinceMillis = -1L;
    private long nextRetryAtMillis = -1L;

    public Attempt start(String source) {
        Objects.requireNonNull(source, "source");
        if (source.isBlank()) {
            throw new IllegalArgumentException("Radio source must not be blank");
        }

        AudioCancellation previous;
        Attempt attempt;
        synchronized (this) {
            previous = this.cancellation;
            attempt = this.beginAttempt(source, 1);
        }
        cancel(previous);
        return attempt;
    }

    public synchronized boolean advance(long generation, RadioPlaybackState nextState) {
        return this.advance(generation, null, nextState, System.currentTimeMillis());
    }

    public synchronized boolean advance(Attempt attempt, RadioPlaybackState nextState, long nowMillis) {
        Objects.requireNonNull(attempt, "attempt");
        return this.advance(attempt.generation(), attempt.cancellation(), nextState, nowMillis);
    }

    /** Advances the exact active finite program to its next independently opened track. */
    public synchronized boolean advanceToNextTrack(Attempt attempt) {
        Objects.requireNonNull(attempt, "attempt");
        if (!this.isCurrentAttempt(attempt)) {
            return false;
        }
        if (this.state != RadioPlaybackState.PLAYING) {
            throw new IllegalStateException("Cannot advance a radio track from " + this.state);
        }
        this.state = RadioPlaybackState.CONNECTING;
        this.streamTitle = null;
        this.pendingStreamTitle = null;
        return true;
    }

    private boolean advance(long generation, @Nullable AudioCancellation expectedCancellation,
                            RadioPlaybackState nextState, long nowMillis) {
        Objects.requireNonNull(nextState, "nextState");
        if (nextState != RadioPlaybackState.CONNECTING
                && nextState != RadioPlaybackState.BUFFERING
                && nextState != RadioPlaybackState.PLAYING) {
            throw new IllegalArgumentException("Not a progress state: " + nextState);
        }
        if (expectedCancellation == null
                ? !this.isCurrentAttempt(generation)
                : !this.isCurrentAttempt(generation, expectedCancellation)) {
            return false;
        }
        if (!isAllowedProgression(this.state, nextState)) {
            throw new IllegalStateException("Cannot transition radio from " + this.state + " to " + nextState);
        }

        this.state = nextState;
        if (nextState == RadioPlaybackState.PLAYING && this.playingSinceMillis < 0L) {
            this.playingSinceMillis = nowMillis;
        }
        return true;
    }

    public Optional<ReconnectWait> scheduleReconnect(long generation, RadioFailure failure) {
        Attempt attempt;
        synchronized (this) {
            if (!this.isCurrentAttempt(generation)) {
                return Optional.empty();
            }
            attempt = new Attempt(this.generation, this.source, this.cancellation);
        }
        return this.scheduleReconnect(attempt, failure, System.currentTimeMillis(), new RadioReconnectPolicy());
    }

    public Optional<ReconnectWait> scheduleReconnect(Attempt attempt, RadioFailure failure,
                                                     long nowMillis, RadioReconnectPolicy policy) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(failure, "failure");
        Objects.requireNonNull(policy, "policy");
        if (!failure.recoverable()) {
            throw new IllegalArgumentException("Reconnect requires a recoverable failure");
        }

        AudioCancellation previous;
        ReconnectWait wait;
        synchronized (this) {
            if (!this.isCurrentAttempt(attempt)) {
                return Optional.empty();
            }

            if (this.playingSinceMillis >= 0L
                    && policy.isSustainedPlayback(Math.max(0L, nowMillis - this.playingSinceMillis))) {
                this.attemptNumber = 1;
            }
            long delayMillis = policy.retryDelayMillis(this.attemptNumber, failure);
            this.state = RadioPlaybackState.RECONNECT_WAIT;
            this.failure = failure;
            this.pendingStreamTitle = null;
            this.playingSinceMillis = -1L;
            this.nextRetryAtMillis = saturatedAdd(nowMillis, delayMillis);
            previous = this.cancellation;
            this.cancellation = new AudioCancellation();
            wait = new ReconnectWait(this.generation, this.cancellation,
                    this.attemptNumber, this.nextRetryAtMillis);
        }
        cancel(previous);
        return Optional.of(wait);
    }

    public boolean fail(long generation, RadioFailure failure) {
        return this.fail(generation, null, failure);
    }

    public boolean fail(Attempt attempt, RadioFailure failure) {
        Objects.requireNonNull(attempt, "attempt");
        return this.fail(attempt.generation(), attempt.cancellation(), failure);
    }

    /** Completes the exact active finite program without scheduling a reconnect. */
    public boolean complete(Attempt attempt) {
        Objects.requireNonNull(attempt, "attempt");
        AudioCancellation previous;
        synchronized (this) {
            if (!this.isCurrentAttempt(attempt)) {
                return false;
            }
            this.state = RadioPlaybackState.STOPPED;
            this.failure = null;
            this.streamTitle = null;
            this.pendingStreamTitle = null;
            this.attemptNumber = 0;
            this.playingSinceMillis = -1L;
            this.nextRetryAtMillis = -1L;
            previous = this.cancellation;
            this.cancellation = null;
        }
        cancel(previous);
        return true;
    }

    private boolean fail(long generation, @Nullable AudioCancellation expectedCancellation, RadioFailure failure) {
        Objects.requireNonNull(failure, "failure");
        if (failure.recoverable()) {
            throw new IllegalArgumentException("Failed state requires a fatal failure");
        }
        return this.finishAttempt(generation, expectedCancellation, RadioPlaybackState.FAILED, failure);
    }

    /** Starts an explicit retry and resets automatic backoff. */
    public Optional<Attempt> retry(long expectedGeneration) {
        AudioCancellation previous;
        Attempt attempt;
        synchronized (this) {
            if (this.generation != expectedGeneration
                    || this.state != RadioPlaybackState.RECONNECT_WAIT && this.state != RadioPlaybackState.FAILED) {
                return Optional.empty();
            }
            previous = this.cancellation;
            attempt = this.beginAttempt(this.source, 1);
        }
        cancel(previous);
        return Optional.of(attempt);
    }

    /** Starts the retry owned by the exact reconnect wait token. */
    public Optional<Attempt> retry(ReconnectWait wait) {
        Objects.requireNonNull(wait, "wait");
        AudioCancellation previous;
        Attempt attempt;
        synchronized (this) {
            if (!this.isCurrentWait(wait)) {
                return Optional.empty();
            }
            previous = this.cancellation;
            int nextAttempt = wait.attemptNumber() == Integer.MAX_VALUE
                    ? Integer.MAX_VALUE : wait.attemptNumber() + 1;
            attempt = this.beginAttempt(this.source, nextAttempt);
        }
        cancel(previous);
        return Optional.of(attempt);
    }

    public boolean failReconnect(ReconnectWait wait, RadioFailure failure) {
        Objects.requireNonNull(wait, "wait");
        Objects.requireNonNull(failure, "failure");
        if (failure.recoverable()) {
            throw new IllegalArgumentException("Failed state requires a fatal failure");
        }
        AudioCancellation previous;
        synchronized (this) {
            if (!this.isCurrentWait(wait)) {
                return false;
            }
            this.state = RadioPlaybackState.FAILED;
            this.failure = failure;
            this.nextRetryAtMillis = -1L;
            previous = this.cancellation;
            this.cancellation = null;
        }
        cancel(previous);
        return true;
    }

    public boolean stop() {
        AudioCancellation previous;
        synchronized (this) {
            if (this.state == RadioPlaybackState.STOPPED) {
                return false;
            }

            this.generation++;
            this.state = RadioPlaybackState.STOPPED;
            this.failure = null;
            this.streamTitle = null;
            this.pendingStreamTitle = null;
            this.attemptNumber = 0;
            this.playingSinceMillis = -1L;
            this.nextRetryAtMillis = -1L;
            previous = this.cancellation;
            this.cancellation = null;
        }
        cancel(previous);
        return true;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(this.generation, this.source, this.state, this.failure, this.streamTitle,
                this.attemptNumber, this.nextRetryAtMillis);
    }

    /** Coalesces metadata produced by the exact currently active stream attempt. */
    public synchronized boolean offerStreamTitle(Attempt attempt, String streamTitle) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(streamTitle, "streamTitle");
        if (!this.isCurrentAttempt(attempt)) {
            return false;
        }
        this.pendingStreamTitle = new PendingStreamTitle(
                attempt.generation(), attempt.cancellation(), streamTitle);
        return true;
    }

    /** Applies at most one latest metadata update from the client tick. */
    public synchronized boolean applyPendingStreamTitle() {
        PendingStreamTitle pending = this.pendingStreamTitle;
        this.pendingStreamTitle = null;
        if (pending == null || !this.isCurrentAttempt(pending.generation(), pending.cancellation())) {
            return false;
        }
        String nextTitle = pending.streamTitle().isEmpty() ? null : pending.streamTitle();
        if (Objects.equals(this.streamTitle, nextTitle)) {
            return false;
        }
        this.streamTitle = nextTitle;
        return true;
    }

    private boolean finishAttempt(long generation, @Nullable AudioCancellation expectedCancellation,
                                  RadioPlaybackState nextState, RadioFailure failure) {
        AudioCancellation previous;
        synchronized (this) {
            if (expectedCancellation == null
                    ? !this.isCurrentAttempt(generation)
                    : !this.isCurrentAttempt(generation, expectedCancellation)) {
                return false;
            }

            this.state = nextState;
            this.failure = failure;
            this.pendingStreamTitle = null;
            this.playingSinceMillis = -1L;
            this.nextRetryAtMillis = -1L;
            previous = this.cancellation;
            this.cancellation = null;
        }
        cancel(previous);
        return true;
    }

    private boolean isCurrent(long generation) {
        return this.generation == generation
                && this.cancellation != null
                && !this.cancellation.isCancelled();
    }

    private boolean isCurrentAttempt(long generation) {
        return this.isCurrent(generation) && switch (this.state) {
            case RESOLVING, CONNECTING, BUFFERING, PLAYING -> true;
            default -> false;
        };
    }

    private boolean isCurrentAttempt(Attempt attempt) {
        return this.isCurrentAttempt(attempt.generation(), attempt.cancellation());
    }

    private boolean isCurrentAttempt(long generation, AudioCancellation cancellation) {
        return this.generation == generation
                && this.cancellation == cancellation
                && !cancellation.isCancelled()
                && switch (this.state) {
            case RESOLVING, CONNECTING, BUFFERING, PLAYING -> true;
            default -> false;
        };
    }

    private Attempt beginAttempt(String source, int attemptNumber) {
        this.generation++;
        this.source = source;
        this.state = RadioPlaybackState.RESOLVING;
        this.failure = null;
        this.streamTitle = null;
        this.pendingStreamTitle = null;
        this.attemptNumber = attemptNumber;
        this.playingSinceMillis = -1L;
        this.nextRetryAtMillis = -1L;
        this.cancellation = new AudioCancellation();
        return new Attempt(this.generation, this.source, this.cancellation);
    }

    private boolean isCurrentWait(ReconnectWait wait) {
        return this.generation == wait.generation()
                && this.cancellation == wait.cancellation()
                && !wait.cancellation().isCancelled()
                && this.state == RadioPlaybackState.RECONNECT_WAIT;
    }

    private static long saturatedAdd(long value, long increment) {
        if (increment > 0L && value > Long.MAX_VALUE - increment) {
            return Long.MAX_VALUE;
        }
        return value + increment;
    }

    private static boolean isAllowedProgression(RadioPlaybackState current, RadioPlaybackState next) {
        return switch (current) {
            case RESOLVING -> next == RadioPlaybackState.CONNECTING;
            case CONNECTING -> next == RadioPlaybackState.BUFFERING;
            case BUFFERING -> next == RadioPlaybackState.PLAYING;
            default -> false;
        };
    }

    private static void cancel(@Nullable AudioCancellation cancellation) {
        if (cancellation != null) {
            cancellation.cancel();
        }
    }

    public record Attempt(long generation, String source, AudioCancellation cancellation) {

        public Attempt {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(cancellation, "cancellation");
        }
    }

    public record ReconnectWait(long generation, AudioCancellation cancellation,
                                int attemptNumber, long retryAtMillis) {

        public ReconnectWait(long generation, AudioCancellation cancellation) {
            this(generation, cancellation, 1, -1L);
        }

        public ReconnectWait {
            Objects.requireNonNull(cancellation, "cancellation");
            if (attemptNumber < 1) {
                throw new IllegalArgumentException("Attempt number must be positive");
            }
        }
    }

    public record Snapshot(long generation, String source, RadioPlaybackState state,
                           @Nullable RadioFailure failure, @Nullable String streamTitle,
                           int attemptNumber, long nextRetryAtMillis) {

        public Snapshot(long generation, String source, RadioPlaybackState state,
                        @Nullable RadioFailure failure, @Nullable String streamTitle) {
            this(generation, source, state, failure, streamTitle,
                    state == RadioPlaybackState.STOPPED ? 0 : 1, -1L);
        }

        public Snapshot {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(state, "state");
            if (attemptNumber < 0) {
                throw new IllegalArgumentException("Attempt number must not be negative");
            }
        }
    }

    private record PendingStreamTitle(long generation, AudioCancellation cancellation, String streamTitle) {

        private PendingStreamTitle {
            Objects.requireNonNull(cancellation, "cancellation");
            Objects.requireNonNull(streamTitle, "streamTitle");
        }
    }
}
