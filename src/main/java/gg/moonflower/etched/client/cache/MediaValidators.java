package gg.moonflower.etched.client.cache;

import gg.moonflower.etched.common.audio.AudioCancellation;
import gg.moonflower.etched.common.audio.AudioContentProbe;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.Locale;

/** Validates supported media before promotion and before returning a cache hit. */
public final class MediaValidators {

    public static final int MAX_COVER_DIMENSION = 2048;
    public static final long MAX_COVER_PIXELS = 4_194_304L;

    private MediaValidators() {
    }

    public static void audio(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file,
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] prefix = AudioContentProbe.readPrefix(input, new AudioCancellation(),
                    AudioContentProbe.DEFAULT_SNIFF_BYTES, AudioContentProbe.DEFAULT_MAX_ID3_PREFIX_BYTES);
            AudioContentProbe.Format format = AudioContentProbe.classify(prefix, AudioContentProbe.DEFAULT_MAX_ID3_PREFIX_BYTES);
            if (format != AudioContentProbe.Format.MP3 && format != AudioContentProbe.Format.OGG) {
                throw new UnsupportedAudioException();
            }
        }
    }

    /** A legacy file of another format can be streamed without writing a cache entry. */
    public static final class UnsupportedAudioException extends IOException {
        public UnsupportedAudioException() {
            super("Cache entry is not MP3 or Ogg audio");
        }
    }

    public static void cover(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             ImageInputStream image = ImageIO.createImageInputStream(input)) {
            if (image == null) {
                throw new IOException("Invalid cover image");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(image);
            if (!readers.hasNext()) {
                throw new IOException("Unsupported cover image");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(image, true, true);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!format.equals("png") && !format.equals("jpeg")) {
                    throw new IOException("Only PNG and JPEG covers are supported");
                }
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_COVER_DIMENSION
                        || height > MAX_COVER_DIMENSION || (long) width * height > MAX_COVER_PIXELS) {
                    throw new IOException("Cover dimensions exceed the cache limit");
                }
                if (reader.read(0) == null) {
                    throw new IOException("Invalid cover pixels");
                }
            } catch (RuntimeException exception) {
                throw new IOException("Invalid cover image", exception);
            } finally {
                reader.dispose();
            }
        }
    }
}
