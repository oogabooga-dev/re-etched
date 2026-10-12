package gg.moonflower.etched.client.radio.stream;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class RadioWaveAudioStreamTest {
    @Test
    void preservesStereoPcmAndReportsEofAfterFinalSamples() throws Exception {
        byte[] samples = {1, 2, 3, 4, 5, 6, 7, 8};
        try (var audio = new RadioWaveAudioStream(new ByteArrayInputStream(wav(16, 2, samples)))) {
            ByteBuffer output = audio.read(100);
            byte[] actual = new byte[output.remaining()];
            output.get(actual);
            assertArrayEquals(samples, actual);
            assertFalse(audio.termination().toCompletableFuture().isDone());
            assertFalse(audio.read(100).hasRemaining());
            assertEquals(RadioAudioStream.TerminalState.EOF, audio.termination().toCompletableFuture().join().state());
        }
    }

    @Test
    void convertsUnsignedEightBitPcmBeforeDownmix() throws Exception {
        byte[] samples = {0, 0, (byte) 128, (byte) 128, (byte) 255, (byte) 255};
        try (var audio = new RadioMonoAudioStream(new RadioWaveAudioStream(
                new ByteArrayInputStream(wav(8, 2, samples))))) {
            ByteBuffer pcm = audio.read(100).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(6, pcm.remaining());
            assertEquals(-32768, pcm.getShort());
            assertEquals(0, pcm.getShort());
            assertEquals(32512, pcm.getShort());
        }
    }

    private static byte[] wav(int bits, int channels, byte[] samples) {
        ByteBuffer wav = ByteBuffer.allocate(44 + samples.length).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + samples.length);
        wav.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
        wav.putShort((short) 1).putShort((short) channels).putInt(22050);
        wav.putInt(22050 * channels * bits / 8).putShort((short) (channels * bits / 8)).putShort((short) bits);
        wav.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(samples.length).put(samples);
        return wav.array();
    }
}
