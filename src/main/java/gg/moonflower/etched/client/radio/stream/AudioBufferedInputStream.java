package gg.moonflower.etched.client.radio.stream;

import gg.moonflower.etched.client.radio.AudioCancellation;
import gg.moonflower.etched.client.radio.RadioResourceDisposer;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A single-producer, single-consumer bounded buffer for one audio response body.
 */
public final class AudioBufferedInputStream extends InputStream {

    public static final int DEFAULT_CAPACITY = 512 * 1024;
    public static final int DEFAULT_CHUNK_SIZE = 16 * 1024;
    public static final int DEFAULT_STARTUP_THRESHOLD = 64 * 1024;

    private final InputStream source;
    private final AudioCancellation cancellation;
    private final byte[] buffer;
    private final int chunkSize;
    private final int startupThreshold;
    private final ReentrantLock lock;
    private final Condition notEmpty;
    private final Condition notFull;
    private final CompletableFuture<Startup> startup;
    private final AtomicBoolean sourceClosed;
    private final ExecutorService producerExecutor;

    private volatile Future<?> producerTask;
    private State state;
    private IOException failure;
    private StartupCompletion startupOutcome;
    private int readPosition;
    private int writePosition;
    private int bufferedBytes;

    public AudioBufferedInputStream(InputStream source, AudioCancellation cancellation,
                                    ExecutorService producerExecutor) {
        this(source, cancellation, producerExecutor, DEFAULT_CAPACITY,
                DEFAULT_CHUNK_SIZE, DEFAULT_STARTUP_THRESHOLD);
    }

    public AudioBufferedInputStream(InputStream source, AudioCancellation cancellation,
                                    ExecutorService producerExecutor, int capacityBytes,
                                    int chunkSize, int startupThresholdBytes) {
        this.source = Objects.requireNonNull(source, "source");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(producerExecutor, "producerExecutor");
        if (capacityBytes <= 0) {
            throw new IllegalArgumentException("capacityBytes must be positive");
        }
        if (chunkSize <= 0 || chunkSize > capacityBytes) {
            throw new IllegalArgumentException("chunkSize must be between 1 and capacityBytes");
        }
        if (startupThresholdBytes <= 0 || startupThresholdBytes > capacityBytes) {
            throw new IllegalArgumentException("startupThresholdBytes must be between 1 and capacityBytes");
        }

        this.buffer = new byte[capacityBytes];
        this.chunkSize = chunkSize;
        this.startupThreshold = startupThresholdBytes;
        this.lock = new ReentrantLock();
        this.notEmpty = this.lock.newCondition();
        this.notFull = this.lock.newCondition();
        this.startup = new CompletableFuture<>();
        this.sourceClosed = new AtomicBoolean();
        this.producerExecutor = producerExecutor;
        this.state = State.OPEN;

        if (cancellation.isCancelled()) {
            this.cancelBuffer();
            return;
        }
        try {
            this.producerTask = producerExecutor.submit(this::produce);
        } catch (RejectedExecutionException exception) {
            this.cancelBuffer();
            throw exception;
        }
        cancellation.onCancel(this::cancelBuffer);
    }

    public CompletionStage<Startup> startup() {
        return this.startup.minimalCompletionStage();
    }

    public State state() {
        this.lock.lock();
        try {
            return this.state;
        } finally {
            this.lock.unlock();
        }
    }

    public int bufferedBytes() {
        this.lock.lock();
        try {
            return this.bufferedBytes;
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public int available() {
        return this.bufferedBytes();
    }

    @Override
    public int read() throws IOException {
        byte[] value = new byte[1];
        int read = this.read(value, 0, 1);
        return read < 0 ? -1 : Byte.toUnsignedInt(value[0]);
    }

    @Override
    public int read(byte[] destination, int offset, int length) throws IOException {
        Objects.requireNonNull(destination, "destination");
        Objects.checkFromIndexSize(offset, length, destination.length);
        if (length == 0) {
            return 0;
        }

        this.lockInterruptibly();
        try {
            while (true) {
                if (this.bufferedBytes > 0) {
                    int copied = Math.min(length, this.bufferedBytes);
                    int first = Math.min(copied, this.buffer.length - this.readPosition);
                    System.arraycopy(this.buffer, this.readPosition, destination, offset, first);
                    int second = copied - first;
                    if (second > 0) {
                        System.arraycopy(this.buffer, 0, destination, offset + first, second);
                    }
                    this.readPosition = (this.readPosition + copied) % this.buffer.length;
                    this.bufferedBytes -= copied;
                    this.notFull.signal();
                    return copied;
                }
                switch (this.state) {
                    case EOF -> {
                        return -1;
                    }
                    case FAILED -> throw this.failure;
                    case CANCELLED -> throw this.cancelledReadException();
                    case OPEN -> this.await(this.notEmpty, "Interrupted while waiting for radio data");
                }
            }
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public long skip(long count) throws IOException {
        if (count <= 0) {
            return 0;
        }

        this.lockInterruptibly();
        try {
            while (true) {
                if (this.bufferedBytes > 0) {
                    int skipped = (int) Math.min(count, this.bufferedBytes);
                    this.readPosition = (this.readPosition + skipped) % this.buffer.length;
                    this.bufferedBytes -= skipped;
                    this.notFull.signal();
                    return skipped;
                }
                switch (this.state) {
                    case EOF -> {
                        return 0;
                    }
                    case FAILED -> throw this.failure;
                    case CANCELLED -> throw this.cancelledReadException();
                    case OPEN -> this.await(this.notEmpty, "Interrupted while waiting to skip radio data");
                }
            }
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public void close() {
        this.cancelBuffer();
    }

    private void produce() {
        byte[] chunk = new byte[this.chunkSize];
        try {
            while (true) {
                int allowed = this.awaitProducerCapacity();
                if (allowed == 0) {
                    return;
                }

                this.cancellation.throwIfCancelled();
                int read = this.source.read(chunk, 0, allowed);
                if (read < 0) {
                    this.publishEof();
                    return;
                }
                if (read == 0) {
                    int value = this.source.read();
                    if (value < 0) {
                        this.publishEof();
                        return;
                    }
                    chunk[0] = (byte) value;
                    read = 1;
                }
                this.cancellation.throwIfCancelled();
                this.publish(chunk, read);
            }
        } catch (CancellationException ignored) {
            this.cancelBuffer();
        } catch (InterruptedIOException exception) {
            if (this.cancellation.isCancelled() || this.state() == State.CANCELLED) {
                this.cancelBuffer();
            } else {
                this.publishFailure(exception);
            }
        } catch (IOException exception) {
            if (this.cancellation.isCancelled() || this.state() == State.CANCELLED) {
                this.cancelBuffer();
            } else {
                this.publishFailure(exception);
            }
        } catch (RuntimeException exception) {
            if (this.cancellation.isCancelled() || this.state() == State.CANCELLED) {
                this.cancelBuffer();
            } else {
                this.publishFailure(new IOException("Radio producer failed", exception));
            }
        } finally {
            this.publishUnexpectedTermination();
            this.closeSource();
        }
    }

    private int awaitProducerCapacity() throws InterruptedIOException {
        this.lockInterruptibly();
        try {
            while (this.state == State.OPEN && this.bufferedBytes == this.buffer.length) {
                this.await(this.notFull, "Radio producer was interrupted");
            }
            return this.state == State.OPEN
                    ? Math.min(this.chunkSize, this.buffer.length - this.bufferedBytes)
                    : 0;
        } finally {
            this.lock.unlock();
        }
    }

    private void publish(byte[] source, int length) {
        StartupCompletion completion;
        this.lock.lock();
        try {
            if (this.state != State.OPEN) {
                return;
            }
            int first = Math.min(length, this.buffer.length - this.writePosition);
            System.arraycopy(source, 0, this.buffer, this.writePosition, first);
            int second = length - first;
            if (second > 0) {
                System.arraycopy(source, first, this.buffer, 0, second);
            }
            this.writePosition = (this.writePosition + length) % this.buffer.length;
            this.bufferedBytes += length;
            this.notEmpty.signal();
            completion = this.startupCompletion();
        } finally {
            this.lock.unlock();
        }
        completion.complete(this.startup);
    }

    private void publishEof() {
        StartupCompletion completion;
        this.lock.lock();
        try {
            if (this.state != State.OPEN) {
                return;
            }
            this.state = State.EOF;
            this.notEmpty.signalAll();
            this.notFull.signalAll();
            completion = this.startupCompletion();
        } finally {
            this.lock.unlock();
        }
        completion.complete(this.startup);
    }

    private void publishFailure(IOException exception) {
        StartupCompletion completion;
        this.lock.lock();
        try {
            if (this.state != State.OPEN) {
                return;
            }
            this.failure = exception;
            this.state = State.FAILED;
            this.notEmpty.signalAll();
            this.notFull.signalAll();
            completion = this.startupCompletion();
        } finally {
            this.lock.unlock();
        }
        completion.complete(this.startup);
    }

    private void publishUnexpectedTermination() {
        this.publishFailure(new IOException("Radio producer terminated without a terminal state"));
    }

    private void cancelBuffer() {
        StartupCompletion completion;
        this.lock.lock();
        try {
            if (this.state == State.CANCELLED) {
                return;
            }
            this.state = State.CANCELLED;
            this.bufferedBytes = 0;
            this.readPosition = 0;
            this.writePosition = 0;
            this.notEmpty.signalAll();
            this.notFull.signalAll();
            completion = this.startupCompletion();
        } finally {
            this.lock.unlock();
        }
        completion.complete(this.startup);
        Future<?> task = this.producerTask;
        if (task != null) {
            cancel(this.producerExecutor, task);
        }
        RadioResourceDisposer.dispose(this::closeSource);
    }

    private static void cancel(ExecutorService executor, Future<?> future) {
        future.cancel(true);
        if (executor instanceof ThreadPoolExecutor pool && future instanceof Runnable task) {
            pool.remove(task);
            pool.purge();
        }
    }

    private StartupCompletion startupCompletion() {
        if (this.startupOutcome != null || this.startup.isDone()) {
            return StartupCompletion.NONE;
        }
        StartupCompletion completion;
        if (this.bufferedBytes >= this.startupThreshold
                || this.state == State.EOF && this.bufferedBytes > 0) {
            completion = new StartupCompletion(Startup.READY, null);
        } else {
            completion = switch (this.state) {
                case EOF -> new StartupCompletion(Startup.EMPTY_EOF, null);
                case FAILED -> new StartupCompletion(null, this.failure);
                case CANCELLED -> new StartupCompletion(null,
                        new CancellationException("Radio buffering was cancelled"));
                case OPEN -> StartupCompletion.NONE;
            };
        }
        if (completion != StartupCompletion.NONE) {
            this.startupOutcome = completion;
        }
        return completion;
    }

    private IOException cancelledReadException() {
        if (this.cancellation.isCancelled()) {
            throw new CancellationException("Radio buffering was cancelled");
        }
        return new IOException("Radio buffer is closed");
    }

    private void closeSource() {
        if (!this.sourceClosed.compareAndSet(false, true)) {
            return;
        }
        try {
            this.source.close();
        } catch (IOException ignored) {
        }
    }

    private void lockInterruptibly() throws InterruptedIOException {
        try {
            this.lock.lockInterruptibly();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            InterruptedIOException interrupted = new InterruptedIOException("Radio read was interrupted");
            interrupted.initCause(exception);
            throw interrupted;
        }
    }

    private void await(Condition condition, String message) throws InterruptedIOException {
        try {
            condition.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            InterruptedIOException interrupted = new InterruptedIOException(message);
            interrupted.initCause(exception);
            throw interrupted;
        }
    }

    public enum State {
        OPEN,
        EOF,
        FAILED,
        CANCELLED
    }

    public enum Startup {
        READY,
        EMPTY_EOF
    }

    private record StartupCompletion(Startup startup, Throwable failure) {

        private static final StartupCompletion NONE = new StartupCompletion(null, null);

        private void complete(CompletableFuture<Startup> future) {
            if (this.failure != null) {
                future.completeExceptionally(this.failure);
            } else if (this.startup != null) {
                future.complete(this.startup);
            }
        }
    }
}
