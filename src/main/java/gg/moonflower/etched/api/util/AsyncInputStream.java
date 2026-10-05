package gg.moonflower.etched.api.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Asynchronously reads data into a bounded buffer for one consumer.
 * The executor must run work off-thread. Close may be called from another thread.
 *
 * @author Ocelot
 * @since 1.2.0
 */
public class AsyncInputStream extends InputStream {

    private static final int MAX_DATA = 32768;

    private final Object lifecycle = new Object();
    private final Deque<BufferedData> readBytes = new ArrayDeque<>();
    private final int maxBuffers;
    private final int bufferSize;
    // Includes queued, consumer-held, and producer-in-flight buffers.
    private int retainedBuffers;
    private int pointer;
    private BufferedData currentData;
    private InputStream delegate;
    private boolean closed;
    private boolean primed;
    private boolean finished;
    private IOException failure;

    public AsyncInputStream(InputStreamSupplier source, int bufferSize, int buffers, Executor readExecutor) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(readExecutor, "readExecutor");
        if (bufferSize <= 0 || bufferSize > MAX_DATA || buffers <= 0) {
            throw new IllegalArgumentException("Buffer size must be 1..32768 and buffer count must be positive");
        }
        this.bufferSize = bufferSize;
        this.maxBuffers = Math.min(buffers, MAX_DATA / bufferSize);
        try {
            readExecutor.execute(() -> this.pump(source));
        } catch (RuntimeException exception) {
            throw new IOException("Unable to schedule asynchronous audio reader", exception);
        }
        try {
            synchronized (this.lifecycle) {
                while (!this.finished && !this.primed) {
                    this.awaitChange();
                }
                if (this.failure != null) {
                    throw this.failure;
                }
            }
        } catch (IOException exception) {
            try {
                this.close();
            } catch (IOException closeFailure) {
                if (closeFailure != exception) {
                    exception.addSuppressed(closeFailure);
                }
            }
            throw exception;
        }
    }

    private void pump(InputStreamSupplier source) {
        IOException problem = null;
        try {
            if (this.isClosed()) {
                return;
            }
            InputStream stream = Objects.requireNonNull(source.get(), "source stream");
            boolean accepted;
            synchronized (this.lifecycle) {
                accepted = !this.closed;
                if (accepted) {
                    this.delegate = stream;
                }
            }
            if (!accepted) {
                // Constructor interruption can retire the operation before source.get returns.
                stream.close();
                return;
            }
            while (this.reserveBuffer()) {
                byte[] data = new byte[this.bufferSize];
                int count = 0;
                boolean eof = false;
                Throwable readFailure = null;
                try {
                    while (count < data.length && !this.isClosed()) {
                        int read = stream.read(data, count, data.length - count);
                        if (read == -1) {
                            eof = true;
                            break;
                        }
                        if (read == 0) {
                            int value = stream.read();
                            if (value == -1) {
                                eof = true;
                                break;
                            }
                            data[count++] = (byte) value;
                        } else {
                            count += read;
                        }
                    }
                } catch (Throwable exception) {
                    readFailure = exception;
                }
                synchronized (this.lifecycle) {
                    if (!this.closed) {
                        if (count > 0) {
                            this.readBytes.addLast(new BufferedData(data, count));
                        } else {
                            this.retainedBuffers--;
                        }
                        if (readFailure == null && this.readBytes.size() == this.maxBuffers) {
                            this.primed = true;
                        }
                        this.lifecycle.notifyAll();
                    }
                }
                if (readFailure != null) {
                    throw readFailure;
                }
                if (eof) {
                    break;
                }
            }
        } catch (Throwable exception) {
            // Even unchecked provider failures must retire initial/data waiters.
            problem = exception instanceof IOException io ? io : new IOException("Asynchronous audio reader failed", exception);
        } finally {
            InputStream owned;
            synchronized (this.lifecycle) {
                owned = this.delegate;
                this.delegate = null;
            }
            if (owned != null) {
                try {
                    owned.close();
                } catch (Throwable exception) {
                    IOException closeFailure = exception instanceof IOException io ? io : new IOException("Failed to close audio reader", exception);
                    if (problem == null) {
                        problem = closeFailure;
                    } else if (problem != closeFailure) {
                        problem.addSuppressed(closeFailure);
                    }
                }
            }
            synchronized (this.lifecycle) {
                this.finished = true;
                if (!this.closed) {
                    this.failure = problem;
                }
                this.lifecycle.notifyAll();
            }
        }
    }

    private boolean reserveBuffer() throws IOException {
        synchronized (this.lifecycle) {
            while (!this.closed && this.retainedBuffers == this.maxBuffers) {
                this.awaitChange();
            }
            if (this.closed) {
                return false;
            }
            this.retainedBuffers++;
            return true;
        }
    }

    private boolean isClosed() {
        synchronized (this.lifecycle) {
            return this.closed;
        }
    }

    private void awaitChange() throws IOException {
        try {
            this.lifecycle.wait();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for asynchronous audio", exception);
        }
    }

    private void ensureOpen() throws IOException {
        if (this.closed) {
            throw new IOException("Stream is closed");
        }
    }

    private boolean nextBuffer() throws IOException {
        this.ensureOpen();
        if (this.currentData != null) {
            return true;
        }
        while (this.readBytes.isEmpty() && !this.finished) {
            this.awaitChange();
            this.ensureOpen();
        }
        if (this.readBytes.isEmpty()) {
            if (this.failure != null) {
                throw this.failure;
            }
            return false;
        }
        this.currentData = this.readBytes.removeFirst();
        this.pointer = 0;
        return true;
    }

    private void consume(int count) {
        this.pointer += count;
        if (this.pointer == this.currentData.length()) {
            this.currentData = null;
            this.retainedBuffers--;
            this.lifecycle.notifyAll();
        }
    }

    @Override
    public int read() throws IOException {
        synchronized (this.lifecycle) {
            if (!this.nextBuffer()) {
                return -1;
            }
            int result = this.currentData.bytes()[this.pointer] & 0xFF;
            this.consume(1);
            return result;
        }
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        synchronized (this.lifecycle) {
            this.ensureOpen();
            if (len == 0) {
                return 0;
            }
            if (!this.nextBuffer()) {
                return -1;
            }
            int count = Math.min(len, this.currentData.length() - this.pointer);
            System.arraycopy(this.currentData.bytes(), this.pointer, b, off, count);
            this.consume(count);
            return count;
        }
    }

    /** Preserves the legacy no-checked-exception signature; read failures use UncheckedIOException. */
    @Override
    public long skip(long n) {
        try {
            synchronized (this.lifecycle) {
                this.ensureOpen();
                long skipped = 0;
                while (skipped < n && this.nextBuffer()) {
                    int count = (int) Math.min(n - skipped, this.currentData.length() - this.pointer);
                    this.consume(count);
                    skipped += count;
                }
                return skipped;
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @Override
    public void close() throws IOException {
        InputStream owned;
        synchronized (this.lifecycle) {
            if (this.closed) {
                return;
            }
            this.closed = true;
            this.currentData = null;
            this.readBytes.clear();
            this.retainedBuffers = 0;
            owned = this.delegate;
            this.delegate = null;
            this.lifecycle.notifyAll();
        }
        // Never join the producer: it may still be inside third-party I/O.
        if (owned != null) {
            owned.close();
        }
    }

    private record BufferedData(byte[] bytes, int length) {
    }

    @FunctionalInterface
    public interface InputStreamSupplier {
        InputStream get() throws IOException;
    }
}
