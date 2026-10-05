package gg.moonflower.etched.api.sound.source;

import gg.moonflower.etched.api.util.AccumulatingDownloadProgressListener;
import gg.moonflower.etched.api.util.AsyncInputStream;
import gg.moonflower.etched.api.util.DownloadProgressListener;
import gg.moonflower.etched.api.util.StreamingInputStream;
import gg.moonflower.etched.common.audio.AudioCancellation;
import net.minecraft.client.Minecraft;
import net.minecraft.Util;
import net.minecraft.util.HttpUtil;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.net.URL;
import java.net.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

/**
 * @author Ocelot
 */
public class StreamingAudioSource implements AudioSource {

    private final URL[] urls;
    private final CompletableFuture<?> downloadFuture;
    private final IntFunction<CompletableFuture<AsyncInputStream.InputStreamSupplier>> locations;
    private final Executor openingExecutor;
    private CompletableFuture<InputStream> stream;

    public StreamingAudioSource(URL[] urls, @Nullable DownloadProgressListener progressListener, boolean temporary, AudioFileType type) {
        this(urls, progressListener, temporary, type, Minecraft.getInstance().getProxy(), new AudioCancellation());
    }

    public StreamingAudioSource(URL[] urls, @Nullable DownloadProgressListener progressListener, boolean temporary,
                                AudioFileType type, Proxy proxy, AudioCancellation cancellation) {
        this.urls = urls.clone();
        int files = Math.min(urls.length, 3);
        DownloadProgressListener accumulatingListener = progressListener != null ? new AccumulatingDownloadProgressListener(progressListener, files) : null;
        this.downloadFuture = CompletableFuture.allOf(IntStream.range(0, files).mapToObj(i -> CompletableFuture.runAsync(() -> AudioSource.downloadTo(this.urls[i], temporary, accumulatingListener, type, proxy, cancellation), HttpUtil.DOWNLOAD_EXECUTOR)).toArray(CompletableFuture[]::new));
        this.locations = i -> CompletableFuture.supplyAsync(
                () -> AudioSource.downloadTo(this.urls[i], temporary, null, type, proxy, cancellation), HttpUtil.DOWNLOAD_EXECUTOR);
        this.openingExecutor = Util.ioPool();
    }

    StreamingAudioSource(URL[] urls, CompletableFuture<?> downloadFuture,
                         IntFunction<CompletableFuture<AsyncInputStream.InputStreamSupplier>> locations,
                         Executor openingExecutor) {
        this.urls = urls.clone();
        this.downloadFuture = downloadFuture;
        this.locations = locations;
        this.openingExecutor = openingExecutor;
    }

    @Override
    public synchronized CompletableFuture<InputStream> openStream() {
        if (this.stream == null) {
            CompletableFuture<AsyncInputStream.InputStreamSupplier> location = this.downloadFuture.thenApply(
                    unused -> () -> new StreamingInputStream(this.urls,
                            i -> LegacyStreamOpenRequests.open(this.locations.apply(i), this.openingExecutor)));
            this.stream = LegacyStreamOpenRequests.open(location, this.openingExecutor);
        }
        return this.stream;
    }
}
