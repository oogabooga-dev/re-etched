package gg.moonflower.etched.client.sound;

import gg.moonflower.etched.api.sound.download.SoundSourceManager;
import gg.moonflower.etched.api.sound.source.AudioSource;
import gg.moonflower.etched.api.util.DownloadProgressListener;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.net.MalformedURLException;
import java.util.concurrent.CompletableFuture;

/** Legacy request entrypoint. Only media bytes may be cached, never sources or opened streams. */
@ApiStatus.Internal
public final class SoundCache {

    private SoundCache() {
    }

    public static CompletableFuture<AudioSource> getAudioStream(String url,
                                                                 @Nullable DownloadProgressListener listener,
                                                                 AudioSource.AudioFileType type) {
        return getAudioStream(url, listener, type, (input, progress, fileType) ->
                SoundSourceManager.getAudioSource(input, progress, Minecraft.getInstance().getProxy(), fileType));
    }

    static CompletableFuture<AudioSource> getAudioStream(String url, @Nullable DownloadProgressListener listener,
                                                        AudioSource.AudioFileType type, SourceResolver resolver) {
        // RawAudioSource and StreamingAudioSource memoize their opened stream: sharing a source
        // across requests also shares decoder input and lets one consumer close another's stream.
        CompletableFuture<AudioSource> pending;
        try {
            pending = resolver.resolve(url, listener, type);
        } catch (MalformedURLException exception) {
            pending = CompletableFuture.failedFuture(exception);
        }
        pending.whenComplete((source, failure) -> {
            if (failure != null && listener != null) {
                listener.onFail();
            }
        });
        return pending;
    }

    @FunctionalInterface
    interface SourceResolver {
        CompletableFuture<AudioSource> resolve(String url, @Nullable DownloadProgressListener listener,
                                               AudioSource.AudioFileType type) throws MalformedURLException;
    }
}
