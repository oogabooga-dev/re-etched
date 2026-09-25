package gg.moonflower.etched.client.cache;

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
        byte[] prefix = new byte[4];
        try (InputStream input = Files.newInputStream(file,
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            if (input.readNBytes(prefix, 0, prefix.length) < prefix.length) {
                throw new IOException("Incomplete audio header");
            }
        }
        boolean ogg = prefix[0] == 'O' && prefix[1] == 'g' && prefix[2] == 'g' && prefix[3] == 'S';
        boolean id3 = prefix[0] == 'I' && prefix[1] == 'D' && prefix[2] == '3';
        boolean mp3 = (prefix[0] & 0xFF) == 0xFF && (prefix[1] & 0xE0) == 0xE0
                && (prefix[1] & 0x18) != 0x08 && (prefix[1] & 0x06) != 0;
        if (!ogg && !id3 && !mp3) {
            throw new UnsupportedAudioException();
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
