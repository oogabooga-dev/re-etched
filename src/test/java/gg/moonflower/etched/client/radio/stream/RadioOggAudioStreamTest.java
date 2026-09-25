package gg.moonflower.etched.client.radio.stream;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RadioOggAudioStreamTest {

    @Test
    void decodesVorbisFixtureAndSignalsEof() throws Exception {
        try (RadioOggAudioStream stream = new RadioOggAudioStream(fixture())) {
            assertEquals(32_000.0F, stream.getFormat().getSampleRate());
            assertEquals(2, stream.getFormat().getChannels());
            int decoded = 0;
            while (true) {
                ByteBuffer output = stream.read(1024);
                assertTrue(output.isDirect());
                if (!output.hasRemaining()) {
                    break;
                }
                assertEquals(0, output.remaining() % stream.getFormat().getFrameSize());
                decoded += output.remaining();
            }

            assertTrue(decoded > 10_000);
            assertEquals(PlaybackAudioStream.TerminalState.EOF,
                    stream.termination().toCompletableFuture().get(2, TimeUnit.SECONDS).state());
        }
    }

    @Test
    void zeroRequestDoesNotConsumeAudioAndCloseIsIdempotent() throws Exception {
        RadioOggAudioStream stream = new RadioOggAudioStream(fixture());
        assertEquals(0, stream.read(0).remaining());
        assertTrue(stream.read(1024).hasRemaining());

        stream.close();
        stream.close();

        assertEquals(PlaybackAudioStream.TerminalState.CLOSED,
                stream.termination().toCompletableFuture().join().state());
        assertThrows(IOException.class, () -> stream.read(1024));
    }

    @Test
    void malformedHeaderClosesOwnedSource() {
        AtomicInteger closes = new AtomicInteger();
        InputStream source = new ByteArrayInputStream(new byte[]{'O', 'g', 'g', 'S', 0, 1, 2}) {
            @Override
            public void close() throws IOException {
                closes.incrementAndGet();
                super.close();
            }
        };

        assertThrows(IOException.class, () -> new RadioOggAudioStream(source));
        assertEquals(1, closes.get());
    }

    private static InputStream fixture() {
        InputStream stream = RadioOggAudioStreamTest.class.getResourceAsStream(
                "/gg/moonflower/etched/client/radio/audio/stereo.ogg");
        if (stream == null) {
            throw new IllegalStateException("Missing Ogg fixture");
        }
        return stream;
    }
}
