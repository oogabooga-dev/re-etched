package gg.moonflower.etched.common.sound.download;

import gg.moonflower.etched.api.sound.download.SoundDownloadSource;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Server-safe client-id discovery for etching-table metadata requests.
 *
 * @author Ocelot
 */
public final class SoundCloudIdTracker {
    private static final int MAX_BYTES = 1024 * 1024;
    private static final Pattern SCRIPTS = Pattern.compile("(?i)<script\\b[^>]*\\bsrc\\s*=\\s*([\"'])(.*?)\\1");
    private static final Pattern CLIENT_ID = Pattern.compile("[\"']?client_id[\"']?\\s*:\\s*[\"']([A-Za-z0-9_-]+)[\"']");
    private static String currentId;

    private SoundCloudIdTracker() {
    }

    public static synchronized void invalidate() {
        currentId = null;
    }

    public static synchronized String fetch(Proxy proxy) {
        if (currentId == null) {
            try {
                currentId = discover(URI.create("https://soundcloud.com/"), proxy);
            } catch (IOException failure) {
                throw new CompletionException("Could not discover SoundCloud client ID", failure);
            }
        }
        return currentId;
    }

    static String discover(URI homepage, Proxy proxy) throws IOException {
        Deque<URI> scripts = new ArrayDeque<>();
        Matcher matches = SCRIPTS.matcher(readPrefix(homepage, proxy));
        while (matches.find()) {
            try {
                URI script = homepage.resolve(matches.group(2));
                if (!"https".equalsIgnoreCase(script.getScheme()) && !"http".equalsIgnoreCase(script.getScheme())) {
                    continue;
                }
                if (scripts.size() == 10) {
                    scripts.removeFirst();
                }
                scripts.addLast(script);
            } catch (IllegalArgumentException ignored) {
            }
        }
        IOException last = null;
        while (!scripts.isEmpty()) {
            try {
                Matcher id = CLIENT_ID.matcher(readPrefix(scripts.removeLast(), proxy));
                if (id.find()) {
                    return id.group(1);
                }
            } catch (IOException failure) {
                last = failure;
            }
        }
        throw new IOException("No SoundCloud client ID found in application scripts", last);
    }

    private static String readPrefix(URI uri, Proxy proxy) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection(proxy);
        try {
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(15_000);
            SoundDownloadSource.getDownloadHeaders().forEach(connection::setRequestProperty);
            if (connection.getResponseCode() != 200) {
                throw new IOException("SoundCloud discovery returned HTTP " + connection.getResponseCode());
            }
            try (var input = connection.getInputStream()) {
                return new String(input.readNBytes(MAX_BYTES), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }
}
