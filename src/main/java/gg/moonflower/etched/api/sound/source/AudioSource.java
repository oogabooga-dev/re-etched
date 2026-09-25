package gg.moonflower.etched.api.sound.source;

import gg.moonflower.etched.api.sound.download.SoundDownloadSource;
import gg.moonflower.etched.api.util.AsyncInputStream;
import gg.moonflower.etched.api.util.DownloadProgressListener;
import gg.moonflower.etched.client.cache.ClientMediaCache;
import gg.moonflower.etched.client.cache.LegacyAudioLoader;
import gg.moonflower.etched.client.radio.AudioCancellation;
import gg.moonflower.etched.client.radio.source.AudioResolveContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.network.chat.Component;
import net.minecraft.util.HttpUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Sources of raw audio data to be played. */
public interface AudioSource {

    Logger LOGGER = LogManager.getLogger();
    long MAX_SIZE = 100 * 1024 * 1024;

    /** @return The vanilla Minecraft client download headers. */
    static Map<String, String> getDownloadHeaders() {
        Map<String, String> map = SoundDownloadSource.getDownloadHeaders();
        User user = Minecraft.getInstance().getUser();
        map.put("X-Minecraft-Username", user.getName());
        map.put("X-Minecraft-UUID", user.getUuid());
        return map;
    }

    static AsyncInputStream.InputStreamSupplier downloadTo(URL url, boolean temporary,
                                                            @Nullable DownloadProgressListener progressListener,
                                                            AudioFileType type) {
        try {
            var uri = url.toURI();
            return () -> {
                if (progressListener != null) {
                    progressListener.progressStartRequest(Component.translatable("resourcepack.requesting"));
                }
                try {
                    InputStream stream;
                    if (type == AudioFileType.FILE && !temporary) {
                        stream = LegacyAudioLoader.file(ClientMediaCache.get(), uri,
                                AudioResolveContext::createDefault, progressListener);
                    } else {
                        AudioCancellation cancellation = new AudioCancellation();
                        stream = new FilterInputStream(new AsyncInputStream(
                                () -> LegacyAudioLoader.stream(uri, cancellation,
                                        AudioResolveContext::createDefault),
                                8192, 8, HttpUtil.DOWNLOAD_EXECUTOR)) {
                            @Override
                            public void close() throws IOException {
                                cancellation.cancel();
                                super.close();
                            }
                        };
                    }
                    return stream;
                } catch (IOException | RuntimeException exception) {
                    if (progressListener != null) {
                        progressListener.onFail();
                    }
                    throw exception;
                }
            };
        } catch (Exception exception) {
            throw new CompletionException(exception);
        }
    }

    /** @return A future to a resource that will exist at some point in the future. */
    CompletableFuture<InputStream> openStream();

    enum AudioFileType {
        FILE(true, false),
        STREAM(false, true),
        BOTH(true, true);

        private final boolean file;
        private final boolean stream;

        AudioFileType(boolean file, boolean stream) {
            this.file = file;
            this.stream = stream;
        }

        public boolean isFile() {
            return this.file;
        }

        public boolean isStream() {
            return this.stream;
        }
    }
}
