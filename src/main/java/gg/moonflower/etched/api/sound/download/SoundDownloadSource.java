package gg.moonflower.etched.api.sound.download;

import gg.moonflower.etched.api.util.DownloadProgressListener;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.Proxy;
import java.util.*;

/**
 * A source for audio to download from besides a direct URL.
 *
 * @author Ocelot
 * @since 2.0.0
 */
public interface SoundDownloadSource {

    /**
     * Resolves the input stream to the cover for the specified album.
     *
     * @param url              The URL to the album
     * @param progressListener The listener for net status
     * @param proxy            The internet proxy
     * @return A stream to the track or <code>{@link Optional#empty()}</code> if there is no cover
     * @throws IOException If any error occurs with requests
     */
    Optional<String> resolveAlbumCover(String url, @Nullable DownloadProgressListener progressListener, Proxy proxy, ResourceManager resourceManager) throws IOException;

    /**
     * Checks to see if the specified URL is for this source.
     *
     * @param url The URL to go to
     * @return Whether that URL is valid
     */
    boolean isValidUrl(String url);

    /**
     * @return The name of this API source
     */
    String getApiName();

    /**
     * Retrieves the special "brand" text for this source.
     *
     * @param url The URL being queried
     * @return The text to display as the brand or nothing
     */
    default Optional<Component> getBrandText(String url) {
        return Optional.empty();
    }
}
