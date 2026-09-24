package gg.moonflower.etched.client.radio;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RadioReconnectControllerTest {

    private static final RadioReconnectPolicy NO_JITTER = new RadioReconnectPolicy(
            new long[]{1_000L, 2_000L, 5_000L, 10_000L, 20_000L, 30_000L},
            30_000L, 0.0D, () -> 0.5D);

    @Test
    void recoverableFailureSchedulesAndStartsTheExactRetry() {
        AtomicLong clock = new AtomicLong(10_000L);
        ManualScheduler scheduler = new ManualScheduler();
        RadioReconnectController controller = new RadioReconnectController(
                NO_JITTER, clock::get, Runnable::run, scheduler);
        PlaybackSession session = new PlaybackSession();
        PlaybackSession.Attempt attempt = session.start("https://radio.example/live");
        List<PlaybackSession.Attempt> retries = new ArrayList<>();
        AtomicInteger stateChanges = new AtomicInteger();

        controller.failure(session, attempt,
                new gg.moonflower.etched.client.radio.net.RadioTransportException(
                        RadioFailure.Code.CONNECT_TIMEOUT, true, "Timed out", null),
                retries::add, stateChanges::incrementAndGet);

        assertEquals(RadioPlaybackState.RECONNECT_WAIT, session.snapshot().state());
        assertEquals(11_000L, session.snapshot().nextRetryAtMillis());
        assertEquals(1_000L, scheduler.tasks.get(0).delayMillis);
        assertEquals(1, stateChanges.get());

        scheduler.fire(0);

        assertEquals(1, retries.size());
        assertEquals(RadioPlaybackState.RESOLVING, session.snapshot().state());
        assertEquals(2, session.snapshot().attemptNumber());
        assertEquals(2, stateChanges.get());
    }

    @Test
    void stopCancelsTimerAndPreventsLateRetry() {
        ManualScheduler scheduler = new ManualScheduler();
        RadioReconnectController controller = new RadioReconnectController(
                NO_JITTER, () -> 0L, Runnable::run, scheduler);
        PlaybackSession session = new PlaybackSession();
        PlaybackSession.Attempt attempt = session.start("https://radio.example/live");
        List<PlaybackSession.Attempt> retries = new ArrayList<>();

        controller.failure(session, attempt,
                new gg.moonflower.etched.client.radio.net.RadioTransportException(
                        RadioFailure.Code.READ_TIMEOUT, true, "Timed out", null),
                retries::add, () -> {
                });
        session.stop();
        scheduler.fire(0);

        assertTrue(scheduler.tasks.get(0).cancelled);
        assertTrue(retries.isEmpty());
        assertEquals(RadioPlaybackState.STOPPED, session.snapshot().state());
    }

    @Test
    void fatalDecoderFailureNeverSchedulesRetry() {
        ManualScheduler scheduler = new ManualScheduler();
        RadioReconnectController controller = new RadioReconnectController(
                NO_JITTER, () -> 0L, Runnable::run, scheduler);
        PlaybackSession session = new PlaybackSession();
        PlaybackSession.Attempt attempt = session.start("https://radio.example/live");

        controller.failure(session, attempt, new IOException("Invalid frame"), ignored -> {
        }, () -> {
        });

        assertEquals(RadioPlaybackState.FAILED, session.snapshot().state());
        assertEquals(RadioFailure.Code.DECODER_FAILURE, session.snapshot().failure().code());
        assertTrue(scheduler.tasks.isEmpty());
    }

    @Test
    void backgroundOutcomeIsMarshalledToTheOwnerExecutor() {
        Queue<Runnable> ownerTasks = new ArrayDeque<>();
        ManualScheduler scheduler = new ManualScheduler();
        RadioReconnectController controller = new RadioReconnectController(
                NO_JITTER, () -> 0L, ownerTasks::add, scheduler);
        PlaybackSession session = new PlaybackSession();
        PlaybackSession.Attempt attempt = session.start("https://radio.example/live");

        controller.failure(session, attempt, new IOException("Invalid frame"), ignored -> {
        }, () -> {
        });

        assertEquals(RadioPlaybackState.RESOLVING, session.snapshot().state());
        ownerTasks.remove().run();
        assertEquals(RadioPlaybackState.FAILED, session.snapshot().state());
    }

    @Test
    void unavailableTimerBecomesFatalInsteadOfRetryStorming() {
        ManualScheduler scheduler = new ManualScheduler();
        scheduler.reject = true;
        RadioReconnectController controller = new RadioReconnectController(
                NO_JITTER, () -> 0L, Runnable::run, scheduler);
        PlaybackSession session = new PlaybackSession();
        PlaybackSession.Attempt attempt = session.start("https://radio.example/live");

        controller.failure(session, attempt,
                new gg.moonflower.etched.client.radio.net.RadioTransportException(
                        RadioFailure.Code.CONNECT_TIMEOUT, true, "Timed out", null),
                ignored -> {
                }, () -> {
                });

        assertEquals(RadioPlaybackState.FAILED, session.snapshot().state());
        assertFalse(session.snapshot().failure().recoverable());
        assertEquals(RadioFailure.Code.RESOURCE_LIMIT, session.snapshot().failure().code());
    }

    @Test
    void alreadyFiredTimerCannotRetryAfterStop() {
        Queue<Runnable> ownerTasks = new ArrayDeque<>();
        ManualScheduler scheduler = new ManualScheduler();
        RadioReconnectController controller = new RadioReconnectController(
                NO_JITTER, () -> 0L, ownerTasks::add, scheduler);
        PlaybackSession session = new PlaybackSession();
        PlaybackSession.Attempt attempt = session.start("https://radio.example/live");
        List<PlaybackSession.Attempt> retries = new ArrayList<>();
        controller.failure(session, attempt,
                new gg.moonflower.etched.client.radio.net.RadioTransportException(
                        RadioFailure.Code.CONNECT_TIMEOUT, true, "Timed out", null),
                retries::add, () -> {
                });
        ownerTasks.remove().run();
        scheduler.fireRaw(0);

        session.stop();
        ownerTasks.remove().run();

        assertTrue(retries.isEmpty());
        assertEquals(RadioPlaybackState.STOPPED, session.snapshot().state());
    }

    @Test
    void manualRetryWinsAgainstAlreadyFiredAutomaticTimer() {
        Queue<Runnable> ownerTasks = new ArrayDeque<>();
        ManualScheduler scheduler = new ManualScheduler();
        RadioReconnectController controller = new RadioReconnectController(
                NO_JITTER, () -> 0L, ownerTasks::add, scheduler);
        PlaybackSession session = new PlaybackSession();
        PlaybackSession.Attempt first = session.start("https://radio.example/live");
        List<PlaybackSession.Attempt> automatic = new ArrayList<>();
        controller.failure(session, first,
                new gg.moonflower.etched.client.radio.net.RadioTransportException(
                        RadioFailure.Code.CONNECT_TIMEOUT, true, "Timed out", null),
                automatic::add, () -> {
                });
        ownerTasks.remove().run();
        scheduler.fireRaw(0);

        PlaybackSession.Attempt manual = session.retry(first.generation()).orElseThrow();
        ownerTasks.remove().run();

        assertTrue(automatic.isEmpty());
        assertEquals(manual.generation(), session.snapshot().generation());
        assertEquals(1, session.snapshot().attemptNumber());
    }

    @Test
    void duplicateTerminalSignalsCannotScheduleMultipleRetries() {
        ManualScheduler scheduler = new ManualScheduler();
        RadioReconnectController controller = new RadioReconnectController(
                NO_JITTER, () -> 0L, Runnable::run, scheduler);
        PlaybackSession session = new PlaybackSession();
        PlaybackSession.Attempt attempt = session.start("https://radio.example/live");

        controller.termination(session, attempt, new gg.moonflower.etched.client.radio.stream.RadioAudioStream.Termination(
                gg.moonflower.etched.client.radio.stream.RadioAudioStream.TerminalState.EOF, null),
                ignored -> {
                }, () -> {
                });
        controller.failure(session, attempt, new IOException("Late decoder error"), ignored -> {
        }, () -> {
        });
        controller.soundEngineStopped(session, attempt, ignored -> {
        }, () -> {
        });

        assertEquals(1, scheduler.tasks.size());
        assertEquals(RadioPlaybackState.RECONNECT_WAIT, session.snapshot().state());
    }

    @Test
    void closeSuppressesRetryAlreadyQueuedOnOwnerExecutor() {
        Queue<Runnable> ownerTasks = new ArrayDeque<>();
        ManualScheduler scheduler = new ManualScheduler();
        RadioReconnectController controller = new RadioReconnectController(
                NO_JITTER, () -> 0L, ownerTasks::add, scheduler);
        PlaybackSession session = new PlaybackSession();
        PlaybackSession.Attempt attempt = session.start("https://radio.example/live");
        List<PlaybackSession.Attempt> retries = new ArrayList<>();
        controller.failure(session, attempt,
                new gg.moonflower.etched.client.radio.net.RadioTransportException(
                        RadioFailure.Code.CONNECT_TIMEOUT, true, "Timed out", null),
                retries::add, () -> {
                });
        ownerTasks.remove().run();
        scheduler.fireRaw(0);

        controller.close();
        ownerTasks.remove().run();

        assertTrue(retries.isEmpty());
        assertEquals(RadioPlaybackState.RECONNECT_WAIT, session.snapshot().state());
    }

    @Test
    void decoderTerminationWinsWhenSoundStopArrivesFirst() {
        ManualScheduler scheduler = new ManualScheduler();
        RadioReconnectController controller = new RadioReconnectController(
                NO_JITTER, () -> 0L, Runnable::run, scheduler);
        PlaybackSession session = new PlaybackSession();
        PlaybackSession.Attempt attempt = session.start("https://radio.example/live");

        controller.soundEngineStopped(session, attempt, ignored -> {
        }, () -> {
        });
        controller.termination(session, attempt,
                new gg.moonflower.etched.client.radio.stream.RadioAudioStream.Termination(
                        gg.moonflower.etched.client.radio.stream.RadioAudioStream.TerminalState.EOF, null),
                ignored -> {
                }, () -> {
                });
        scheduler.fireRaw(0);

        assertEquals(RadioPlaybackState.RECONNECT_WAIT, session.snapshot().state());
        assertEquals(RadioFailure.Code.UNEXPECTED_EOF, session.snapshot().failure().code());
        assertTrue(scheduler.tasks.get(0).cancelled);
    }

    @Test
    void soundStopWithoutDecoderOutcomeSchedulesRecoveryAfterGracePeriod() {
        ManualScheduler scheduler = new ManualScheduler();
        RadioReconnectController controller = new RadioReconnectController(
                NO_JITTER, () -> 0L, Runnable::run, scheduler);
        PlaybackSession session = new PlaybackSession();
        PlaybackSession.Attempt attempt = session.start("https://radio.example/live");

        controller.soundEngineStopped(session, attempt, ignored -> {
        }, () -> {
        });
        assertEquals(RadioPlaybackState.RESOLVING, session.snapshot().state());
        assertEquals(50L, scheduler.tasks.get(0).delayMillis);

        scheduler.fireRaw(0);

        assertEquals(RadioPlaybackState.RECONNECT_WAIT, session.snapshot().state());
        assertEquals(RadioFailure.Code.SOUND_ENGINE_STOPPED, session.snapshot().failure().code());
        assertTrue(session.snapshot().failure().recoverable());
        assertEquals(1_000L, scheduler.tasks.get(1).delayMillis);
    }

    @Test
    void sequenceAdvanceCancelsDeferredSoundStop() {
        ManualScheduler scheduler = new ManualScheduler();
        RadioReconnectController controller = new RadioReconnectController(
                NO_JITTER, () -> 0L, Runnable::run, scheduler);
        PlaybackSession session = new PlaybackSession();
        PlaybackSession.Attempt attempt = session.start("https://radio.example/album");
        session.advance(attempt, RadioPlaybackState.CONNECTING, 0L);
        session.advance(attempt, RadioPlaybackState.BUFFERING, 0L);
        session.advance(attempt, RadioPlaybackState.PLAYING, 0L);
        AtomicInteger advances = new AtomicInteger();

        controller.soundEngineStopped(session, attempt, ignored -> {
        }, () -> {
        });
        controller.sequenceAdvance(session, attempt, advances::incrementAndGet, () -> {
        });
        scheduler.fireRaw(0);

        assertEquals(1, advances.get());
        assertEquals(RadioPlaybackState.CONNECTING, session.snapshot().state());
        assertTrue(scheduler.tasks.get(0).cancelled);
    }

    @Test
    void pendingRetryLimitPreventsUnboundedTimerQueue() {
        ManualScheduler scheduler = new ManualScheduler();
        RadioReconnectController controller = new RadioReconnectController(
                NO_JITTER, () -> 0L, Runnable::run, scheduler, 1);
        PlaybackSession first = new PlaybackSession();
        PlaybackSession second = new PlaybackSession();
        PlaybackSession.Attempt firstAttempt = first.start("https://radio.example/first");
        PlaybackSession.Attempt secondAttempt = second.start("https://radio.example/second");
        RadioFailure transientFailure = RadioFailure.recoverable(
                RadioFailure.Code.CONNECT_TIMEOUT, "Timed out", null);

        controller.failure(first, firstAttempt, transientFailure, ignored -> {
        }, () -> {
        });
        controller.failure(second, secondAttempt, transientFailure, ignored -> {
        }, () -> {
        });

        assertEquals(1, scheduler.tasks.size());
        assertEquals(RadioPlaybackState.RECONNECT_WAIT, first.snapshot().state());
        assertEquals(RadioPlaybackState.FAILED, second.snapshot().state());
        assertEquals(RadioFailure.Code.RESOURCE_LIMIT, second.snapshot().failure().code());
        assertFalse(second.snapshot().failure().recoverable());
    }

    private static final class ManualScheduler implements RadioReconnectController.RetryScheduler {

        private final List<ScheduledTask> tasks = new ArrayList<>();
        private boolean reject;

        @Override
        public RadioReconnectController.Cancellable schedule(Runnable task, long delayMillis) {
            if (this.reject) {
                throw new java.util.concurrent.RejectedExecutionException("closed");
            }
            ScheduledTask scheduled = new ScheduledTask(task, delayMillis);
            this.tasks.add(scheduled);
            return () -> scheduled.cancelled = true;
        }

        private void fire(int index) {
            ScheduledTask task = this.tasks.get(index);
            if (!task.cancelled) {
                task.task.run();
            }
        }

        private void fireRaw(int index) {
            this.tasks.get(index).task.run();
        }
    }

    private static final class ScheduledTask {

        private final Runnable task;
        private final long delayMillis;
        private boolean cancelled;

        private ScheduledTask(Runnable task, long delayMillis) {
            this.task = task;
            this.delayMillis = delayMillis;
        }
    }
}
