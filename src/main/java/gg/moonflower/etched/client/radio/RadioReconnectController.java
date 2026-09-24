package gg.moonflower.etched.client.radio;

import gg.moonflower.etched.client.radio.stream.RadioAudioStream;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Serializes terminal outcomes and owns automatic retry timers. */
public final class RadioReconnectController implements AutoCloseable {

    private static final int DEFAULT_MAX_PENDING_RETRIES = 32;
    private static final long SOUND_STOP_GRACE_MILLIS = 50L;
    private final RadioReconnectPolicy policy;
    private final LongSupplier clock;
    private final java.util.concurrent.Executor ownerExecutor;
    private final RetryScheduler scheduler;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Semaphore retryPermits;
    private final ConcurrentMap<PlaybackSession.Attempt, TimerRegistration> pendingSoundStops =
            new ConcurrentHashMap<>();

    public static RadioReconnectController createDefault(java.util.concurrent.Executor ownerExecutor) {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "Etched radio reconnect");
            thread.setDaemon(true);
            return thread;
        });
        return new RadioReconnectController(new RadioReconnectPolicy(), System::currentTimeMillis,
                ownerExecutor, new ExecutorRetryScheduler(executor));
    }

    RadioReconnectController(RadioReconnectPolicy policy, LongSupplier clock,
                             java.util.concurrent.Executor ownerExecutor, RetryScheduler scheduler) {
        this(policy, clock, ownerExecutor, scheduler, DEFAULT_MAX_PENDING_RETRIES);
    }

    RadioReconnectController(RadioReconnectPolicy policy, LongSupplier clock,
                             java.util.concurrent.Executor ownerExecutor, RetryScheduler scheduler,
                             int maxPendingRetries) {
        if (maxPendingRetries < 1) {
            throw new IllegalArgumentException("Pending reconnect limit must be positive");
        }
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ownerExecutor = Objects.requireNonNull(ownerExecutor, "ownerExecutor");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.retryPermits = new Semaphore(maxPendingRetries);
    }

    public void execute(Runnable action) {
        Objects.requireNonNull(action, "action");
        if (!this.closed.get()) {
            this.ownerExecutor.execute(() -> {
                if (!this.closed.get()) {
                    action.run();
                }
            });
        }
    }

    public void progress(PlaybackSession session, PlaybackSession.Attempt attempt, RadioPlaybackState state,
                         Runnable stateChanged) {
        this.execute(() -> {
            if (session.advance(attempt, state, this.clock.getAsLong())) {
                stateChanged.run();
            }
        });
    }

    public void failure(PlaybackSession session, PlaybackSession.Attempt attempt, Throwable throwable,
                        Consumer<PlaybackSession.Attempt> retryStarter, Runnable stateChanged) {
        Objects.requireNonNull(throwable, "throwable");
        this.execute(() -> this.handle(session, attempt, this.policy.classify(throwable),
                retryStarter, stateChanged));
    }

    public void failure(PlaybackSession session, PlaybackSession.Attempt attempt, RadioFailure failure,
                        Consumer<PlaybackSession.Attempt> retryStarter, Runnable stateChanged) {
        this.execute(() -> this.handle(session, attempt, Optional.of(Objects.requireNonNull(failure, "failure")),
                retryStarter, stateChanged));
    }

    public void termination(PlaybackSession session, PlaybackSession.Attempt attempt,
                            RadioAudioStream.Termination termination,
                            Consumer<PlaybackSession.Attempt> retryStarter, Runnable stateChanged) {
        this.execute(() -> this.handle(session, attempt, this.policy.classify(termination),
                retryStarter, stateChanged));
    }

    public void soundEngineStopped(PlaybackSession session, PlaybackSession.Attempt attempt,
                                   Consumer<PlaybackSession.Attempt> retryStarter, Runnable stateChanged) {
        this.execute(() -> this.deferSoundEngineStop(session, attempt, retryStarter, stateChanged));
    }

    public void sequenceAdvance(PlaybackSession session, PlaybackSession.Attempt attempt,
                                Runnable continuation, Runnable stateChanged) {
        Objects.requireNonNull(continuation, "continuation");
        Objects.requireNonNull(stateChanged, "stateChanged");
        this.execute(() -> {
            this.cancelPendingSoundStop(attempt);
            if (session.advanceToNextTrack(attempt)) {
                stateChanged.run();
                continuation.run();
            }
        });
    }

    private void handle(PlaybackSession session, PlaybackSession.Attempt attempt, Optional<RadioFailure> classified,
                        Consumer<PlaybackSession.Attempt> retryStarter, Runnable stateChanged) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(retryStarter, "retryStarter");
        Objects.requireNonNull(stateChanged, "stateChanged");
        if (classified.isEmpty() || attempt.cancellation().isCancelled()) {
            return;
        }
        this.cancelPendingSoundStop(attempt);
        RadioFailure failure = classified.orElseThrow();
        if (!failure.recoverable()) {
            if (session.fail(attempt, failure)) {
                stateChanged.run();
            }
            return;
        }

        long nowMillis = this.clock.getAsLong();
        Optional<PlaybackSession.ReconnectWait> scheduled = session.scheduleReconnect(
                attempt, failure, nowMillis, this.policy);
        if (scheduled.isEmpty()) {
            return;
        }
        PlaybackSession.ReconnectWait wait = scheduled.orElseThrow();
        if (!this.retryPermits.tryAcquire()) {
            RadioFailure limit = RadioFailure.fatal(RadioFailure.Code.RESOURCE_LIMIT,
                    "Too many radio reconnects are already pending", null);
            if (session.failReconnect(wait, limit)) {
                stateChanged.run();
            }
            return;
        }

        TimerRegistration registration = new TimerRegistration(this.retryPermits::release);
        try {
            registration.attach(this.scheduler.schedule(() -> {
                if (registration.fire()) {
                    this.execute(() -> this.retry(session, wait, retryStarter, stateChanged));
                }
            }, Math.max(0L, wait.retryAtMillis() - nowMillis)));
        } catch (RuntimeException exception) {
            registration.cancel();
            RadioFailure schedulingFailure = RadioFailure.fatal(RadioFailure.Code.RESOURCE_LIMIT,
                    "Radio reconnect scheduler is unavailable", exception);
            if (session.failReconnect(wait, schedulingFailure)) {
                stateChanged.run();
            }
            return;
        }
        wait.cancellation().onCancel(registration::cancel);
        stateChanged.run();
    }

    private void deferSoundEngineStop(PlaybackSession session, PlaybackSession.Attempt attempt,
                                      Consumer<PlaybackSession.Attempt> retryStarter, Runnable stateChanged) {
        if (attempt.cancellation().isCancelled()) {
            return;
        }
        TimerRegistration registration = new TimerRegistration(() -> {
        });
        if (this.pendingSoundStops.putIfAbsent(attempt, registration) != null) {
            return;
        }
        try {
            registration.attach(this.scheduler.schedule(() -> {
                this.execute(() -> {
                    if (this.pendingSoundStops.remove(attempt, registration) && registration.fire()) {
                        this.handle(session, attempt, Optional.of(this.policy.soundEngineStopped()),
                                retryStarter, stateChanged);
                    }
                });
            }, SOUND_STOP_GRACE_MILLIS));
        } catch (RuntimeException exception) {
            this.pendingSoundStops.remove(attempt, registration);
            registration.cancel();
            this.handle(session, attempt, Optional.of(this.policy.soundEngineStopped()),
                    retryStarter, stateChanged);
            return;
        }
        attempt.cancellation().onCancel(() -> {
            if (this.pendingSoundStops.remove(attempt, registration)) {
                registration.cancel();
            }
        });
    }

    private void cancelPendingSoundStop(PlaybackSession.Attempt attempt) {
        TimerRegistration registration = this.pendingSoundStops.remove(attempt);
        if (registration != null) {
            registration.cancel();
        }
    }

    private void retry(PlaybackSession session, PlaybackSession.ReconnectWait wait,
                       Consumer<PlaybackSession.Attempt> retryStarter, Runnable stateChanged) {
        Optional<PlaybackSession.Attempt> retried = session.retry(wait);
        if (retried.isEmpty()) {
            return;
        }
        PlaybackSession.Attempt attempt = retried.orElseThrow();
        try {
            retryStarter.accept(attempt);
        } catch (RuntimeException exception) {
            this.handle(session, attempt, this.policy.classify(exception), retryStarter, stateChanged);
            return;
        }
        stateChanged.run();
    }

    @Override
    public void close() {
        if (this.closed.compareAndSet(false, true)) {
            this.pendingSoundStops.values().forEach(TimerRegistration::cancel);
            this.pendingSoundStops.clear();
            this.scheduler.close();
        }
    }

    interface RetryScheduler extends AutoCloseable {

        Cancellable schedule(Runnable task, long delayMillis);

        @Override
        default void close() {
        }
    }

    @FunctionalInterface
    interface Cancellable {

        void cancel();
    }

    private record ExecutorRetryScheduler(ScheduledExecutorService executor) implements RetryScheduler {

        private ExecutorRetryScheduler {
            Objects.requireNonNull(executor, "executor");
        }

        @Override
        public Cancellable schedule(Runnable task, long delayMillis) {
            var future = this.executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        }

        @Override
        public void close() {
            this.executor.shutdownNow();
        }
    }

    private static final class TimerRegistration {

        private final Runnable released;
        private Cancellable cancellable;
        private boolean completed;

        private TimerRegistration(Runnable released) {
            this.released = released;
        }

        private void attach(Cancellable cancellable) {
            boolean cancel;
            synchronized (this) {
                cancel = this.completed;
                if (!cancel) {
                    this.cancellable = Objects.requireNonNull(cancellable, "cancellable");
                }
            }
            if (cancel) {
                cancellable.cancel();
            }
        }

        private boolean fire() {
            synchronized (this) {
                if (this.completed) {
                    return false;
                }
                this.completed = true;
            }
            this.released.run();
            return true;
        }

        private void cancel() {
            Cancellable current;
            synchronized (this) {
                if (this.completed) {
                    return;
                }
                this.completed = true;
                current = this.cancellable;
            }
            if (current != null) {
                current.cancel();
            }
            this.released.run();
        }
    }
}
