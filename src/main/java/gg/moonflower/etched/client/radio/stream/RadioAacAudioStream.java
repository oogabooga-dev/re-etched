package gg.moonflower.etched.client.radio.stream;

import net.sourceforge.jaad.aac.Decoder;
import net.sourceforge.jaad.aac.SampleBuffer;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded AAC-LC ADTS frame decoder. Input is supplied by the checked SoundCloud fMP4 demuxer. */
public final class RadioAacAudioStream extends AbstractRadioAudioStream {
    private static final int[] RATES = {96000, 88200, 64000, 48000, 44100, 32000, 24000,
            22050, 16000, 12000, 11025, 8000, 7350};
    private final InputStream input;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final SampleBuffer samples = new SampleBuffer();
    private Decoder decoder;
    private byte[] config;
    private AudioFormat format;
    private ByteBuffer pending = ByteBuffer.allocate(0);
    private boolean eof;

    public RadioAacAudioStream(InputStream input) throws IOException {
        this.input = Objects.requireNonNull(input);
        samples.setBigEndian(false);
        try {
            if (!decodeFrame()) throw new IOException("Empty AAC stream");
        } catch (IOException | RuntimeException e) {
            try { input.close(); } catch (IOException close) { e.addSuppressed(close); }
            throw e;
        }
    }

    @Override
    public AudioFormat getFormat() { return format; }

    @Override
    public synchronized ByteBuffer read(int requestedBytes) throws IOException {
        if (closed.get()) throw new IOException("AAC decoder is closed");
        if (requestedBytes <= 0) return ByteBuffer.allocateDirect(0);
        try {
            if (eof && !pending.hasRemaining()) {
                complete(TerminalState.EOF);
                return ByteBuffer.allocateDirect(0);
            }
            int frameSize = format.getFrameSize();
            int limit = Math.min(requestedBytes, 1024 * 1024);
            limit = Math.max(frameSize, limit - limit % frameSize);
            ByteBuffer result = ByteBuffer.allocateDirect(limit).order(ByteOrder.LITTLE_ENDIAN);
            while (result.hasRemaining()) {
                if (!pending.hasRemaining() && !decodeFrame()) { eof = true; break; }
                int count = Math.min(result.remaining(), pending.remaining());
                ByteBuffer slice = pending.slice(); slice.limit(count);
                result.put(slice); pending.position(pending.position() + count);
            }
            result.flip();
            if (eof && !result.hasRemaining()) complete(TerminalState.EOF);
            return result;
        } catch (CancellationException e) {
            complete(TerminalState.CANCELLED);
            InterruptedIOException error = new InterruptedIOException("AAC decoding cancelled");
            error.initCause(e); throw error;
        } catch (IOException e) {
            fail(e); throw e;
        } catch (RuntimeException e) {
            IOException error = new IOException("AAC decoding failed", e);
            fail(error); throw error;
        }
    }

    private boolean decodeFrame() throws IOException {
        byte[] header = input.readNBytes(7);
        if (header.length == 0) return false;
        if (header.length != 7 || (header[0] & 255) != 255 || (header[1] & 255) != 0xf1
                || (header[2] >>> 6 & 3) != 1 || (header[6] & 3) != 0) {
            throw new IOException("Expected complete MPEG-4 AAC-LC ADTS frame");
        }
        int frequency = (header[2] >>> 2) & 15;
        int channels = ((header[2] & 1) << 2) | ((header[3] >>> 6) & 3);
        if (frequency >= RATES.length || channels < 1 || channels > 2) throw new IOException("Unsupported AAC format");
        byte[] currentConfig = {(byte) (0x10 | (frequency >>> 1)), (byte) ((frequency << 7) | (channels << 3))};
        if (decoder == null) {
            config = currentConfig;
            decoder = new Decoder(config);
            format = new AudioFormat(RATES[frequency], 16, channels, true, false);
        } else if (!Arrays.equals(config, currentConfig)) throw new IOException("AAC format changed");
        int length = ((header[3] & 3) << 11) | ((header[4] & 255) << 3) | ((header[5] & 255) >>> 5);
        if (length <= 7) throw new IOException("Invalid AAC frame length");
        byte[] frame = input.readNBytes(length - 7);
        if (frame.length != length - 7) throw new IOException("Truncated AAC frame");
        // JAAD can swallow an end-of-frame error. Clear old PCM and require a complete output frame.
        samples.setData(new byte[0], 0, 0, 0, 0);
        decoder.decodeFrame(frame, samples);
        if (samples.getSampleRate() != RATES[frequency] || samples.getChannels() != channels
                || samples.getBitsPerSample() != 16 || samples.getData().length != 1024 * channels * 2) {
            throw new IOException("JAAD returned an incomplete or unsupported PCM frame");
        }
        pending = ByteBuffer.wrap(samples.getData());
        return true;
    }

    @Override
    public void close() throws IOException {
        // Closing upstream must unblock a read without waiting for the decoder monitor.
        if (closed.compareAndSet(false, true)) {
            complete(TerminalState.CLOSED);
            input.close();
        }
    }
}
