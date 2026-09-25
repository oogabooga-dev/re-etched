package gg.moonflower.etched.client.sound;

import gg.moonflower.etched.api.sound.download.SoundSourceManager;
import gg.moonflower.etched.api.sound.source.AudioSource;
import gg.moonflower.etched.api.util.DownloadProgressListener;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.net.MalformedURLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Coalesces legacy provider resolution only; media bytes use the v5 cache or bypass disk. */
@ApiStatus.Internal
public final class SoundCache {

    private static final ConcurrentMap<Request, CompletableFuture<AudioSource>> IN_FLIGHT =
            new ConcurrentHashMap<>();

    private SoundCache() {
    }

    public static CompletableFuture<AudioSource> getAudioStream(String url,
                                                                 @Nullable DownloadProgressListener listener,
                                                                 AudioSource.AudioFileType type) {
        Request request = new Request(url, type);
        CompletableFuture<AudioSource> pending = IN_FLIGHT.computeIfAbsent(request, ignored -> {
            try {
                return SoundSourceManager.getAudioSource(url, listener, Minecraft.getInstance().getProxy(), type);
            } catch (MalformedURLException exception) {
                return CompletableFuture.failedFuture(exception);
            }
        });
        pending.whenComplete((source, failure) -> {
            IN_FLIGHT.remove(request, pending);
            if (failure != null && listener != null) {
                listener.onFail();
            }
        });
        return pending;
    }

    private record Request(String url, AudioSource.AudioFileType type) {
    }
}
