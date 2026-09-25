package gg.moonflower.etched.client.radio.stream;

import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RadioMonoAudioStreamTest {

    @Test
    void averagesStereoSamplesWithoutOverflow() throws Exception {
        FakeStream source = new FakeStream(stereoFormat(), shorts(
                30_000, 30_000,
                -30_000, -30_000,
                20_000, -10_000));
        RadioMonoAudioStream stream = new RadioMonoAudioStream(source);

        ByteBuffer output = stream.read(6).order(ByteOrder.LITTLE_ENDIAN);

        assertEquals(1, stream.getFormat().getChannels());
        assertEquals(30_000, output.getShort());
        assertEquals(-30_000, output.getShort());
        assertEquals(5_000, output.getShort());
    }

    @Test
    void passesMonoThroughAndSharesTerminalSignal() throws Exception {
        ByteBuffer data = shorts(1, 2, 3);
        FakeStream source = new FakeStream(new AudioFormat(22_050, 16, 1, true, false), data);
        RadioMonoAudioStream stream = new RadioMonoAudioStream(source);

        assertSame(data, stream.read(6));
        assertSame(source.termination(), stream.termination());
    }

    @Test
    void rejectsPaddedStereoFrames() {
        AudioFormat padded = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                44_100, 16, 2, 6, 44_100, false);
        assertThrows(IllegalArgumentException.class,
                () -> new RadioMonoAudioStream(new FakeStream(padded, shorts(1, 2))));
    }

    @Test
    void reportsDownmixFailuresThroughItsTerminalSignal() {
        RadioMonoAudioStream stream = new RadioMonoAudioStream(
                new FakeStream(stereoFormat(), shorts(1, 2, 3)));

        IOException failure = assertThrows(IOException.class, () -> stream.read(4));
        PlaybackAudioStream.Termination termination = stream.termination().toCompletableFuture().join();

        assertEquals(PlaybackAudioStream.TerminalState.FAILED, termination.state());
        assertSame(failure, termination.failure());
    }

    private static AudioFormat stereoFormat() {
        return new AudioFormat(44_100, 16, 2, true, false);
    }

    private static ByteBuffer shorts(int... values) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(values.length * Short.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (int value : values) {
            buffer.putShort((short) value);
        }
        return buffer.flip();
    }

    private static final class FakeStream implements PlaybackAudioStream {

        private final AudioFormat format;
        private final ByteBuffer data;
        private final CompletableFuture<Termination> termination = new CompletableFuture<>();

        private FakeStream(AudioFormat format, ByteBuffer data) {
            this.format = format;
            this.data = data;
        }

        @Override
        public AudioFormat getFormat() {
            return this.format;
        }

        @Override
        public ByteBuffer read(int amount) {
            return this.data;
        }

        @Override
        public CompletionStage<Termination> termination() {
            return this.termination;
        }

        @Override
        public void close() throws IOException {
        }
    }
}
