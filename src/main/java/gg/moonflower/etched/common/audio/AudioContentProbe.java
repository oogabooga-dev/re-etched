package gg.moonflower.etched.common.audio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Bounded, side-neutral prefix inspection, not a full decoder or proof of a playable file. */
public final class AudioContentProbe {

    public static final int DEFAULT_SNIFF_BYTES = 8192;
    public static final int DEFAULT_MAX_ID3_PREFIX_BYTES = 256 * 1024;

    private static final int MINIMUM_SNIFF_BYTES = 4;
    private static final int OGG_PAGE_HEADER_BYTES = 27;
    private static final int VORBIS_IDENTIFICATION_BYTES = 30;

    private AudioContentProbe() {
    }

    public enum Format {
        MP3, OGG, AAC, UNKNOWN
    }

    /** The caller retains stream ownership and must replay these bytes if it later decodes the body. */
    public static byte[] readPrefix(InputStream body, AudioCancellation cancellation,
                                    int limit, int id3Limit) throws IOException {
        if (limit < 4 || id3Limit < 4 || id3Limit > 1 << 20) {
            throw new IllegalArgumentException("Invalid audio prefix limits");
        }
        ByteArrayOutputStream prefix = new ByteArrayOutputStream(limit);
        byte[] buffer = new byte[Math.min(8192, Math.max(limit, id3Limit))];
        readUntil(prefix, body, buffer, MINIMUM_SNIFF_BYTES, limit, cancellation);
        if (startsWith(prefix.toByteArray(), "ID3")) {
            readUntil(prefix, body, buffer, 10, id3Limit, cancellation);
            int frame = id3FrameOffset(prefix.toByteArray(), id3Limit);
            if (frame < 0) {
                return prefix.toByteArray();
            }
            readUntil(prefix, body, buffer, frame + MINIMUM_SNIFF_BYTES, id3Limit, cancellation);
        } else if (startsWith(prefix.toByteArray(), "OggS")) {
            readUntil(prefix, body, buffer, OGG_PAGE_HEADER_BYTES, limit, cancellation);
            if (prefix.size() >= OGG_PAGE_HEADER_BYTES) {
                int segments = prefix.toByteArray()[26] & 0xFF;
                if (segments > 0) {
                    readUntil(prefix, body, buffer,
                            OGG_PAGE_HEADER_BYTES + segments + VORBIS_IDENTIFICATION_BYTES,
                            limit, cancellation);
                }
            }
        }
        boolean readTextPrefix = looksLikeTextPrefix(prefix.toByteArray());
        while (prefix.size() < limit) {
            cancellation.throwIfCancelled();
            int available = body.available();
            if (available <= 0 && !readTextPrefix) {
                break;
            }
            int read = body.read(buffer, 0, Math.min(
                    available > 0 ? available : buffer.length, limit - prefix.size()));
            if (read <= 0) {
                break;
            }
            prefix.write(buffer, 0, read);
        }
        return prefix.toByteArray();
    }

    private static void readUntil(ByteArrayOutputStream prefix, InputStream body, byte[] buffer,
                                  int target, int limit, AudioCancellation cancellation) throws IOException {
        int end = Math.min(limit, target);
        while (prefix.size() < end) {
            cancellation.throwIfCancelled();
            int read = body.read(buffer, 0, Math.min(buffer.length, end - prefix.size()));
            if (read < 0) {
                return;
            }
            if (read > 0) {
                prefix.write(buffer, 0, read);
            }
        }
    }

    public static Format classify(byte[] prefix, int maxId3PrefixBytes) {
        if (startsWith(prefix, "OggS")) {
            return hasVorbisIdentification(prefix) ? Format.OGG : Format.UNKNOWN;
        }
        if (hasAdtsSignature(prefix)) {
            return Format.AAC;
        }
        if (hasMpegAudioSignature(prefix, 0)) {
            return Format.MP3;
        }
        if (startsWith(prefix, "ID3")) {
            int frame = id3FrameOffset(prefix, maxId3PrefixBytes);
            return frame >= 0 && hasMpegAudioSignature(prefix, frame) ? Format.MP3 : Format.UNKNOWN;
        }
        return Format.UNKNOWN;
    }

    public static boolean looksLikeTextPrefix(byte[] prefix) {
        if (prefix.length == 0) {
            return false;
        }
        if (startsWith(prefix, "ID3") || startsWith(prefix, "OggS")
                || hasAdtsSignature(prefix) || hasMpegAudioSignature(prefix, 0)) {
            return false;
        }
        // Only inspect the first bytes: later UTF-8 text in a playlist is fine.
        for (int i = 0; i < Math.min(prefix.length, MINIMUM_SNIFF_BYTES); i++) {
            int current = prefix[i] & 0xFF;
            if (current != '\t' && current != '\r' && current != '\n'
                    && (current < 0x20 || current > 0x7E)) {
                return i == 0 && current == 0xEF;
            }
        }
        return true;
    }

    private static boolean hasAdtsSignature(byte[] bytes) {
        return bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xF6) == 0xF0;
    }

    private static boolean hasVorbisIdentification(byte[] bytes) {
        if (bytes.length < OGG_PAGE_HEADER_BYTES + 1 + VORBIS_IDENTIFICATION_BYTES
                || bytes[4] != 0 || bytes[5] != 2) { // First page, begin of stream.
            return false;
        }
        int segments = bytes[26] & 0xFF;
        int packet = OGG_PAGE_HEADER_BYTES + segments;
        if (segments == 0 || bytes.length < packet + VORBIS_IDENTIFICATION_BYTES
                || (bytes[OGG_PAGE_HEADER_BYTES] & 0xFF) != VORBIS_IDENTIFICATION_BYTES
                || bytes[packet] != 1) {
            return false;
        }
        for (int i = 0; i < 6; i++) {
            if (bytes[packet + 1 + i] != "vorbis".charAt(i)) {
                return false;
            }
        }
        // Identification packet: version, channels, sample rate, block sizes, framing bit.
        for (int i = 7; i <= 10; i++) {
            if (bytes[packet + i] != 0) {
                return false;
            }
        }
        int blocks = bytes[packet + 28] & 0xFF;
        int small = blocks & 0x0F;
        int large = blocks >>> 4;
        return bytes[packet + 11] != 0
                && (bytes[packet + 12] != 0 || bytes[packet + 13] != 0
                || bytes[packet + 14] != 0 || bytes[packet + 15] != 0)
                && small >= 6 && large <= 13 && small <= large
                && bytes[packet + 29] == 1;
    }

    private static boolean hasMpegAudioSignature(byte[] bytes, int offset) {
        if (bytes.length - offset < 4 || (bytes[offset] & 0xFF) != 0xFF
                || (bytes[offset + 1] & 0xE0) != 0xE0) {
            return false;
        }
        int version = (bytes[offset + 1] >>> 3) & 0x03;
        int layer = (bytes[offset + 1] >>> 1) & 0x03;
        int bitrate = (bytes[offset + 2] >>> 4) & 0x0F;
        int sampleRate = (bytes[offset + 2] >>> 2) & 0x03;
        return version != 1 && layer != 0 && bitrate != 0 && bitrate != 15 && sampleRate != 3;
    }

    /** Returns the first MPEG byte only when the complete tag and frame header fit the sniff budget. */
    private static int id3FrameOffset(byte[] bytes, int limit) {
        if (bytes.length < 10 || !startsWith(bytes, "ID3")) {
            return -1;
        }
        int version = bytes[3] & 0xFF;
        int flags = bytes[5] & 0xFF;
        if (version < 2 || version > 4 || (bytes[4] & 0xFF) == 0xFF
                || (flags & (version == 2 ? 0x3F : version == 3 ? 0x1F : 0x0F)) != 0) {
            return -1;
        }
        int size = 0;
        for (int i = 6; i < 10; i++) {
            int part = bytes[i] & 0xFF;
            if (part > 0x7F) {
                return -1;
            }
            size = (size << 7) | part;
        }
        long frame = 10L + size + (version == 4 && (flags & 0x10) != 0 ? 10 : 0);
        return frame + MINIMUM_SNIFF_BYTES <= limit ? (int) frame : -1;
    }

    private static boolean startsWith(byte[] bytes, String signature) {
        byte[] expected = signature.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length < expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (bytes[i] != expected[i]) {
                return false;
            }
        }
        return true;
    }
}
