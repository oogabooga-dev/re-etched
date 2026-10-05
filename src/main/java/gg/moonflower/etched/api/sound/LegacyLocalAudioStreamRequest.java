package gg.moonflower.etched.api.sound;

import gg.moonflower.etched.api.sound.stream.MonoWrapper;
import gg.moonflower.etched.client.sound.EmptyAudioStream;
import net.minecraft.client.sounds.AudioStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/** Owns a vanilla loader stream until local mono modification and publication have succeeded. */
final class LegacyLocalAudioStreamRequest {

    private static final Logger LOGGER = LogManager.getLogger();

    private final Object lifecycle = new Object();
    private final CompletableFuture<AudioStream> result = new CompletableFuture<>();
    private final Executor executor;
    private final UnaryOperator<AudioStream> modifier;
    private final Runnable success;
    private final Consumer<Throwable> failure;
    private AudioStream owned;
    private AudioStream modified;

    private LegacyLocalAudioStreamRequest(Executor executor, UnaryOperator<AudioStream> modifier,
                                         Runnable success, Consumer<Throwable> failure) {
        this.executor = executor;
        this.modifier = modifier;
        this.success = success;
        this.failure = failure;
        this.result.whenComplete((delivered, error) -> {
            Resources retired;
            synchronized (this.lifecycle) {
                if (delivered != null && delivered == this.modified) {
                    this.owned = null;
                    this.modified = null;
                    return;
                }
                retired = this.detach();
            }
            close(retired, null);
        });
    }

    static CompletableFuture<AudioStream> start(CompletableFuture<AudioStream> loading, Executor executor,
                                               Runnable success, Consumer<Throwable> failure) {
        return start(loading, executor, MonoWrapper::new, success, failure);
    }

    static CompletableFuture<AudioStream> start(CompletableFuture<AudioStream> loading, Executor executor,
                                               UnaryOperator<AudioStream> modifier, Runnable success,
                                               Consumer<Throwable> failure) {
        var request = new LegacyLocalAudioStreamRequest(executor, modifier, success, failure);
        // Vanilla's resource-producing future must stay observable even after public cancellation.
        loading.whenComplete((stream, error) -> {
            if (error != null) {
                request.fail(error);
            } else if (stream == null) {
                request.fail(new NullPointerException("loaded audio stream"));
            } else {
                request.loaded(stream);
            }
        });
        return request.result;
    }

    private void loaded(AudioStream stream) {
        AudioStream owned = new CloseOnceAudioStream(stream);
        boolean accepted;
        synchronized (this.lifecycle) {
            accepted = !this.result.isDone();
            if (accepted) {
                this.owned = owned;
            }
        }
        if (!accepted) {
            close(new Resources(owned, null), null);
            return;
        }
        try {
            this.executor.execute(() -> this.modify(owned));
        } catch (RuntimeException error) {
            this.fail(error);
        }
    }

    private void modify(AudioStream owned) {
        if (this.result.isDone()) {
            return;
        }
        AudioStream modified;
        try {
            modified = Objects.requireNonNull(this.modifier.apply(owned), "modified audio stream");
        } catch (Throwable error) {
            this.fail(error);
            return;
        }
        boolean accepted;
        synchronized (this.lifecycle) {
            accepted = !this.result.isDone();
            if (accepted) {
                this.modified = modified;
            }
        }
        if (!accepted) {
            close(new Resources(owned, modified), null);
            return;
        }
        try {
            this.executor.execute(() -> this.publish(modified));
        } catch (RuntimeException error) {
            this.fail(error);
        }
    }

    private void publish(AudioStream stream) {
        if (this.result.isDone()) {
            return;
        }
        try {
            this.success.run();
        } catch (Throwable error) {
            this.retire(error);
            this.result.completeExceptionally(error);
            return;
        }
        this.result.complete(stream);
    }

    private void fail(Throwable error) {
        this.retire(error);
        if (this.result.isDone()) {
            return;
        }
        try {
            this.executor.execute(() -> {
                if (this.result.isDone()) {
                    return;
                }
                try {
                    this.failure.accept(error);
                    this.result.complete(EmptyAudioStream.INSTANCE);
                } catch (Throwable callbackError) {
                    this.result.completeExceptionally(callbackError);
                }
            });
        } catch (RuntimeException rejection) {
            this.result.completeExceptionally(rejection);
        }
    }

    private Resources detach() {
        Resources resources = new Resources(this.owned, this.modified);
        this.owned = null;
        this.modified = null;
        return resources;
    }

    private void retire(Throwable error) {
        Resources resources;
        synchronized (this.lifecycle) {
            resources = this.detach();
        }
        close(resources, error);
    }

    private static void close(Resources resources, Throwable primary) {
        close(resources.modified(), primary);
        close(resources.owned(), primary);
    }

    private static void close(AudioStream stream, Throwable primary) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (Throwable error) {
            while (primary instanceof CompletionException && primary.getCause() != null) {
                primary = primary.getCause();
            }
            if (primary != null && primary != error) {
                primary.addSuppressed(error);
            } else {
                LOGGER.warn("Failed to close retired local legacy audio stream", error);
            }
        }
    }

    private record Resources(AudioStream owned, AudioStream modified) {
    }

    private static final class CloseOnceAudioStream implements AudioStream {
        private final AudioStream stream;
        private final AtomicBoolean closed = new AtomicBoolean();

        private CloseOnceAudioStream(AudioStream stream) {
            this.stream = stream;
        }

        @Override
        public AudioFormat getFormat() {
            return this.stream.getFormat();
        }

        @Override
        public ByteBuffer read(int amount) throws IOException {
            if (this.closed.get()) {
                throw new IOException("Stream is closed");
            }
            return this.stream.read(amount);
        }

        @Override
        public void close() throws IOException {
            if (this.closed.compareAndSet(false, true)) {
                this.stream.close();
            }
        }
    }
}
