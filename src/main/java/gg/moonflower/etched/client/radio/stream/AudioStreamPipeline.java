package gg.moonflower.etched.client.radio.stream;

import gg.moonflower.etched.client.radio.AudioCancellation;
import gg.moonflower.etched.client.radio.RadioResourceDisposer;
import gg.moonflower.etched.client.radio.RadioFailure;
import gg.moonflower.etched.client.radio.source.RadioResolvedSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Consumer;

/** Builds one decoder from one independently owned resolved live response. */
public final class AudioStreamPipeline {

    private AudioStreamPipeline() {
    }

    public static Preparation prepare(RadioResolvedSource source, AudioCancellation cancellation,
                                       ExecutorService producerExecutor,
                                       ExecutorService decoderExecutor, boolean forceStereo) {
        return prepare(source, cancellation, producerExecutor, decoderExecutor, forceStereo, ignored -> {
        });
    }

    public static Preparation prepare(RadioResolvedSource source, AudioCancellation cancellation,
                                      ExecutorService producerExecutor,
                                      ExecutorService decoderExecutor, boolean forceStereo,
                                      Consumer<String> streamTitleListener) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(producerExecutor, "producerExecutor");
        Objects.requireNonNull(decoderExecutor, "decoderExecutor");
        Objects.requireNonNull(streamTitleListener, "streamTitleListener");
        if (producerExecutor == decoderExecutor) {
            throw new IllegalArgumentException("Producer and decoder executors must be distinct");
        }

        AudioBufferedInputStream buffer;
        try {
            buffer = new AudioBufferedInputStream(source.body(), cancellation, producerExecutor);
        } catch (RuntimeException exception) {
            source.close();
            throw exception;
        }

        CompletableFuture<RadioAudioStream> stream = new CompletableFuture<>();
        FutureTask<Void> decoderTask = new FutureTask<>(() -> {
            RadioAudioStream audio = null;
            try {
                audio = decode(source, buffer, cancellation, forceStereo, streamTitleListener);
                if (!stream.complete(audio)) {
                    closeQuietly(audio);
                }
            } catch (Throwable failure) {
                buffer.close();
                stream.completeExceptionally(failure);
            }
            return null;
        });
        Preparation preparation = new Preparation(buffer, stream, decoderExecutor, decoderTask);
        buffer.startup().whenComplete((startup, failure) -> {
            if (failure != null) {
                buffer.close();
                stream.completeExceptionally(failure);
                return;
            }
            if (startup == AudioBufferedInputStream.Startup.EMPTY_EOF) {
                buffer.close();
                stream.completeExceptionally(new RadioStreamException(
                        RadioFailure.Code.UNEXPECTED_EOF, true,
                        "Radio stream ended before audio data arrived", null));
                return;
            }
            if (stream.isDone()) {
                return;
            }
            try {
                decoderExecutor.execute(decoderTask);
            } catch (RuntimeException exception) {
                buffer.close();
                stream.completeExceptionally(exception);
            } finally {
                if (decoderTask.isCancelled()) {
                    remove(decoderExecutor, decoderTask);
                }
            }
        });
        return preparation;
    }

    private static RadioAudioStream decode(RadioResolvedSource source, AudioBufferedInputStream buffer,
                                           AudioCancellation cancellation, boolean forceStereo,
                                           Consumer<String> streamTitleListener) {
        cancellation.throwIfCancelled();
        RadioAudioStream decoded = null;
        try {
            InputStream audioBody = audioBody(source, buffer, streamTitleListener);
            decoded = switch (source.format()) {
                case MP3 -> new RadioMp3AudioStream(audioBody);
                case OGG -> new RadioOggAudioStream(audioBody);
            };
            cancellation.throwIfCancelled();
            return forceStereo ? decoded : new RadioMonoAudioStream(decoded);
        } catch (IOException | RuntimeException exception) {
            if (decoded != null) {
                try {
                    decoded.close();
                } catch (IOException closeException) {
                    exception.addSuppressed(closeException);
                }
            } else {
                buffer.close();
            }
            throw new CompletionException(exception);
        }
    }

    private static void cancel(ExecutorService executor, Future<?> future) {
        future.cancel(true);
        remove(executor, future);
    }

    private static void remove(ExecutorService executor, Future<?> future) {
        if (executor instanceof ThreadPoolExecutor pool && future instanceof Runnable task) {
            pool.remove(task);
            pool.purge();
        }
    }

    private static void closeQuietly(RadioAudioStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
        }
    }

    private static InputStream audioBody(RadioResolvedSource source, InputStream body,
                                         Consumer<String> streamTitleListener) {
        String value = source.headers().entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getKey().equalsIgnoreCase("icy-metaint"))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst()
                .orElse(null);
        if (value == null) {
            return body;
        }
        try {
            int interval = Integer.parseInt(value.trim());
            return interval > 0
                    ? new IcyInputStream(body, interval, streamTitleListener)
                    : body;
        } catch (NumberFormatException ignored) {
            return body;
        }
    }

    public static final class Preparation implements AutoCloseable {

        private final AudioBufferedInputStream buffer;
        private final CompletableFuture<RadioAudioStream> stream;
        private final ExecutorService decoderExecutor;
        private final Future<?> decoderTask;
        private boolean closed;
        private boolean transferred;

        private Preparation(AudioBufferedInputStream buffer,
                            CompletableFuture<RadioAudioStream> stream,
                            ExecutorService decoderExecutor, Future<?> decoderTask) {
            this.buffer = buffer;
            this.stream = stream;
            this.decoderExecutor = decoderExecutor;
            this.decoderTask = decoderTask;
            stream.whenComplete((audio, failure) -> {
                if (failure != null) {
                    this.buffer.close();
                    return;
                }
                synchronized (this) {
                    if (!this.closed || this.transferred || audio == null) {
                        return;
                    }
                }
                RadioResourceDisposer.dispose(() -> closeQuietly(audio));
            });
        }

        public int bufferedBytes() {
            return this.buffer.bufferedBytes();
        }

        public AudioBufferedInputStream.State bufferState() {
            return this.buffer.state();
        }

        public CompletionStage<RadioAudioStream> stream() {
            return this.stream.minimalCompletionStage();
        }

        public synchronized boolean transfer(RadioAudioStream audio) {
            Objects.requireNonNull(audio, "audio");
            if (this.closed || this.transferred || !this.stream.isDone()
                    || this.stream.isCompletedExceptionally() || this.stream.join() != audio) {
                return false;
            }
            this.transferred = true;
            return true;
        }

        @Override
        public void close() {
            RadioAudioStream audio = null;
            boolean closeBuffer;
            synchronized (this) {
                if (this.closed) {
                    return;
                }
                this.closed = true;
                closeBuffer = !this.transferred;
                if (!this.transferred && this.stream.isDone() && !this.stream.isCompletedExceptionally()) {
                    audio = this.stream.join();
                }
            }
            if (closeBuffer) {
                stream.cancel(true);
                cancel(this.decoderExecutor, this.decoderTask);
                this.buffer.close();
            }
            if (audio != null) {
                RadioAudioStream orphaned = audio;
                RadioResourceDisposer.dispose(() -> closeQuietly(orphaned));
            }
        }

    }
}
