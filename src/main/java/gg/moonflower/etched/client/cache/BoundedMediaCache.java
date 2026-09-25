package gg.moonflower.etched.client.cache;

import gg.moonflower.etched.client.radio.AudioCancellation;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** One bounded disk cache with separate audio and cover budgets. Never reads legacy cache paths. */
public final class BoundedMediaCache {

    private final Path root;
    private final Clock clock;
    private final Map<Namespace, Store> stores = new HashMap<>();

    public BoundedMediaCache(Path root) throws IOException {
        this(root, Clock.systemUTC(), Limits.AUDIO, Limits.COVERS);
    }

    BoundedMediaCache(Path root, Clock clock, Limits audio, Limits covers) throws IOException {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.clock = Objects.requireNonNull(clock, "clock");
        directory(this.root);
        this.stores.put(Namespace.AUDIO, new Store(Namespace.AUDIO, audio));
        this.stores.put(Namespace.COVERS, new Store(Namespace.COVERS, covers));
    }

    /** Each caller receives its own pinned file stream. A cancelled waiter never cancels other waiters. */
    public Lease acquire(Namespace namespace, String request, AudioCancellation cancellation,
                         Loader loader, Validator validator) throws IOException {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(validator, "validator");
        cancellation.throwIfCancelled();
        Store store = this.stores.get(namespace);
        String hash = sha256(request);
        Load load;
        boolean producer = false;
        synchronized (store) {
            Entry entry = store.entries.get(hash);
            if (entry != null) {
                if (!store.expired(entry, this.clock.millis())) {
                    try {
                        return store.open(entry, validator);
                    } catch (IOException invalid) {
                        if (store.entries.get(hash) != null) {
                            throw invalid;
                        }
                        // A malformed on-disk entry was discarded; fetch it again.
                    }
                } else {
                    store.remove(entry);
                }
            }
            load = store.loads.get(hash);
            if (load == null || load.cancellation.isCancelled()) {
                if (store.loads.size() >= 16) {
                    throw new IOException("Too many cache downloads are active");
                }
                load = new Load();
                store.loads.put(hash, load);
                producer = true;
            }
            load.waiters++;
        }
        Load pending = load;
        AtomicBoolean detached = new AtomicBoolean();
        Runnable detach = () -> {
            if (detached.compareAndSet(false, true)) {
                store.detach(pending);
            }
        };
        cancellation.onCancel(detach);
        try {
            cancellation.throwIfCancelled();
            if (producer) {
                try {
                    store.download(hash, pending, loader, validator);
                    pending.done.complete(null);
                } catch (Throwable failure) {
                    pending.done.completeExceptionally(failure);
                } finally {
                    synchronized (store) {
                        store.loads.remove(hash, pending);
                    }
                }
            }
            while (true) {
                cancellation.throwIfCancelled();
                try {
                    pending.done.get(50L, TimeUnit.MILLISECONDS);
                    break;
                } catch (TimeoutException ignored) {
                    // Wake periodically to honor cancellation without cancelling the shared download.
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for cache media", exception);
                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause();
                    if (cause instanceof IOException io) {
                        throw io;
                    }
                    if (cause instanceof RuntimeException runtime) {
                        throw runtime;
                    }
                    throw new IOException("Could not cache media", cause);
                }
            }
            cancellation.throwIfCancelled();
            synchronized (store) {
                Entry entry = store.entries.get(hash);
                if (entry == null) {
                    throw new IOException("Cache download did not produce an entry");
                }
                return store.open(entry, validator);
            }
        } finally {
            detach.run();
        }
    }

    public enum Namespace {
        AUDIO("audio"), COVERS("covers");

        private final String directory;

        Namespace(String directory) {
            this.directory = directory;
        }
    }

    public record Limits(long perEntryBytes, long totalBytes, int maxEntries, Duration maxAge) {
        public static final Limits AUDIO = new Limits(100L << 20, 512L << 20, 256, Duration.ofDays(7));
        public static final Limits COVERS = new Limits(8L << 20, 64L << 20, 512, Duration.ofDays(1));

        public Limits {
            if (perEntryBytes < 1 || totalBytes < perEntryBytes || maxEntries < 1
                    || Objects.requireNonNull(maxAge, "maxAge").isNegative() || maxAge.isZero()) {
                throw new IllegalArgumentException("Invalid cache limits");
            }
        }
    }

    @FunctionalInterface
    public interface Loader {
        Content load(AudioCancellation cancellation) throws IOException;
    }

    @FunctionalInterface
    public interface Validator {
        void validate(Path file) throws IOException;
    }

    /** The loader owns its response until closed; advertised size is only an early rejection hint. */
    public record Content(InputStream body, long advertisedBytes) implements AutoCloseable {
        public Content {
            Objects.requireNonNull(body, "body");
        }

        @Override
        public void close() throws IOException {
            this.body.close();
        }
    }

    public final class Lease implements AutoCloseable {
        private final Store store;
        private final Entry entry;
        private final InputStream stream;
        private boolean closed;

        private Lease(Store store, Entry entry, InputStream stream) {
            this.store = store;
            this.entry = entry;
            this.stream = stream;
        }

        public InputStream body() {
            return this.stream;
        }

        @Override
        public void close() throws IOException {
            synchronized (this.store) {
                if (this.closed) {
                    return;
                }
                this.closed = true;
                try {
                    this.stream.close();
                } finally {
                    this.entry.pins--;
                }
            }
        }
    }

    private final class Store {
        private final Path path;
        private final Limits limits;
        private final Map<String, Entry> entries = new HashMap<>();
        private final Map<String, Load> loads = new HashMap<>();
        private long bytes;

        private Store(Namespace namespace, Limits limits) throws IOException {
            this.limits = Objects.requireNonNull(limits, "limits");
            this.path = root.resolve(namespace.directory);
            directory(this.path);
            Validator validator = namespace == Namespace.AUDIO ? MediaValidators::audio : MediaValidators::cover;
            try (DirectoryStream<Path> files = Files.newDirectoryStream(this.path)) {
                for (Path file : files) {
                    String name = file.getFileName().toString();
                    if (name.matches("[0-9a-f]{64}-.*\\.part")) {
                        Files.deleteIfExists(file);
                    } else if (name.matches("[0-9a-f]{64}\\.bin")) {
                        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                            Files.deleteIfExists(file);
                            continue;
                        }
                        long size = Files.size(file);
                        long written = Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toMillis();
                        boolean invalid = size == 0 || size > limits.perEntryBytes()
                                || clock.millis() - written > limits.maxAge().toMillis()
                                || written > clock.millis();
                        if (!invalid) {
                            try {
                                validator.validate(file);
                            } catch (IOException exception) {
                                invalid = true;
                            }
                        }
                        if (invalid) {
                            Files.deleteIfExists(file);
                            continue;
                        }
                        Entry entry = new Entry(file, size, written);
                        this.entries.put(name.substring(0, 64), entry);
                        this.bytes += size;
                    } else {
                        // Unknown files are not ours; leave them untouched.
                    }
                }
            }
            this.evict(0L, 0);
        }

        private boolean expired(Entry entry, long now) {
            return now - entry.written >= this.limits.maxAge().toMillis() || now < entry.written;
        }

        private Lease open(Entry entry, Validator validator) throws IOException {
            if (!Files.isRegularFile(entry.file, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(entry.file) != entry.size || this.expired(entry, clock.millis())) {
                this.remove(entry);
                throw new IOException("Cache entry changed or expired");
            }
            try {
                validator.validate(entry.file);
            } catch (IOException exception) {
                this.remove(entry);
                throw exception;
            }
            InputStream stream = Files.newInputStream(entry.file,
                    StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            entry.pins++;
            entry.accessed = clock.millis();
            return new Lease(this, entry, stream);
        }

        private void download(String hash, Load pending, Loader loader, Validator validator) throws IOException {
            Path part = Files.createTempFile(this.path, hash + "-", ".part");
            try {
                pending.cancellation.throwIfCancelled();
                long count = 0L;
                try (Content content = loader.load(pending.cancellation);
                     OutputStream output = Files.newOutputStream(part,
                             StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                    if (content.advertisedBytes() > this.limits.perEntryBytes()) {
                        throw new IOException("Cache entry exceeds byte limit");
                    }
                    pending.cancellation.onCancel(() -> {
                        try {
                            content.close();
                        } catch (IOException ignored) {
                        }
                    });
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = content.body().read(buffer)) != -1) {
                        pending.cancellation.throwIfCancelled();
                        count += read;
                        if (count > this.limits.perEntryBytes()) {
                            throw new IOException("Cache entry exceeds byte limit");
                        }
                        output.write(buffer, 0, read);
                    }
                }
                pending.cancellation.throwIfCancelled();
                if (count == 0) {
                    throw new IOException("Empty cache entry");
                }
                validator.validate(part);
                synchronized (this) {
                    pending.cancellation.throwIfCancelled();
                    this.evict(count, 1);
                    Path destination = this.path.resolve(hash + ".bin");
                    if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("Cache destination already exists");
                    }
                    Files.move(part, destination, StandardCopyOption.ATOMIC_MOVE);
                    long now = clock.millis();
                    Files.setLastModifiedTime(destination, java.nio.file.attribute.FileTime.fromMillis(now));
                    this.entries.put(hash, new Entry(destination, count, now));
                    this.bytes += count;
                }
            } finally {
                Files.deleteIfExists(part);
            }
        }

        private void evict(long incoming, int entriesAdded) throws IOException {
            while (this.entries.size() + entriesAdded > this.limits.maxEntries()
                    || this.bytes + incoming > this.limits.totalBytes()) {
                Entry oldest = this.entries.values().stream().filter(entry -> entry.pins == 0)
                        .min(Comparator.comparingLong(entry -> entry.accessed)).orElse(null);
                if (oldest == null) {
                    throw new IOException("All cache entries are in use");
                }
                this.remove(oldest);
            }
        }

        private void remove(Entry entry) throws IOException {
            if (entry.pins != 0) {
                throw new IOException("Cache entry is in use");
            }
            Files.deleteIfExists(entry.file);
            this.entries.values().remove(entry);
            this.bytes -= entry.size;
        }

        private void detach(Load pending) {
            boolean cancel;
            synchronized (this) {
                if (pending.waiters == 0) {
                    return;
                }
                cancel = --pending.waiters == 0 && !pending.done.isDone();
            }
            if (cancel) {
                pending.cancellation.cancel();
            }
        }
    }

    private static final class Entry {
        private final Path file;
        private final long size;
        private final long written;
        private long accessed;
        private int pins;

        private Entry(Path file, long size, long written) {
            this.file = file;
            this.size = size;
            this.written = written;
            this.accessed = written;
        }
    }

    private static final class Load {
        private final AudioCancellation cancellation = new AudioCancellation();
        private final CompletableFuture<Void> done = new CompletableFuture<>();
        private int waiters;
    }

    private static void directory(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Cache root must be a real directory");
            }
        } else {
            Files.createDirectory(path);
        }
    }

    private static String sha256(String value) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        return java.util.HexFormat.of().formatHex(digest);
    }
}
