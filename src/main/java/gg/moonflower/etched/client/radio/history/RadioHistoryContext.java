package gg.moonflower.etched.client.radio.history;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.world.level.storage.LevelResource;

import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Creates privacy-preserving history partitions for the current world or server. */
public final class RadioHistoryContext {

    private static final Pattern KEY_PATTERN = Pattern.compile("(?:mp|sp):[0-9a-f]{64}");

    private RadioHistoryContext() {
    }

    public static Optional<String> resolve(Minecraft minecraft) {
        if (minecraft.hasSingleplayerServer() && minecraft.getSingleplayerServer() != null) {
            Path world = minecraft.getSingleplayerServer().getWorldPath(LevelResource.ROOT);
            return Optional.of(singleplayerKey(world, minecraft.getLevelSource().getBaseDir()));
        }

        ServerData server = minecraft.getCurrentServer();
        if (server == null || server.ip == null || server.ip.isBlank()) {
            return Optional.empty();
        }
        return multiplayerKey(server.ip);
    }

    static Optional<String> multiplayerKey(String address) {
        if (address == null || address.isBlank()) {
            return Optional.empty();
        }
        ServerAddress parsed = ServerAddress.parseString(address.trim());
        String host = parsed.getHost();
        if (host == null || host.isBlank()) {
            return Optional.empty();
        }

        host = canonicalHost(host);
        return host.isEmpty()
                ? Optional.empty()
                : Optional.of("mp:" + hash("multiplayer\0" + host + "\0" + parsed.getPort()));
    }

    static String singleplayerKey(Path world, Path saves) {
        Path normalizedWorld = normalize(world);
        Path normalizedSaves = normalize(saves);
        String identity = normalizedWorld.startsWith(normalizedSaves)
                ? normalizedSaves.relativize(normalizedWorld).toString()
                : normalizedWorld.toString();
        identity = identity.replace(normalizedWorld.getFileSystem().getSeparator(), "/");
        return "sp:" + hash("singleplayer\0" + identity);
    }

    public static boolean isValidKey(String key) {
        return key != null && KEY_PATTERN.matcher(key).matches();
    }

    private static String canonicalHost(String value) {
        String host = value.trim().toLowerCase(Locale.ROOT);
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.isEmpty() || host.indexOf(':') >= 0) {
            return host;
        }
        try {
            return IDN.toASCII(host).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException exception) {
            return "";
        }
    }

    private static Path normalize(Path path) {
        try {
            return path.toRealPath();
        } catch (Exception exception) {
            return path.toAbsolutePath().normalize();
        }
    }

    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
