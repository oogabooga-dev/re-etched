package gg.moonflower.etched.client.radio.stream;

import com.mojang.blaze3d.audio.OggAudioStream;

import javax.sound.sampled.AudioFormat;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.SequenceInputStream;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** Radio-local lifecycle wrapper around Minecraft's Vorbis decoder. */
public final class RadioOggAudioStream extends AbstractPlaybackAudioStream {

    private static final int MAX_PCM_READ = 1024 * 1024;
    private static final int MAX_INITIALIZATION_BYTES = 256 * 1024;

    private final OggAudioStream delegate;
    private final AtomicBoolean closed;

    public RadioOggAudioStream(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        try {
            this.delegate = new OggAudioStream(validatedInput(input));
        } catch (IOException | RuntimeException exception) {
            try {
                input.close();
            } catch (IOException closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
        this.closed = new AtomicBoolean();
    }

    @Override
    public AudioFormat getFormat() {
        return this.delegate.getFormat();
    }

    @Override
    public synchronized ByteBuffer read(int requestedBytes) throws IOException {
        if (this.closed.get()) {
            throw new IOException("Ogg decoder is closed");
        }
        if (requestedBytes <= 0) {
            return ByteBuffer.allocateDirect(0);
        }
        try {
            ByteBuffer output = this.delegate.read(Math.min(requestedBytes, MAX_PCM_READ));
            if (!output.hasRemaining()) {
                this.complete(TerminalState.EOF);
            }
            return output;
        } catch (CancellationException exception) {
            this.complete(TerminalState.CANCELLED);
            InterruptedIOException interrupted = new InterruptedIOException("Ogg decoding was cancelled");
            interrupted.initCause(exception);
            throw interrupted;
        } catch (IOException exception) {
            this.fail(exception);
            throw exception;
        } catch (RuntimeException exception) {
            IOException failure = new IOException("Ogg decoder failed", exception);
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
        this.delegate.close();
    }

    private static InputStream validatedInput(InputStream input) throws IOException {
        ByteArrayOutputStream prefix = new ByteArrayOutputStream(8192);
        byte[] chunk = new byte[8192];
        int nextInspection = chunk.length;
        while (prefix.size() < MAX_INITIALIZATION_BYTES) {
            int read = input.read(chunk, 0,
                    Math.min(chunk.length, MAX_INITIALIZATION_BYTES - prefix.size()));
            boolean eof = read < 0;
            if (read == 0) {
                int value = input.read();
                if (value < 0) {
                    eof = true;
                } else {
                    prefix.write(value);
                }
            } else if (read > 0) {
                prefix.write(chunk, 0, read);
            }

            if (eof && prefix.size() == 0) {
                throw new IOException("Failed to find Ogg header");
            }

            if (!eof && prefix.size() < nextInspection) {
                continue;
            }

            byte[] bytes = prefix.toByteArray();
            int error = inspectHeader(bytes);
            if (error == 0) {
                return new SequenceInputStream(new ByteArrayInputStream(bytes), input);
            }
            if (error != 1) {
                throw new IOException("Failed to read Ogg header " + error);
            }
            if (eof) {
                throw new IOException("Failed to find Ogg header");
            }
            nextInspection = Math.min(MAX_INITIALIZATION_BYTES, nextInspection * 2);
        }
        throw new IOException("Ogg headers exceed the radio decoder limit");
    }

    private static int inspectHeader(byte[] prefix) {
        ByteBuffer nativeBuffer = MemoryUtil.memAlloc(prefix.length);
        long handle = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            nativeBuffer.put(prefix).flip();
            IntBuffer consumed = stack.mallocInt(1);
            IntBuffer error = stack.mallocInt(1);
            handle = STBVorbis.stb_vorbis_open_pushdata(nativeBuffer, consumed, error, null);
            int errorCode = error.get(0);
            return handle != 0L ? 0 : errorCode == 0 || errorCode == 1 ? 1 : errorCode;
        } finally {
            if (handle != 0L) {
                STBVorbis.stb_vorbis_close(handle);
            }
            MemoryUtil.memFree((Buffer) nativeBuffer);
        }
    }
}
