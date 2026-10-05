package gg.moonflower.etched.common.sound.download;

import com.google.gson.JsonParseException;
import gg.moonflower.etched.api.record.TrackData;
import gg.moonflower.etched.api.sound.download.SoundDownloadSource;
import gg.moonflower.etched.api.util.DownloadProgressListener;
import gg.moonflower.etched.common.audio.AudioCancellation;
import gg.moonflower.etched.common.audio.provider.BandcampMetadataResolver;
import gg.moonflower.etched.common.audio.provider.BandcampPageReader;
import gg.moonflower.etched.core.Etched;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Compatibility facade; all page requests use the common bounded, proxy-aware transport. */
public class BandcampSource implements SoundDownloadSource {

    private static final Component BRAND = Component.translatable("sound_source." + Etched.MOD_ID + ".bandcamp").withStyle(style -> style.withColor(TextColor.fromRgb(0x477987)));

    private final Function<Proxy, BandcampMetadataResolver> resolvers;

    public BandcampSource() {
        this(BandcampMetadataResolver::new);
    }

    BandcampSource(Function<Proxy, BandcampMetadataResolver> resolvers) {
        this.resolvers = Objects.requireNonNull(resolvers, "resolvers");
    }

    @Override
    public List<URL> resolveUrl(String url, @Nullable DownloadProgressListener progressListener, Proxy proxy) throws IOException {
        URI input = input(url);
        this.startRequest(progressListener);
        List<URI> media = this.resolvers.apply(proxy).resolveMediaUrls(input, new AudioCancellation());
        if (progressListener != null) {
            progressListener.progressStartRequest(RESOLVING_TRACKS);
        }
        List<URL> urls = new ArrayList<>(media.size());
        for (URI uri : media) {
            urls.add(uri.toURL());
        }
        return urls;
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
            if (!BandcampPageReader.supports(uri)) {
                throw new IOException("Bandcamp URL must use HTTP(S) on bandcamp.com without user info");
            }
            return uri;
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IOException("Invalid Bandcamp URL", exception);
        }
    }

    @Override
    public boolean isValidUrl(String url) {
        try {
            return url != null && BandcampPageReader.supports(URI.create(url));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    @Override
    public boolean isTemporary(String url) {
        return true;
    }

    @Override
    public String getApiName() {
        return "Bandcamp";
    }

    @Override
    public Optional<Component> getBrandText(String url) {
        return Optional.of(BRAND);
    }
}
