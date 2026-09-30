package gg.moonflower.etched.client.radio.source;

import gg.moonflower.etched.common.audio.RadioFailure;

import java.net.URI;
import java.util.Locale;

final class RadioPlaylistUris {

    private RadioPlaylistUris() {
    }

    static URI resolve(URI base, String reference) throws RadioSourceException {
        try {
            URI relative = URI.create(reference);
            URI resolved;
            if (!relative.isAbsolute() && relative.getRawAuthority() == null
                    && relative.getRawPath().isEmpty() && relative.getRawQuery() != null) {
                String rawBase = removeFragment(base.toString());
                int query = rawBase.indexOf('?');
                resolved = URI.create((query >= 0 ? rawBase.substring(0, query) : rawBase)
                        + relative);
            } else {
                resolved = base.resolve(relative);
            }
            String scheme = resolved.getScheme();
            String normalizedScheme = scheme == null ? "" : scheme.toLowerCase(Locale.ROOT);
            if (!resolved.isAbsolute()
                    || !normalizedScheme.equals("http") && !normalizedScheme.equals("https")) {
                throw failure("Radio playlists may contain only HTTP(S) URLs", null);
            }
            if (resolved.getRawFragment() != null) {
                resolved = URI.create(removeFragment(resolved.toString()));
            }
            return resolved;
        } catch (IllegalArgumentException exception) {
            throw failure("Radio playlist contains an invalid URL", exception);
        }
    }

    private static String removeFragment(String uri) {
        int fragment = uri.indexOf('#');
        return fragment >= 0 ? uri.substring(0, fragment) : uri;
    }

    static RadioSourceException failure(String message, Throwable cause) {
        return new RadioSourceException(RadioFailure.Code.UNSUPPORTED_AUDIO, false, message, cause);
    }
}
