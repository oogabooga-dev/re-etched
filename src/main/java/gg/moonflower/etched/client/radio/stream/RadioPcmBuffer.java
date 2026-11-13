package gg.moonflower.etched.client.radio.stream;

import gg.moonflower.etched.client.radio.RadioResourceDisposer;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * SoundEngine must never wait for network input or a decoder. One worker owns
 * decoding; the audio thread only copies bounded PCM or a short silence block.
 */
final class RadioPcmBuffer extends AbstractRadioAudioStream {
    private static final int CAPACITY = 1024 * 1024;
    private static final long STALL_NANOS = TimeUnit.SECONDS.toNanos(15);

    private final RadioAudioStream decoder;
    private final Runnable abortInput;
    private final AudioFormat format;
    private final byte[] pcm;
    private final int chunkBytes;
    private final AtomicBoolean decoderClosed = new AtomicBoolean();
    private int head;
    private int size;
    private boolean closed;
    private Termination terminal;
    private final LongSupplier nanoTime;
    private long lastProgress;

    RadioPcmBuffer(RadioAudioStream decoder, Runnable abortInput) {
        this(decoder, abortInput, System::nanoTime);
    }

    RadioPcmBuffer(RadioAudioStream decoder, Runnable abortInput, LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
        this.lastProgress = nanoTime.getAsLong();
        this.decoder = decoder;
        this.abortInput = abortInput;
        this.format = decoder.getFormat();
        int frame = this.format.getFrameSize();
        if (frame <= 0 || frame > CAPACITY || !Float.isFinite(this.format.getSampleRate())
                || this.format.getSampleRate() <= 0) {
            throw new IllegalArgumentException("Invalid PCM format");
        }
        this.pcm = new byte[CAPACITY - CAPACITY % frame];
        // Up to 100 ms per worker read / underrun. Keep all reads frame aligned.
        this.chunkBytes = Math.max(frame, Math.min(this.pcm.length,
                (int) (this.format.getSampleRate() / 10) * frame));
    }

    @Override
    public AudioFormat getFormat() {
        return this.format;
    }

    /** Runs only on the decoder executor, including when the PCM queue is full. */
    void produce() {
        try {
            while (true) {
                synchronized (this) {
                    while (!this.closed && this.pcm.length - this.size < this.chunkBytes) {
                        this.wait();
                    }
                    if (this.closed) {
                        return;
                    }
                }
                ByteBuffer decoded = this.decoder.read(this.chunkBytes);
                if (!decoded.hasRemaining()) {
                    Termination end = this.decoder.termination().toCompletableFuture().getNow(null);
                    this.finish(end == null ? new Termination(TerminalState.EOF, null) : end);
                    return;
                }
                int length = decoded.remaining();
                if (length > this.chunkBytes || length % this.format.getFrameSize() != 0) {
                    throw new IOException("Decoder returned invalid PCM length");
                }
                synchronized (this) {
                    if (this.closed) {
                        return;
                    }
                    int tail = (this.head + this.size) % this.pcm.length;
                    int first = Math.min(length, this.pcm.length - tail);
                    decoded.get(this.pcm, tail, first);
                    decoded.get(this.pcm, 0, length - first);
                    this.size += length;
                    this.lastProgress = this.nanoTime.getAsLong();
                }
            }
        } catch (InterruptedException | CancellationException exception) {
            this.finish(new Termination(TerminalState.CANCELLED, null));
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        } catch (IOException exception) {
            this.finish(new Termination(TerminalState.FAILED, exception));
        } catch (RuntimeException exception) {
            this.finish(new Termination(TerminalState.FAILED,
                    new IOException("Radio PCM decoding failed", exception)));
        } finally {
            this.abortInput.run();
            this.closeDecoder();
        }
    }

    private synchronized void finish(Termination termination) {
        if (!this.closed) {
            this.terminal = termination;
        }
    }

    @Override
    public ByteBuffer read(int requestedBytes) throws IOException {
        Termination end;
        synchronized (this) {
            if (this.closed || requestedBytes <= 0) {
                return ByteBuffer.allocateDirect(0);
            }
            int frame = this.format.getFrameSize();
            int limit = Math.max(frame, Math.min(requestedBytes, this.pcm.length));
            limit -= limit % frame;
            if (this.size > 0) {
                int length = Math.min(limit, this.size);
                ByteBuffer result = ByteBuffer.allocateDirect(length);
                int first = Math.min(length, this.pcm.length - this.head);
                result.put(this.pcm, this.head, first);
                result.put(this.pcm, 0, length - first);
                this.head = (this.head + length) % this.pcm.length;
                this.size -= length;
                this.lastProgress = this.nanoTime.getAsLong();
                this.notifyAll();
                return result.flip();
            }
            end = this.terminal;
            if (end == null && this.nanoTime.getAsLong() - this.lastProgress >= STALL_NANOS) {
                end = new Termination(TerminalState.FAILED,
                        new IOException("Radio decoder stopped producing audio"));
                this.terminal = end;
            }
            if (end == null) {
                // Empty buffers mean EOF to Minecraft. Silence keeps the channel
                // alive until data, failure or cancellation arrives.
                return ByteBuffer.allocateDirect(Math.min(limit, this.chunkBytes));
            }
        }
        // Completion callbacks can cancel the session. Never invoke them under
        // the PCM monitor, or run decoder close on SoundEngine's thread.
        if (end.state() == TerminalState.FAILED) {
            this.fail(end.failure());
        } else {
            this.complete(end.state());
        }
        this.close();
        if (end.failure() != null) {
            throw end.failure();
        }
        return ByteBuffer.allocateDirect(0);
    }

    @Override
    public void close() {
        this.stop(TerminalState.CLOSED);
    }

    void cancel() {
        this.stop(TerminalState.CANCELLED);
    }

    private void stop(TerminalState state) {
        synchronized (this) {
            if (this.closed) {
                return;
            }
            this.closed = true;
            this.size = 0;
            this.notifyAll();
        }
        this.complete(state);
        this.abortInput.run();
        RadioResourceDisposer.dispose(this::closeDecoder);
    }

    private void closeDecoder() {
        if (this.decoderClosed.compareAndSet(false, true)) {
            try {
                this.decoder.close();
            } catch (IOException ignored) {
            }
        }
    }
}
