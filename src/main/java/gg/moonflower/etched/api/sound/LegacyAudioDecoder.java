package gg.moonflower.etched.api.sound;

import com.mojang.blaze3d.audio.OggAudioStream;
import gg.moonflower.etched.api.sound.stream.MonoWrapper;
import gg.moonflower.etched.api.sound.stream.RawAudioStream;
import gg.moonflower.etched.api.util.Mp3InputStream;
import gg.moonflower.etched.api.util.WaveDataReader;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.LoopingAudioStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

/** Legacy synchronous decode ownership. Codec probing never includes modifier/listener failures. */
final class LegacyAudioDecoder {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final int MARK_LIMIT = 4192;

    private LegacyAudioDecoder() {
    }

    static AudioStream decode(InputStream input, Sound sound, boolean repeat, Runnable loading) {
        return decode(input, loading, stream -> sound instanceof SoundStreamModifier modifier
                ? modifier.modifyStream(stream) : new MonoWrapper(stream), codecs(repeat));
    }

    static List<Decoder> codecs(boolean repeat) {
        return List.of(
                input -> repeat ? new LoopingAudioStream(OggAudioStream::new, input) : new OggAudioStream(input),
                input -> {
                    AudioInputStream wave = WaveDataReader.getAudioInputStream(input);
                    AudioFormat format = wave.getFormat();
                    return repeat ? new LoopingAudioStream(stream -> new RawAudioStream(format, stream), wave)
                            : new RawAudioStream(format, wave);
                },
                input -> {
                    Mp3InputStream mp3 = new Mp3InputStream(input);
                    return repeat ? new LoopingAudioStream(stream -> new RawAudioStream(mp3.getFormat(), stream), mp3)
                            : new RawAudioStream(mp3.getFormat(), mp3);
                });
    }

    static AudioStream decode(InputStream input, Runnable loading, UnaryOperator<AudioStream> modifier,
                              List<Decoder> codecs) {
        InputStream buffered = new BufferedInputStream(new CloseOnceInput(input));
        AudioStream decoded = null;
        try {
            loading.run();
            decoded = openCodec(buffered, codecs);
            AudioStream modified = Objects.requireNonNull(modifier.apply(decoded), "modified audio stream");
            return new OwnedDecodedStream(modified, buffered);
        } catch (Throwable failure) {
            if (decoded != null) {
                closeAfterFailure(decoded, failure);
            }
            closeAfterFailure(buffered, failure);
            throw completion(failure);
        }
    }

    private static AudioStream openCodec(InputStream input, List<Decoder> codecs) throws Exception {
        List<Exception> failures = new ArrayList<>();
        for (int i = 0; i < codecs.size(); i++) {
            input.mark(MARK_LIMIT);
            try {
                return Objects.requireNonNull(codecs.get(i).open(input), "decoded audio stream");
            } catch (Exception failure) {
                LOGGER.debug("Failed to load legacy audio with codec {}", i, failure);
                failures.add(failure);
            }
            if (i + 1 < codecs.size()) {
                try {
                    input.reset();
                } catch (IOException failure) {
                    failures.forEach(failure::addSuppressed);
                    throw failure;
                }
            }
        }
        UnsupportedAudioFileException failure = new UnsupportedAudioFileException("Could not load as OGG, WAV, OR MP3");
        failures.forEach(failure::addSuppressed);
        throw failure;
    }

    static AudioStream publish(AudioStream stream, Runnable success) {
        try {
            success.run();
            return stream;
        } catch (Throwable failure) {
            closeAfterFailure(stream, failure);
            throw completion(failure);
        }
    }

    private static CompletionException completion(Throwable failure) {
        return failure instanceof CompletionException exception ? exception : new CompletionException(failure);
    }

    private static void closeAfterFailure(AutoCloseable resource, Throwable failure) {
        try {
            resource.close();
        } catch (Throwable closeFailure) {
            if (closeFailure != failure) {
                failure.addSuppressed(closeFailure);
            }
        }
    }

    @FunctionalInterface
    interface Decoder {
        AudioStream open(InputStream input) throws Exception;
    }

    private static final class CloseOnceInput extends FilterInputStream {
        private final AtomicBoolean closed = new AtomicBoolean();

        private CloseOnceInput(InputStream input) {
            super(Objects.requireNonNull(input, "input"));
        }

        @Override
        public void close() throws IOException {
            if (this.closed.compareAndSet(false, true)) {
                super.close();
            }
        }
    }

    private static final class OwnedDecodedStream implements AudioStream {
        private final AudioStream decoded;
        private final InputStream input;
        private final AtomicBoolean closed = new AtomicBoolean();

        private OwnedDecodedStream(AudioStream decoded, InputStream input) {
            this.decoded = decoded;
            this.input = input;
        }

        @Override
        public AudioFormat getFormat() {
            return this.decoded.getFormat();
        }

        @Override
        public ByteBuffer read(int amount) throws IOException {
            if (this.closed.get()) {
                throw new IOException("Stream is closed");
            }
            return this.decoded.read(amount);
        }

        @Override
        public void close() throws IOException {
            if (!this.closed.compareAndSet(false, true)) {
                return;
            }
            try {
                this.decoded.close();
            } catch (IOException | RuntimeException | Error failure) {
                closeAfterFailure(this.input, failure);
                throw failure;
            }
            this.input.close();
        }
    }
}
