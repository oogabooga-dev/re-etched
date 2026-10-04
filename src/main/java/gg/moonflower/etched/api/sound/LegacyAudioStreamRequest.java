package gg.moonflower.etched.api.sound;

import gg.moonflower.etched.api.sound.source.AudioSource;
import gg.moonflower.etched.client.sound.EmptyAudioStream;
import net.minecraft.client.sounds.AudioStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/** One remote legacy source/open/decode/publication operation. Never discards resource futures. */
final class LegacyAudioStreamRequest {

    private static final Logger LOGGER = LogManager.getLogger();

    private final Object lifecycle = new Object();
    private final CompletableFuture<AudioStream> result = new CompletableFuture<>();
    private final Executor executor;
    private final Function<InputStream, AudioStream> decoder;
    private final Runnable success;
    private final Consumer<Throwable> failure;
    private InputStream input;
    private AudioStream decoded;

    private LegacyAudioStreamRequest(Executor executor, Function<InputStream, AudioStream> decoder,
                                     Runnable success, Consumer<Throwable> failure) {
        this.executor = executor;
        this.decoder = decoder;
        this.success = success;
        this.failure = failure;
        this.result.whenComplete((delivered, error) -> {
            Resources retired;
            synchronized (this.lifecycle) {
                if (delivered != null && delivered == this.decoded) {
                    // CompletableFuture completion is the atomic transfer to the consumer.
                    this.input = null;
                    this.decoded = null;
                    return;
                }
                retired = this.detach();
            }
            close(retired, null);
        });
    }

    static CompletableFuture<AudioStream> start(CompletableFuture<AudioSource> source, Executor executor,
                                               Function<InputStream, AudioStream> decoder, Runnable success,
                                               Consumer<Throwable> failure) {
        return start(cancelled -> source, executor, decoder, success, failure);
    }

    static CompletableFuture<AudioStream> start(Function<BooleanSupplier, CompletableFuture<AudioSource>> factory,
                                               Executor executor, Function<InputStream, AudioStream> decoder,
                                               Runnable success, Consumer<Throwable> failure) {
        var request = new LegacyAudioStreamRequest(executor, decoder, success, failure);
        CompletableFuture<AudioSource> source;
        try {
            source = Objects.requireNonNull(factory.apply(request.result::isCancelled), "source future");
        } catch (Throwable error) {
            request.fail(error);
            return request.result;
        }
        source.whenComplete((resolved, error) -> {
            if (request.result.isDone()) {
                return;
            }
            if (error != null) {
                request.fail(error);
                return;
            }
            try {
                // Do not cancel this future: a third-party producer may otherwise discard a late input.
                Objects.requireNonNull(resolved.openStream(), "open future").whenComplete((input, openError) -> {
                    if (openError != null) {
                        request.fail(openError);
                    } else {
                        request.opened(input);
                    }
                });
            } catch (Throwable openError) {
                request.fail(openError);
            }
        });
        return request.result;
    }

    private void opened(InputStream opened) {
        if (opened == null) {
            this.fail(new NullPointerException("opened input"));
            return;
        }
        InputStream owned = LegacyAudioDecoder.ownInput(opened);
        synchronized (this.lifecycle) {
            if (!this.result.isDone()) {
                this.input = owned;
            }
        }
        if (this.result.isDone()) {
            close(new Resources(owned, null), null);
            return;
        }
        try {
            this.executor.execute(() -> this.decode(owned));
        } catch (RuntimeException error) {
            this.fail(error);
        }
    }

    private void decode(InputStream owned) {
        if (this.result.isDone()) {
            return;
        }
        AudioStream audio;
        try {
            audio = Objects.requireNonNull(this.decoder.apply(owned), "decoded stream");
        } catch (Throwable error) {
            this.fail(error);
            return;
        }
        boolean accepted;
        synchronized (this.lifecycle) {
            accepted = !this.result.isDone();
            if (accepted) {
                this.decoded = audio;
            }
        }
        if (!accepted) {
            close(new Resources(owned, audio), null);
            return;
        }
        try {
            this.executor.execute(() -> this.publish(audio));
        } catch (RuntimeException error) {
            this.fail(error);
        }
    }

    private void publish(AudioStream audio) {
        if (this.result.isDone()) {
            return;
        }
        try {
            this.success.run();
        } catch (Throwable error) {
            // Match the old success-listener exception channel, without invoking onFail again.
            this.retire(error);
            this.result.completeExceptionally(error);
            return;
        }
        this.result.complete(audio);
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
                    Throwable cause = error instanceof CompletionException && error.getCause() != null
                            ? error.getCause() : error;
                    this.failure.accept(cause);
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
        Resources resources = new Resources(this.input, this.decoded);
        this.input = null;
        this.decoded = null;
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
        close(resources.decoded(), primary);
        close(resources.input(), primary);
    }

    private static void close(AutoCloseable resource, Throwable primary) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Throwable error) {
            while (primary instanceof CompletionException && primary.getCause() != null) {
                primary = primary.getCause();
            }
            if (primary != null && primary != error) {
                primary.addSuppressed(error);
            } else {
                LOGGER.warn("Failed to close retired legacy audio resource", error);
            }
        }
    }

    private record Resources(InputStream input, AudioStream decoded) {
    }
}
