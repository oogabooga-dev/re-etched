package gg.moonflower.etched.client.radio;

import gg.moonflower.etched.client.radio.source.*;
import gg.moonflower.etched.client.radio.stream.RadioAudioStream;
import gg.moonflower.etched.client.radio.stream.RadioStreamPipeline;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** Opens one disc track using the radio transport, buffering and decoders. No shared read cursors. */
public final class RecordStreamLoader {

    private final RadioSourceProgramResolver resolver;
    private final Function<RadioCancellation, RadioResolveContext> contexts;
    private final ExecutorService resolvers;
    private final ExecutorService producers;
    private final ExecutorService decoders;
    private final Semaphore slots = new Semaphore(8);
    private final Set<Load> active = ConcurrentHashMap.newKeySet();
    private boolean closed;

    public RecordStreamLoader(RadioSourceProgramResolver resolver,
                       Function<RadioCancellation, RadioResolveContext> contexts,
                       ExecutorService resolvers, ExecutorService producers, ExecutorService decoders) {
        this.resolver = resolver;
        this.contexts = contexts;
        this.resolvers = resolvers;
        this.producers = producers;
        this.decoders = decoders;
    }

    public static RecordStreamLoader getInstance() {
        return Holder.INSTANCE;
    }

    public synchronized Load open(String url, boolean stereo) {
        Load load = new Load();
        if (this.closed || !this.slots.tryAcquire()) {
            load.result.completeExceptionally(new RejectedExecutionException("Too many online discs playing"));
            return load;
        }
        this.active.add(load);
        load.cancellation.onCancel(() -> {
            this.active.remove(load);
            this.slots.release();
        });
        try {
            Future<?> task = this.resolvers.submit(() -> {
                RadioResolvedSource source = null;
                try {
                    RadioResolveContext context = this.contexts.apply(load.cancellation);
                    RadioSourceProgram program = this.resolver.resolveProgram(URI.create(url), context);
                    // Etched stores album tracks separately in TrackData. Never silently truncate an album URL.
                    if (program.tracks().size() != 1) {
                        throw new IllegalArgumentException("Record track URL must resolve to one track");
                    }
                    load.cancellation.throwIfCancelled();
                    source = program.openTrack(0, this.contexts.apply(load.cancellation));
                    load.cancellation.throwIfCancelled();
                    RadioStreamPipeline.Preparation preparation = RadioStreamPipeline.prepare(source,
                            load.cancellation, this.producers, this.decoders, stereo);
                    source = null;
                    load.install(preparation);
                } catch (Exception failure) {
                    if (source != null) {
                        source.close();
                    }
                    load.result.completeExceptionally(failure);
                    load.close();
                }
            });
            load.cancellation.onCancel(() -> {
                task.cancel(true);
                if (this.resolvers instanceof ThreadPoolExecutor pool && task instanceof Runnable runnable) {
                    pool.remove(runnable);
                }
            });
        } catch (RejectedExecutionException failure) {
            load.result.completeExceptionally(failure);
            load.close();
        }
        return load;
    }

    public synchronized void shutdown() {
        this.closed = true;
        List.copyOf(this.active).forEach(Load::close);
        this.resolvers.shutdownNow();
        this.producers.shutdownNow();
        this.decoders.shutdownNow();
    }

    public static final class Load implements AutoCloseable {
        private final RadioCancellation cancellation = new RadioCancellation();
        private final CompletableFuture<RadioAudioStream> result = new CompletableFuture<>();
        private RadioStreamPipeline.Preparation preparation;

        private Load() {
            this.cancellation.onCancel(() -> this.result.cancel(false));
        }

        private void install(RadioStreamPipeline.Preparation preparation) {
            synchronized (this) {
                if (this.cancellation.isCancelled()) {
                    preparation.close();
                    return;
                }
                this.preparation = preparation;
            }
            preparation.stream().whenComplete((audio, failure) -> {
                if (failure != null) {
                    this.result.completeExceptionally(failure);
                    this.close();
                } else {
                    this.result.complete(audio);
                }
            });
        }

        public CompletableFuture<RadioAudioStream> stream() {
            return this.result;
        }

        /** Called exactly when handing the prepared stream to SoundEngine. */
        public synchronized boolean transfer(RadioAudioStream audio) {
            if (this.cancellation.isCancelled() || this.preparation == null) {
                return false;
            }
            boolean transferred = this.preparation.transfer(audio);
            if (transferred) {
                this.preparation.close();
                this.preparation = null;
            }
            return transferred;
        }

        @Override
        public void close() {
            this.cancellation.cancel();
            RadioStreamPipeline.Preparation pending;
            synchronized (this) {
                pending = this.preparation;
                this.preparation = null;
            }
            if (pending != null) {
                pending.close();
            }
        }
    }

    private static ExecutorService executor(String name, int threads) {
        AtomicInteger number = new AtomicInteger();
        return new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(32), task -> {
                    Thread thread = new Thread(task, name + " " + number.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    private static final class Holder {
        private static final RecordStreamLoader INSTANCE = new RecordStreamLoader(
                new CompositeRadioSourceResolver(List.of(new SoundCloudRadioSourceResolver(),
                        new BandcampRadioSourceResolver(), new DirectRadioSourceResolver(true))),
                RadioResolveContext::createDefault, executor("Etched disc resolver", 2),
                executor("Etched disc producer", 8), executor("Etched disc decoder", 2));
    }
}
