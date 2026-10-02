package gg.moonflower.etched.api.sound.source;

import gg.moonflower.etched.api.util.AsyncInputStream;
import gg.moonflower.etched.api.util.DownloadProgressListener;
import net.minecraft.Util;
import net.minecraft.util.HttpUtil;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.net.URL;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * @author Ocelot
 */
public class RawAudioSource implements AudioSource {

    private final CompletableFuture<AsyncInputStream.InputStreamSupplier> locationFuture;
    private final Executor openingExecutor;
    private CompletableFuture<InputStream> stream;

    public RawAudioSource(URL url, @Nullable DownloadProgressListener listener, boolean temporary, AudioFileType type) {
        this(CompletableFuture.supplyAsync(() -> AudioSource.downloadTo(url, temporary, listener, type),
                HttpUtil.DOWNLOAD_EXECUTOR), Util.ioPool());
    }

    RawAudioSource(CompletableFuture<AsyncInputStream.InputStreamSupplier> locationFuture, Executor openingExecutor) {
        this.locationFuture = locationFuture;
        this.openingExecutor = openingExecutor;
    }

    @Override
    public synchronized CompletableFuture<InputStream> openStream() {
        if (this.stream != null) {
            return this.stream;
        }
        return this.stream = LegacyStreamOpenRequests.open(this.locationFuture, this.openingExecutor);
    }
}
