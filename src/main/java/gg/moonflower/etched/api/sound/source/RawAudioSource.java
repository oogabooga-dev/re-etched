package gg.moonflower.etched.api.sound.source;

import gg.moonflower.etched.api.util.AsyncInputStream;
import gg.moonflower.etched.api.util.DownloadProgressListener;
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

/**
 * @author Ocelot
 */
public class RawAudioSource implements AudioSource {

    private final CompletableFuture<AsyncInputStream.InputStreamSupplier> locationFuture;
    private final Executor openingExecutor;
    private CompletableFuture<InputStream> stream;

    public RawAudioSource(URL url, @Nullable DownloadProgressListener listener, boolean temporary, AudioFileType type) {
        this(url, listener, temporary, type, Minecraft.getInstance().getProxy(), new AudioCancellation());
    }

    public RawAudioSource(URL url, @Nullable DownloadProgressListener listener, boolean temporary, AudioFileType type,
                          Proxy proxy, AudioCancellation cancellation) {
        this(CompletableFuture.supplyAsync(() -> AudioSource.downloadTo(url, temporary, listener, type, proxy, cancellation),
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
