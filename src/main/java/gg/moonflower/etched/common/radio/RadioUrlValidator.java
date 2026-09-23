package gg.moonflower.etched.common.radio;

import gg.moonflower.etched.common.network.EtchedProtocol;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Performs the shared syntactic validation used by the radio screen and server menu. */
public final class RadioUrlValidator {

    public static final int MAX_LENGTH = EtchedProtocol.MAX_URL_LENGTH;

    private RadioUrlValidator() {
    }

    public static Result validate(String value) {
        if (value == null || value.length() > MAX_LENGTH) {
            return Result.invalid();
        }

        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return Result.valid("");
        }

        try {
            URI uri = new URI(normalized);
            String scheme = uri.getScheme();
            if (!uri.isAbsolute() || scheme == null
                    || !switch (scheme.toLowerCase(Locale.ROOT)) {
                case "http", "https" -> true;
                default -> false;
            }
                    || uri.getHost() == null || uri.getHost().isBlank()
                    || uri.getRawUserInfo() != null
                    || uri.getPort() == 0 || uri.getPort() > 65_535
                    || uri.getHost().contains("%")) {
                return Result.invalid();
            }
            return Result.valid(normalized);
        } catch (URISyntaxException exception) {
            return Result.invalid();
        }
    }

    public record Result(boolean valid, String normalized) {

        private static Result valid(String normalized) {
            return new Result(true, normalized);
        }

        private static Result invalid() {
            return new Result(false, "");
        }
    }
}
