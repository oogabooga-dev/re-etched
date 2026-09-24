package gg.moonflower.etched.client.radio;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RadioConnectionSchedulerTest {

    @Test
    void boundsActiveConnectionsAndPendingQueue() {
        RadioConnectionScheduler scheduler = new RadioConnectionScheduler(1, 1, Runnable::run);
        List<Integer> started = new ArrayList<>();
        List<RadioConnectionScheduler.Lease> leases = new ArrayList<>();

        assertTrue(scheduler.submit(new AudioCancellation(), lease -> {
            started.add(1);
            leases.add(lease);
        }));
        assertTrue(scheduler.submit(new AudioCancellation(), lease -> {
            started.add(2);
            leases.add(lease);
        }));
        assertFalse(scheduler.submit(new AudioCancellation(), lease -> started.add(3)));

        assertEquals(List.of(1), started);
        assertEquals(1, scheduler.activeCount());
        assertEquals(1, scheduler.queuedCount());

        leases.get(0).close();

        assertEquals(List.of(1, 2), started);
        assertEquals(1, scheduler.activeCount());
        assertEquals(0, scheduler.queuedCount());
        leases.get(1).close();
        assertEquals(0, scheduler.activeCount());
    }

    @Test
    void cancellationRemovesQueuedAttemptBeforeAdmission() {
        RadioConnectionScheduler scheduler = new RadioConnectionScheduler(1, 2, Runnable::run);
        List<RadioConnectionScheduler.Lease> leases = new ArrayList<>();
        List<Integer> started = new ArrayList<>();
        AudioCancellation queued = new AudioCancellation();
        scheduler.submit(new AudioCancellation(), leases::add);
        scheduler.submit(queued, lease -> started.add(2));

        queued.cancel();
        leases.get(0).close();

        assertTrue(started.isEmpty());
        assertEquals(0, scheduler.activeCount());
        assertEquals(0, scheduler.queuedCount());
    }

    @Test
    void activeCancellationWaitsForResourceOwnerToReleaseLease() {
        RadioConnectionScheduler scheduler = new RadioConnectionScheduler(1, 0, Runnable::run);
        AudioCancellation cancellation = new AudioCancellation();
        List<RadioConnectionScheduler.Lease> leases = new ArrayList<>();
        scheduler.submit(cancellation, leases::add);

        cancellation.cancel();

        assertEquals(1, scheduler.activeCount());
        assertFalse(scheduler.submit(new AudioCancellation(), leases::add));
        leases.get(0).close();
        assertEquals(0, scheduler.activeCount());
    }

    @Test
    void closeRejectsNewAndDropsQueuedAttempts() {
        RadioConnectionScheduler scheduler = new RadioConnectionScheduler(1, 1, Runnable::run);
        List<RadioConnectionScheduler.Lease> leases = new ArrayList<>();
        scheduler.submit(new AudioCancellation(), leases::add);
        scheduler.submit(new AudioCancellation(), leases::add);

        scheduler.close();

        assertEquals(0, scheduler.queuedCount());
        assertFalse(scheduler.submit(new AudioCancellation(), leases::add));
        leases.get(0).close();
        assertEquals(0, scheduler.activeCount());
    }

    @Test
    void reportsRecoverableResourceLimitFailure() {
        RadioFailure failure = RadioConnectionScheduler.limitFailure();

        assertEquals(RadioFailure.Code.RESOURCE_LIMIT, failure.code());
        assertTrue(failure.recoverable());
    }

    @Test
    void ownerExecutorRejectionRollsBackReservationAndClosesScheduler() {
        RadioConnectionScheduler scheduler = new RadioConnectionScheduler(1, 1,
                command -> {
                    throw new RejectedExecutionException("closed");
                });

        assertFalse(scheduler.submit(new AudioCancellation(), lease -> {
        }));
        assertEquals(0, scheduler.activeCount());
        assertFalse(scheduler.submit(new AudioCancellation(), lease -> {
        }));
    }

    @Test
    void closeSuppressesReservedStarterWaitingOnOwnerExecutor() {
        Queue<Runnable> ownerTasks = new ArrayDeque<>();
        RadioConnectionScheduler scheduler = new RadioConnectionScheduler(1, 1, ownerTasks::add);
        List<RadioConnectionScheduler.Lease> leases = new ArrayList<>();
        assertTrue(scheduler.submit(new AudioCancellation(), leases::add));

        scheduler.close();
        ownerTasks.remove().run();

        assertTrue(leases.isEmpty());
        assertEquals(0, scheduler.activeCount());
    }
}
