package gg.moonflower.etched.client.radio.stream;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RadioMp3AudioStreamTest {

    @Test
    void decodesMonoUsingActualJLayerSampleCount() throws Exception {
        try (RadioMp3AudioStream stream = new RadioMp3AudioStream(fixture("mono.mp3"))) {
            assertEquals(22_050.0F, stream.getFormat().getSampleRate());
            assertEquals(1, stream.getFormat().getChannels());
            assertEquals(16, stream.getFormat().getSampleSizeInBits());

            int decodedBytes = drain(stream, 777);
            assertTrue(decodedBytes > 4_000);
            assertTrue(decodedBytes < 20_000,
                    "The decoder must not copy JLayer's unused backing-array tail");
            assertEquals(PlaybackAudioStream.TerminalState.EOF,
                    stream.termination().toCompletableFuture().get(2, TimeUnit.SECONDS).state());
        }
    }

    @Test
    void decodesStereoVbrWithId3AndReturnsPartialPcm() throws Exception {
        try (RadioMp3AudioStream stream = new RadioMp3AudioStream(fixture("stereo-vbr-id3.mp3"))) {
            assertEquals(44_100.0F, stream.getFormat().getSampleRate());
            assertEquals(2, stream.getFormat().getChannels());

            ByteBuffer first = stream.read(128);
            assertTrue(first.isDirect());
            assertTrue(first.hasRemaining());
            assertTrue(first.remaining() <= 128);
            assertEquals(0, first.remaining() % stream.getFormat().getFrameSize());

            int decodedBytes = first.remaining() + drain(stream, 513);
            assertTrue(decodedBytes > 20_000);
            assertEquals(0, decodedBytes % stream.getFormat().getFrameSize());
        }
    }

    @Test
    void fillsFourMinecraftStreamingBuffersAcrossMp3Frames() throws Exception {
        try (RadioMp3AudioStream stream = new RadioMp3AudioStream(fixture("stereo-long.mp3"))) {
            int oneSecond = (int) (stream.getFormat().getSampleRate() * stream.getFormat().getFrameSize());

            for (int i = 0; i < 4; i++) {
                ByteBuffer output = stream.read(oneSecond);
                assertEquals(oneSecond, output.remaining());
                assertEquals(0, output.remaining() % stream.getFormat().getFrameSize());
            }
        }
    }

    @Test
    void monoWrapperFillsRequestedOutputAcrossMp3Frames() throws Exception {
        try (RadioMonoAudioStream stream = new RadioMonoAudioStream(
                new RadioMp3AudioStream(fixture("stereo-long.mp3")))) {
            int oneSecond = (int) (stream.getFormat().getSampleRate() * stream.getFormat().getFrameSize());
            ByteBuffer output = stream.read(oneSecond);

            assertEquals(oneSecond, output.remaining());
            assertEquals(0, output.remaining() % stream.getFormat().getFrameSize());
        }
    }

    @Test
    void startsFromAnMp3FrameThatDependsOnEarlierBitReservoirData() throws Exception {
        InputStream midstream = fixture("stereo-long.mp3");
        midstream.skipNBytes(461);

        try (RadioMp3AudioStream stream = new RadioMp3AudioStream(midstream)) {
            assertTrue(stream.read(16 * 1024).hasRemaining());
        }
    }

    @Test
    void returnsFinalPartialPcmBeforeSignalingEof() throws Exception {
        try (RadioMp3AudioStream stream = new RadioMp3AudioStream(fixture("mono.mp3"))) {
            ByteBuffer finalPcm = stream.read(64 * 1024);

            assertTrue(finalPcm.hasRemaining());
            assertFalse(stream.termination().toCompletableFuture().isDone());
            assertFalse(stream.read(64 * 1024).hasRemaining());
            assertEquals(PlaybackAudioStream.TerminalState.EOF,
                    stream.termination().toCompletableFuture().join().state());
        }
    }

    @Test
    void rejectsDataWithoutAnMp3Frame() {
        assertThrows(IOException.class,
                () -> new RadioMp3AudioStream(new ByteArrayInputStream(new byte[]{1, 2, 3, 4})));
    }

    @Test
    void rejectsOversizedId3BeforeAllocatingItsDeclaredBody() {
        byte[] header = {'I', 'D', '3', 4, 0, 0, 0, 0x40, 0, 1};
        IOException exception = assertThrows(IOException.class,
                () -> new RadioMp3AudioStream(new ByteArrayInputStream(header)));
        assertTrue(exception.getMessage().contains("ID3"));
    }

    @Test
    void closeIsIdempotent() throws Exception {
        RadioMp3AudioStream stream = new RadioMp3AudioStream(fixture("mono.mp3"));

        stream.close();
        stream.close();

        assertEquals(PlaybackAudioStream.TerminalState.CLOSED,
                stream.termination().toCompletableFuture().join().state());
        assertThrows(IOException.class, () -> stream.read(10));
    }

    private static int drain(RadioMp3AudioStream stream, int requested) throws IOException {
        int total = 0;
        while (true) {
            ByteBuffer decoded = stream.read(requested);
            assertTrue(decoded.isDirect());
            if (!decoded.hasRemaining()) {
                return total;
            }
            assertEquals(0, decoded.remaining() % stream.getFormat().getFrameSize());
            total += decoded.remaining();
        }
    }

    private static InputStream fixture(String name) {
        InputStream stream = RadioMp3AudioStreamTest.class.getResourceAsStream(
                "/gg/moonflower/etched/client/radio/audio/" + name);
        if (stream == null) {
            throw new IllegalArgumentException("Missing fixture: " + name);
        }
        return stream;
    }
}
