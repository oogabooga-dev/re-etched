package gg.moonflower.etched.client.radio;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Bounds admitted radio connections and their pending in-memory queue. */
public final class RadioConnectionScheduler implements AutoCloseable {

    private final int maxActive;
    private final int maxQueued;
    private final Executor ownerExecutor;
    private final ArrayDeque<Entry> queued = new ArrayDeque<>();
    private final Set<Entry> admitted = new HashSet<>();
    private int active;
    private boolean closed;

    public RadioConnectionScheduler(int maxActive, int maxQueued, Executor ownerExecutor) {
        if (maxActive < 1 || maxQueued < 0) {
            throw new IllegalArgumentException("Radio connection limits must be positive");
        }
        this.maxActive = maxActive;
        this.maxQueued = maxQueued;
        this.ownerExecutor = Objects.requireNonNull(ownerExecutor, "ownerExecutor");
    }

    public boolean submit(AudioCancellation cancellation, Consumer<Lease> starter) {
        return this.submit(cancellation, starter, () -> {
        });
    }

    public boolean submit(AudioCancellation cancellation, Consumer<Lease> starter, Runnable dispatchFailed) {
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(starter, "starter");
        Entry entry = new Entry(cancellation, starter, Objects.requireNonNull(dispatchFailed, "dispatchFailed"));
        boolean reserved;
        synchronized (this) {
            if (this.closed || this.active >= this.maxActive && this.queued.size() >= this.maxQueued) {
                return false;
            }
            if (this.active < this.maxActive) {
                this.reserveLocked(entry);
                reserved = true;
            } else {
                entry.state = EntryState.QUEUED;
                this.queued.addLast(entry);
                reserved = false;
            }
        }
        cancellation.onCancel(() -> this.dispatchCancel(entry));
        if (reserved) {
            return this.dispatchStart(entry);
        }
        return true;
    }

    public static RadioFailure limitFailure() {
        return RadioFailure.recoverable(RadioFailure.Code.RESOURCE_LIMIT,
                "Too many radio connections are already pending", null);
    }

    public static RadioFailure unavailableFailure() {
        return RadioFailure.fatal(RadioFailure.Code.RESOURCE_LIMIT,
                "Radio connection scheduler is unavailable", null);
    }

    public synchronized int activeCount() {
        return this.active;
    }

    public synchronized int queuedCount() {
        return this.queued.size();
    }

    private void reserveLocked(Entry entry) {
        this.active++;
        this.admitted.add(entry);
        entry.state = EntryState.RESERVED;
    }

    private boolean dispatchStart(Entry entry) {
        if (entry == null) {
            return true;
        }
        try {
            this.ownerExecutor.execute(() -> this.start(entry));
            return true;
        } catch (RuntimeException exception) {
            this.ownerExecutorFailed(entry);
            return false;
        }
    }

    private void dispatchCancel(Entry entry) {
        try {
            this.ownerExecutor.execute(() -> this.cancel(entry));
        } catch (RuntimeException exception) {
            this.ownerExecutorFailed(entry);
        }
    }

    private void start(Entry entry) {
        Entry next = null;
        synchronized (this) {
            if (entry.state != EntryState.RESERVED) {
                return;
            }
            if (this.closed || entry.cancellation.isCancelled()) {
                next = this.releaseLocked(entry);
            } else {
                entry.state = EntryState.ACTIVE;
            }
        }
        this.dispatchStart(next);
        if (entry.state != EntryState.ACTIVE) {
            return;
        }
        try {
            entry.starter.accept(new Lease(this, entry));
        } catch (RuntimeException exception) {
            this.releaseFromOwner(entry);
            throw exception;
        }
    }

    private void cancel(Entry entry) {
        Entry next = null;
        synchronized (this) {
            if (entry.state == EntryState.QUEUED) {
                this.queued.remove(entry);
                entry.state = EntryState.FINISHED;
            } else if (entry.state == EntryState.RESERVED) {
                next = this.releaseLocked(entry);
            }
        }
        this.dispatchStart(next);
    }

    private void releaseFromOwner(Entry entry) {
        Entry next;
        synchronized (this) {
            next = this.releaseLocked(entry);
        }
        this.dispatchStart(next);
    }

    private Entry releaseLocked(Entry entry) {
        if (entry.state != EntryState.RESERVED && entry.state != EntryState.ACTIVE) {
            return null;
        }
        entry.state = EntryState.FINISHED;
        this.admitted.remove(entry);
        this.active--;
        while (!this.closed && this.active < this.maxActive && !this.queued.isEmpty()) {
            Entry next = this.queued.removeFirst();
            if (next.cancellation.isCancelled()) {
                next.state = EntryState.FINISHED;
            } else {
                this.reserveLocked(next);
                return next;
            }
        }
        return null;
    }

    private void ownerExecutorFailed(Entry entry) {
        ArrayList<Entry> rejected = new ArrayList<>();
        synchronized (this) {
            this.closed = true;
            for (Entry queuedEntry : this.queued) {
                queuedEntry.state = EntryState.FINISHED;
                rejected.add(queuedEntry);
            }
            this.queued.clear();
            for (Entry admittedEntry : new ArrayList<>(this.admitted)) {
                if (admittedEntry.state == EntryState.RESERVED) {
                    admittedEntry.state = EntryState.FINISHED;
                    this.admitted.remove(admittedEntry);
                    this.active--;
                    rejected.add(admittedEntry);
                }
            }
            if (entry.state == EntryState.RESERVED) {
                entry.state = EntryState.FINISHED;
                this.admitted.remove(entry);
                this.active--;
                rejected.add(entry);
            }
        }
        for (Entry rejectedEntry : rejected) {
            rejectedEntry.dispatchFailed.run();
        }
    }

    @Override
    public synchronized void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        Entry entry;
        while ((entry = this.queued.pollFirst()) != null) {
            entry.state = EntryState.FINISHED;
        }
        for (Entry admittedEntry : new ArrayList<>(this.admitted)) {
            if (admittedEntry.state == EntryState.RESERVED) {
                admittedEntry.state = EntryState.FINISHED;
                this.admitted.remove(admittedEntry);
                this.active--;
            }
        }
    }

    public static final class Lease implements AutoCloseable {

        private final RadioConnectionScheduler scheduler;
        private final Entry entry;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(RadioConnectionScheduler scheduler, Entry entry) {
            this.scheduler = scheduler;
            this.entry = entry;
        }

        @Override
        public void close() {
            if (this.closed.compareAndSet(false, true)) {
                try {
                    this.scheduler.ownerExecutor.execute(() -> this.scheduler.releaseFromOwner(this.entry));
                } catch (RuntimeException exception) {
                    this.scheduler.ownerExecutorFailed(this.entry);
                    this.scheduler.releaseFromOwner(this.entry);
                }
            }
        }
    }

    private static final class Entry {

        private final AudioCancellation cancellation;
        private final Consumer<Lease> starter;
        private final Runnable dispatchFailed;
        private EntryState state;

        private Entry(AudioCancellation cancellation, Consumer<Lease> starter, Runnable dispatchFailed) {
            this.cancellation = cancellation;
            this.starter = starter;
            this.dispatchFailed = dispatchFailed;
        }
    }

    private enum EntryState {
        QUEUED,
        RESERVED,
        ACTIVE,
        FINISHED
    }
}
