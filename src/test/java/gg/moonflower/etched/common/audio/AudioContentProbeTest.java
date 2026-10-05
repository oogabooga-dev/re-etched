package gg.moonflower.etched.common.audio;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class AudioContentProbeTest {

    @Test
    void recognizesRealMpegId3AndVorbisFixtures() throws Exception {
        for (String name : new String[]{"mono.mp3", "stereo-vbr-id3.mp3", "stereo.ogg"}) {
            byte[] prefix = read(new ByteArrayInputStream(TestAudioContent.fixture(name)));
            assertEquals(name.endsWith("ogg") ? AudioContentProbe.Format.OGG : AudioContentProbe.Format.MP3,
                    classify(prefix), name);
        }
    }

    @Test
    void rejectsFakeSignaturesMalformedMpegAndUnsupportedBodies() {
        for (byte[] bytes : new byte[][]{
                new byte[]{(byte) 0xFF, (byte) 0xFB, 0, 0},
                new byte[]{(byte) 0xFF, (byte) 0xEB, (byte) 0x90, 0},
                new byte[]{(byte) 0xFF, (byte) 0xFB, (byte) 0x9C, 0},
                "ID3-audio".getBytes(StandardCharsets.US_ASCII),
                "OggS-not-vorbis".getBytes(StandardCharsets.US_ASCII),
                "RIFF....WAVE".getBytes(StandardCharsets.US_ASCII),
                "<html>not audio</html>".getBytes(StandardCharsets.US_ASCII),
                "#EXTM3U\nhttps://audio.example/track".getBytes(StandardCharsets.US_ASCII)}) {
            assertEquals(AudioContentProbe.Format.UNKNOWN, classify(bytes));
        }
        assertEquals(AudioContentProbe.Format.AAC,
                classify(new byte[]{(byte) 0xFF, (byte) 0xF1, 0, 0}));
    }

    @Test
    void rejectsNonVorbisAndMalformedVorbisIdentification() throws Exception {
        byte[] ogg = TestAudioContent.fixture("stereo.ogg");
        int packet = 27 + (ogg[26] & 0xFF);
        for (int offset : new int[]{4, 5, packet, packet + 1, packet + 7, packet + 11,
                packet + 28, packet + 29}) {
            byte[] invalid = ogg.clone();
            invalid[offset] = (byte) (offset == packet + 11 || offset == packet + 28 ? 0 : 127);
            assertEquals(AudioContentProbe.Format.UNKNOWN, classify(invalid), "offset " + offset);
        }
    }

    @Test
    void separateId3LimitAllowsLargeTagsButRejectsOversizedHeadersWithoutReadingTheirBody() throws Exception {
        byte[] tagged = TestAudioContent.taggedMpeg(16 * 1024);
        assertArrayEquals(tagged, read(new ByteArrayInputStream(tagged)));
        assertEquals(AudioContentProbe.Format.MP3, classify(tagged));
        byte[] header = Arrays.copyOf(TestAudioContent.taggedMpeg(
                AudioContentProbe.DEFAULT_MAX_ID3_PREFIX_BYTES), 10);
        byte[] prefix = read(noReadPast(header));
        assertArrayEquals(header, prefix);
        assertEquals(AudioContentProbe.Format.UNKNOWN, classify(prefix));
        byte[] shortBudget = AudioContentProbe.readPrefix(new ByteArrayInputStream(tagged),
                new AudioCancellation(), 8192, 8192);
        assertEquals(10, shortBudget.length);
    }

    @Test
    void binaryLivePrefixDoesNotRequireEofAndProbeDoesNotCloseCallerStream() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        InputStream live = new ByteArrayInputStream(TestAudioContent.mpeg()) {
            @Override
            public synchronized int read(byte[] bytes, int offset, int length) {
                if (super.available() == 0) {
                    throw new AssertionError("Probe waited for EOF on a binary live stream");
                }
                return super.read(bytes, offset, length);
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };
        assertEquals(AudioContentProbe.Format.MP3, classify(read(live)));
        assertFalse(closed.get());
    }

    @Test
    void textInspectionIsBoundedAndPreCancellationDoesNotRead() throws Exception {
        byte[] text = new byte[AudioContentProbe.DEFAULT_SNIFF_BYTES];
        Arrays.fill(text, (byte) 'a');
        assertArrayEquals(text, read(noReadPast(text)));
        AudioCancellation cancelled = new AudioCancellation();
        cancelled.cancel();
        assertThrows(CancellationException.class, () -> AudioContentProbe.readPrefix(
                noReadPast(new byte[0]), cancelled, 8192, 8192));
    }

    private static InputStream noReadPast(byte[] bytes) {
        return new ByteArrayInputStream(bytes) {
            @Override
            public synchronized int read(byte[] buffer, int offset, int length) {
                if (super.available() == 0) {
                    throw new AssertionError("Probe exceeded the expected read boundary");
                }
                return super.read(buffer, offset, length);
            }
        };
    }

    private static byte[] read(InputStream body) throws IOException {
        return AudioContentProbe.readPrefix(body, new AudioCancellation(),
                AudioContentProbe.DEFAULT_SNIFF_BYTES, AudioContentProbe.DEFAULT_MAX_ID3_PREFIX_BYTES);
    }

    private static AudioContentProbe.Format classify(byte[] prefix) {
        return AudioContentProbe.classify(prefix, AudioContentProbe.DEFAULT_MAX_ID3_PREFIX_BYTES);
    }
}
