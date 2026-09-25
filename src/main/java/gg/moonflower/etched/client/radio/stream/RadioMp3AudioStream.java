package gg.moonflower.etched.client.radio.stream;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.JavaLayerException;
import javazoom.jl.decoder.Obuffer;
import javazoom.jl.decoder.SampleBuffer;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.PushbackInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Radio-local streaming MP3 decoder backed by JLayer. */
public final class RadioMp3AudioStream extends AbstractPlaybackAudioStream {

    private static final int MAX_PCM_READ = 1024 * 1024;
    private static final int MAX_ID3_BYTES = 1024 * 1024;
    private static final int MAX_EMPTY_FRAMES = 32;

    private final Bitstream bitstream;
    private final Decoder decoder;
    private final AtomicBoolean closed;
    private AudioFormat format;
    private ByteBuffer pending;
    private boolean eof;

    public RadioMp3AudioStream(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        this.bitstream = new Bitstream(guardId3(input));
        this.decoder = new Decoder();
        this.closed = new AtomicBoolean();
        this.pending = emptyBuffer();
        try {
            if (!this.decodeFrame()) {
                throw new IOException("MP3 stream does not contain an audio frame");
            }
        } catch (IOException | RuntimeException exception) {
            try {
                this.bitstream.close();
            } catch (JavaLayerException closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    @Override
    public AudioFormat getFormat() {
        return this.format;
    }

    @Override
    public synchronized ByteBuffer read(int requestedBytes) throws IOException {
        this.requireOpen();
        if (requestedBytes <= 0) {
            return emptyBuffer();
        }
        try {
            if (this.eof && !this.pending.hasRemaining()) {
                this.complete(TerminalState.EOF);
                return emptyBuffer();
            }

            int frameSize = this.format.getFrameSize();
            int limit = Math.min(requestedBytes, MAX_PCM_READ);
            limit -= limit % frameSize;
            if (limit == 0) {
                limit = frameSize;
            }
            ByteBuffer output = ByteBuffer.allocateDirect(limit).order(ByteOrder.LITTLE_ENDIAN);
            while (output.hasRemaining()) {
                if (!this.pending.hasRemaining()) {
                    if (!this.decodeFrame()) {
                        this.eof = true;
                        break;
                    }
                }
                int copied = Math.min(output.remaining(), this.pending.remaining());
                copied -= copied % frameSize;
                ByteBuffer slice = this.pending.slice();
                slice.limit(copied);
                output.put(slice);
                this.pending.position(this.pending.position() + copied);
            }
            output.flip();
            return output;
        } catch (CancellationException exception) {
            this.complete(TerminalState.CANCELLED);
            throw interrupted("MP3 decoding was cancelled", exception);
        } catch (IOException exception) {
            this.fail(exception);
            throw exception;
        } catch (RuntimeException exception) {
            IOException failure = new IOException("MP3 decoder failed", exception);
            this.fail(failure);
            throw failure;
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }
        if (!this.isTerminated()) {
            this.complete(TerminalState.CLOSED);
        }
        try {
            this.bitstream.close();
        } catch (JavaLayerException exception) {
            throw asIOException("Could not close MP3 decoder", exception);
        }
    }

    private boolean decodeFrame() throws IOException {
        for (int emptyFrames = 0; emptyFrames <= MAX_EMPTY_FRAMES; emptyFrames++) {
            try {
                Header header = this.bitstream.readFrame();
                if (header == null) {
                    this.pending = emptyBuffer();
                    return false;
                }

                int frameChannels = header.mode() == Header.SINGLE_CHANNEL ? 1 : 2;
                int frameFrequency = header.frequency();
                if (this.format == null) {
                    this.format = new AudioFormat(frameFrequency, Short.SIZE, frameChannels, true, false);
                } else if (this.format.getSampleRate() != frameFrequency
                        || this.format.getChannels() != frameChannels) {
                    throw new IOException("MP3 stream changed its audio format");
                }

                Obuffer output = this.decoder.decodeFrame(header, this.bitstream);
                if (!(output instanceof SampleBuffer samples)) {
                    throw new IOException("JLayer returned an unsupported output buffer");
                }
                if (samples.getSampleFrequency() != frameFrequency
                        || samples.getChannelCount() != frameChannels) {
                    throw new IOException("JLayer returned PCM in an unexpected format");
                }

                int sampleCount = samples.getBufferLength();
                if (sampleCount == 0) {
                    continue;
                }
                ByteBuffer decoded = ByteBuffer.allocate(sampleCount * Short.BYTES)
                        .order(ByteOrder.LITTLE_ENDIAN);
                short[] source = samples.getBuffer();
                for (int i = 0; i < sampleCount; i++) {
                    decoded.putShort(source[i]);
                }
                decoded.flip();
                this.pending = decoded;
                return true;
            } catch (JavaLayerException exception) {
                throw asIOException("Could not decode MP3 frame", exception);
            } finally {
                this.bitstream.closeFrame();
            }
        }
        throw new IOException("MP3 stream produced too many empty frames");
    }

    private void requireOpen() throws IOException {
        if (this.closed.get()) {
            throw new IOException("MP3 decoder is closed");
        }
    }

    private static IOException asIOException(String message, JavaLayerException exception) {
        Throwable cause = exception;
        while (cause instanceof JavaLayerException javaLayerException
                && javaLayerException.getException() != null
                && javaLayerException.getException() != cause) {
            cause = javaLayerException.getException();
        }
        if (cause instanceof CancellationException cancellation) {
            throw cancellation;
        }
        if (cause instanceof IOException ioException) {
            return ioException;
        }
        return new IOException(message, exception);
    }

    private static InputStream guardId3(InputStream input) throws IOException {
        PushbackInputStream guarded = new PushbackInputStream(input, 10);
        byte[] header = new byte[10];
        int read = 0;
        try {
            while (read < header.length) {
                int count = guarded.read(header, read, header.length - read);
                if (count < 0) {
                    break;
                }
                if (count == 0) {
                    int value = guarded.read();
                    if (value < 0) {
                        break;
                    }
                    header[read++] = (byte) value;
                    continue;
                }
                read += count;
            }
            if (read == header.length && header[0] == 'I' && header[1] == 'D' && header[2] == '3') {
                int size = 0;
                for (int i = 6; i < 10; i++) {
                    if ((header[i] & 0x80) != 0) {
                        throw new IOException("MP3 contains an invalid ID3 size");
                    }
                    size = (size << 7) | header[i];
                }
                if (size > MAX_ID3_BYTES) {
                    throw new IOException("MP3 ID3 tag exceeds the radio decoder limit");
                }
            }
            guarded.unread(header, 0, read);
            return guarded;
        } catch (IOException exception) {
            try {
                guarded.close();
            } catch (IOException closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    private static InterruptedIOException interrupted(String message, Throwable cause) {
        InterruptedIOException exception = new InterruptedIOException(message);
        exception.initCause(cause);
        return exception;
    }

    private static ByteBuffer emptyBuffer() {
        return ByteBuffer.allocateDirect(0);
    }
}
