package gg.moonflower.etched.api.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.IntFunction;

/**
 * Utilizes multiple input stream futures to act as a single data stream.
 * Reads belong to one consumer; close may retire that consumer from another thread.
 * Each source future must own a fresh stream, including results arriving after close.
 *
 * @author Ocelot
 */
public class StreamingInputStream extends InputStream {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final int PREFETCH = 3;

    private final Object lifecycle = new Object();
    private final int parts;
    private final Deque<PendingStream> queue = new ArrayDeque<>(PREFETCH);
    private final IntFunction<CompletableFuture<InputStream>> source;
    private int index;
    private boolean closed;

    public StreamingInputStream(URL[] urls, IntFunction<CompletableFuture<InputStream>> source) {
        this.parts = Objects.requireNonNull(urls, "urls").length;
        this.source = Objects.requireNonNull(source, "source");
        this.queueBuffers();
    }

    private void queueBuffers() {
        while (!this.closed && this.queue.size() < PREFETCH && this.index < this.parts) {
            CompletableFuture<InputStream> pending;
            try {
                pending = Objects.requireNonNull(this.source.apply(this.index), "source future");
            } catch (RuntimeException failure) {
                pending = CompletableFuture.failedFuture(failure);
            }
            this.queue.addLast(new PendingStream(pending));
            this.index++;
        }
    }

    private void ensureOpen() throws IOException {
        if (this.closed) {
            throw new IOException("Stream is closed");
        }
    }

    private InputStream getCurrentStream() throws IOException {
        PendingStream pending;
        synchronized (this.lifecycle) {
            this.ensureOpen();
            pending = this.queue.peekFirst();
        }
        if (pending == null) {
            return null;
        }
        try {
            InputStream stream = pending.ready.get();
            synchronized (this.lifecycle) {
                this.ensureOpen();
            }
            return stream;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while opening audio part", failure);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            while (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (cause instanceof IOException exception) {
                throw exception;
            }
            throw new IOException("Failed to open audio part", cause);
        } catch (CancellationException failure) {
            throw new IOException("Audio part opening was cancelled", failure);
        }
    }

    private void incrementPosition() throws IOException {
        InputStream exhausted;
        synchronized (this.lifecycle) {
            this.ensureOpen();
            exhausted = this.queue.removeFirst().retire();
        }
        closeQuietly(exhausted);
        synchronized (this.lifecycle) {
            this.ensureOpen();
            this.queueBuffers();
        }
    }

    @Override
    public int read() throws IOException {
        try {
            InputStream current;
            while ((current = this.getCurrentStream()) != null) {
                int result = current.read();
                if (result != -1) {
                    return result;
                }
                this.incrementPosition();
            }
            return -1;
        } catch (IOException failure) {
            this.close();
            throw failure;
        }
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        synchronized (this.lifecycle) {
            this.ensureOpen();
        }
        if (len == 0) {
            return 0;
        }
        try {
            InputStream current;
            while ((current = this.getCurrentStream()) != null) {
                int result = current.read(b, off, len);
                if (result != -1) {
                    return result;
                }
                this.incrementPosition();
            }
            return -1;
        } catch (IOException failure) {
            this.close();
            throw failure;
        }
    }

    @Override
    public long skip(long n) throws IOException {
        synchronized (this.lifecycle) {
            this.ensureOpen();
        }
        if (n <= 0) {
            return 0;
        }
        // A short skip is not EOF. Reading also handles streams whose skip always returns zero.
        byte[] buffer = new byte[(int) Math.min(n, 8192)];
        long skipped = 0;
        while (skipped < n) {
            int read = this.read(buffer, 0, (int) Math.min(n - skipped, buffer.length));
            if (read == -1) {
                break;
            }
            skipped += read;
        }
        return skipped;
    }

    @Override
    public void close() {
        List<InputStream> opened = new ArrayList<>(PREFETCH);
        synchronized (this.lifecycle) {
            if (this.closed) {
                return;
            }
            this.closed = true;
            // Wake all future waiters before closing potentially blocking delegate streams.
            for (PendingStream pending : this.queue) {
                opened.add(pending.retire());
            }
            this.queue.clear();
        }
        opened.forEach(StreamingInputStream::closeQuietly);
    }

    private static void closeQuietly(InputStream stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (Exception failure) {
                LOGGER.warn("Failed to close legacy audio part", failure);
            }
        }
    }

    private static final class PendingStream {
        private final CompletableFuture<InputStream> ready = new CompletableFuture<>();
        private InputStream stream;
        private boolean retired;

        private PendingStream(CompletableFuture<InputStream> source) {
            // Do not cancel source: CompletableFuture cancellation could discard its late stream.
            source.whenComplete((opened, failure) -> {
                synchronized (this) {
                    if (!this.retired) {
                        if (failure != null) {
                            this.ready.completeExceptionally(failure);
                        } else if (opened == null) {
                            this.ready.completeExceptionally(new IOException("Audio part returned no stream"));
                        } else {
                            this.stream = opened;
                            this.ready.complete(opened);
                        }
                        return;
                    }
                }
                closeQuietly(opened);
            });
        }

        private synchronized InputStream retire() {
            this.retired = true;
            this.ready.completeExceptionally(new IOException("Stream is closed"));
            InputStream opened = this.stream;
            this.stream = null;
            return opened;
        }
    }
}
