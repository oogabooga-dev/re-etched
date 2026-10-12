package gg.moonflower.etched.client.radio.stream;

import gg.moonflower.etched.api.util.WaveDataReader;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CancellationException;

/** Preserves legacy PCM WAV discs while using the same buffered transport and 16-bit output. */
public final class RadioWaveAudioStream extends AbstractRadioAudioStream {
    private final AudioInputStream input;
    private final AudioFormat format;
    private final int bits;
    private boolean closed;

    public RadioWaveAudioStream(InputStream source) throws IOException {
        try {
            this.input = WaveDataReader.getAudioInputStream(new BufferedInputStream(source));
            AudioFormat original = this.input.getFormat();
            this.bits = original.getSampleSizeInBits();
            int channels = original.getChannels();
            if ((channels != 1 && channels != 2) || (this.bits != 8 && this.bits != 16)
                    || original.isBigEndian() || original.getFrameSize() != channels * this.bits / 8
                    || !(this.bits == 8 ? AudioFormat.Encoding.PCM_UNSIGNED : AudioFormat.Encoding.PCM_SIGNED)
                    .equals(original.getEncoding())) {
                throw new IOException("WAV disc must contain mono/stereo 8-bit or 16-bit PCM");
            }
            this.format = new AudioFormat(original.getSampleRate(), 16, channels, true, false);
        } catch (IOException | UnsupportedAudioFileException | RuntimeException failure) {
            source.close();
            throw new IOException("Could not open WAV disc", failure);
        }
    }

    @Override
    public AudioFormat getFormat() {
        return this.format;
    }

    @Override
    public synchronized ByteBuffer read(int requestedBytes) throws IOException {
        if (this.closed) {
            throw new IOException("WAV decoder is closed");
        }
        if (requestedBytes <= 0) {
            return ByteBuffer.allocateDirect(0);
        }
        int frameSize = this.format.getFrameSize();
        int size = Math.max(frameSize, Math.min(1024 * 1024, requestedBytes));
        size -= size % frameSize;
        try {
            byte[] bytes = this.input.readNBytes(this.bits == 8 ? size / 2 : size);
            if (bytes.length % this.input.getFormat().getFrameSize() != 0) {
                throw new IOException("WAV returned a partial PCM frame");
            }
            ByteBuffer pcm = ByteBuffer.allocateDirect(this.bits == 8 ? bytes.length * 2 : bytes.length)
                    .order(ByteOrder.LITTLE_ENDIAN);
            if (this.bits == 8) {
                for (byte value : bytes) {
                    pcm.putShort((short) ((Byte.toUnsignedInt(value) - 128) << 8));
                }
            } else {
                pcm.put(bytes);
            }
            pcm.flip();
            if (!pcm.hasRemaining()) {
                this.complete(TerminalState.EOF);
            }
            return pcm;
        } catch (CancellationException failure) {
            this.complete(TerminalState.CANCELLED);
            throw new IOException("WAV decoding cancelled", failure);
        } catch (IOException failure) {
            this.fail(failure);
            throw failure;
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (!this.closed) {
            this.closed = true;
            this.complete(TerminalState.CLOSED);
            this.input.close();
        }
    }
}
