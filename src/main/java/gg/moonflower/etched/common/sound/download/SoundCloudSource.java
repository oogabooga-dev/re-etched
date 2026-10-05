package gg.moonflower.etched.common.sound.download;

import com.google.gson.JsonParseException;
import gg.moonflower.etched.api.record.TrackData;
import gg.moonflower.etched.api.sound.download.SoundDownloadSource;
import gg.moonflower.etched.api.util.DownloadProgressListener;
import gg.moonflower.etched.common.audio.AudioCancellation;
import gg.moonflower.etched.common.audio.provider.SoundCloudMetadataResolver;
import gg.moonflower.etched.common.audio.provider.SoundCloudPageReader;
import gg.moonflower.etched.core.Etched;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.Proxy;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Compatibility facade; all page and transcoding requests use the common secure transport. */
public class SoundCloudSource implements SoundDownloadSource {

    private static final Component BRAND = Component.translatable("sound_source." + Etched.MOD_ID + ".sound_cloud").withStyle(style -> style.withColor(TextColor.fromRgb(0xFF5500)));

    private final Function<Proxy, SoundCloudMetadataResolver> resolvers;

    public SoundCloudSource() {
        this(SoundCloudMetadataResolver::new);
    }

    SoundCloudSource(Function<Proxy, SoundCloudMetadataResolver> resolvers) {
        this.resolvers = Objects.requireNonNull(resolvers, "resolvers");
    }

    @Override
    public List<TrackData> resolveTracks(String url, @Nullable DownloadProgressListener progressListener, Proxy proxy) throws IOException, JsonParseException {
        URI input = input(url);
        this.startRequest(progressListener);
        return this.resolvers.apply(proxy).resolveTracks(input, new AudioCancellation());
    }

    @Override
    public Optional<String> resolveAlbumCover(String url, @Nullable DownloadProgressListener progressListener, Proxy proxy, ResourceManager resourceManager) throws IOException {
        URI input = input(url);
        this.startRequest(progressListener);
        return this.resolvers.apply(proxy).resolveAlbumCover(input, new AudioCancellation()).map(URI::toString);
    }

    private void startRequest(@Nullable DownloadProgressListener listener) {
        if (listener != null) {
            listener.progressStartRequest(Component.translatable("sound_source." + Etched.MOD_ID + ".requesting", this.getApiName()));
        }
    }

    private static URI input(String url) throws IOException {
        try {
            URI uri = URI.create(url);
            if (!SoundCloudPageReader.supports(uri)) {
                throw new IOException("SoundCloud URL must use HTTP(S) on soundcloud.com without user info");
            }
            return uri;
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IOException("Invalid SoundCloud URL", exception);
        }
    }

    @Override
    public boolean isValidUrl(String url) {
        try {
            return url != null && SoundCloudPageReader.supports(URI.create(url));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    @Override
    public String getApiName() {
        return "SoundCloud";
    }

    @Override
    public Optional<Component> getBrandText(String url) {
        return Optional.of(BRAND);
    }
}
