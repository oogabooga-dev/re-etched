package gg.moonflower.etched.client.radio.stream;

import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RadioPcmBufferTest {
    @Test
    void stalledDecoderCannotBlockSoundEngineOrItsNextTask() throws Exception {
        GateDecoder decoder = new GateDecoder(false);
        RadioPcmBuffer pcm = new RadioPcmBuffer(decoder, decoder.release::countDown);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ExecutorService soundEngine = Executors.newSingleThreadExecutor();
        try {
            worker.submit(pcm::produce);
            assertTrue(decoder.entered.await(2, TimeUnit.SECONDS));
            // The actual Channel.attachBufferStream performs four reads, then
            // SoundEngine.play waits for another task on this same executor.
            soundEngine.submit(() -> {
                for (int i = 0; i < 4; i++) {
                    ByteBuffer silence = pcm.read(88_200);
                    assertTrue(silence.hasRemaining());
                    while (silence.hasRemaining()) assertEquals(0, silence.get());
                }
                return null;
            }).get(1, TimeUnit.SECONDS);
            assertEquals(42, soundEngine.submit(() -> 42).get(1, TimeUnit.SECONDS));
            assertEquals(1, decoder.release.getCount(), "Input is still stalled");
            assertFalse(pcm.isTerminated(), "Underrun is not EOF");
            soundEngine.submit(pcm::close).get(1, TimeUnit.SECONDS);
        } finally {
            pcm.close();
            worker.shutdownNow();
            soundEngine.shutdownNow();
        }
    }

    @Test
    void bufferedTailIsDeliveredBeforeEofAndDecoderIsClosedOnce() throws Exception {
        GateDecoder decoder = new GateDecoder(false);
        RadioPcmBuffer pcm = new RadioPcmBuffer(decoder, decoder.release::countDown);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> task = worker.submit(pcm::produce);
            decoder.release.countDown();
            task.get(2, TimeUnit.SECONDS);
            assertFalse(pcm.isTerminated(), "Prefetch EOF must not discard queued PCM");
            ByteBuffer data = pcm.read(100);
            assertEquals(4, data.remaining());
            assertEquals(0x12345678, data.getInt());
            assertFalse(pcm.isTerminated());
            assertFalse(pcm.read(100).hasRemaining());
            assertEquals(RadioAudioStream.TerminalState.EOF,
                    pcm.termination().toCompletableFuture().get().state());
            pcm.close();
            assertEquals(1, decoder.closes.get());
        } finally {
            pcm.close();
            worker.shutdownNow();
        }
    }

    @Test
    void failureIsReportedWithoutRepeatingDecoderReads() throws Exception {
        GateDecoder decoder = new GateDecoder(true);
        RadioPcmBuffer pcm = new RadioPcmBuffer(decoder, decoder.release::countDown);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            decoder.release.countDown();
            worker.submit(pcm::produce).get(2, TimeUnit.SECONDS);
            assertThrows(IOException.class, () -> pcm.read(100));
            assertEquals(RadioAudioStream.TerminalState.FAILED,
                    pcm.termination().toCompletableFuture().get().state());
            assertFalse(pcm.read(100).hasRemaining());
            assertEquals(1, decoder.reads.get());
        } finally {
            pcm.close();
            worker.shutdownNow();
        }
    }

    @Test
    void cancelAndCloseNeverWaitForSynchronizedDecoderReadOrClose() throws Exception {
        GateDecoder decoder = new GateDecoder(false);
        CountDownLatch cleanup = new CountDownLatch(1);
        RadioPcmBuffer pcm = new RadioPcmBuffer(decoder, cleanup::countDown);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            worker.submit(pcm::produce);
            assertTrue(decoder.entered.await(2, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(1), pcm::cancel);
            assertEquals(0, cleanup.getCount());
            assertEquals(1, decoder.release.getCount());
            assertEquals(RadioAudioStream.TerminalState.CANCELLED,
                    pcm.termination().toCompletableFuture().get().state());
            assertFalse(pcm.read(100).hasRemaining());
        } finally {
            decoder.release.countDown();
            pcm.close();
            worker.shutdownNow();
        }
    }

    @Test
    void stalledAudioTimesOutEvenWhenUpstreamNeverReportsAnError() throws Exception {
        GateDecoder decoder = new GateDecoder(false);
        var time = new java.util.concurrent.atomic.AtomicLong();
        RadioPcmBuffer pcm = new RadioPcmBuffer(decoder, decoder.release::countDown, time::get);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            worker.submit(pcm::produce);
            assertTrue(decoder.entered.await(2, TimeUnit.SECONDS));
            assertTrue(pcm.read(100).hasRemaining());
            time.set(TimeUnit.SECONDS.toNanos(16));
            assertThrows(IOException.class, () -> pcm.read(100));
            assertEquals(RadioAudioStream.TerminalState.FAILED,
                    pcm.termination().toCompletableFuture().get().state());
            assertEquals(0, decoder.release.getCount());
        } finally {
            pcm.close();
            worker.shutdownNow();
        }
    }

    @Test
    void audioResumesAfterAnUnderrunWithoutLosingSamples() throws Exception {
        GateDecoder decoder = new GateDecoder(false);
        RadioPcmBuffer pcm = new RadioPcmBuffer(decoder, decoder.release::countDown);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> task = worker.submit(pcm::produce);
            assertTrue(decoder.entered.await(2, TimeUnit.SECONDS));
            assertEquals(0, pcm.read(4).getInt());
            decoder.release.countDown();
            task.get(2, TimeUnit.SECONDS);
            assertEquals(0x12345678, pcm.read(4).getInt());
            assertFalse(pcm.read(4).hasRemaining());
            assertEquals(RadioAudioStream.TerminalState.EOF,
                    pcm.termination().toCompletableFuture().get().state());
        } finally {
            pcm.close();
            worker.shutdownNow();
        }
    }

    @Test
    void pausedConsumerBoundsPrefetchAndCancellationReleasesWorker() throws Exception {
        AtomicInteger bytes = new AtomicInteger();
        CountDownLatch nearlyFull = new CountDownLatch(1);
        RadioAudioStream decoder = new AbstractRadioAudioStream() {
            @Override public AudioFormat getFormat() { return new AudioFormat(44_100, 16, 1, true, false); }
            @Override public ByteBuffer read(int amount) {
                if (bytes.addAndGet(amount) >= 900_000) nearlyFull.countDown();
                return ByteBuffer.allocateDirect(amount);
            }
            @Override public void close() { }
        };
        RadioPcmBuffer pcm = new RadioPcmBuffer(decoder, () -> { });
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> task = worker.submit(pcm::produce);
            assertTrue(nearlyFull.await(2, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> task.get(100, TimeUnit.MILLISECONDS));
            assertTrue(bytes.get() <= 1024 * 1024, "Paused playback must not decode without a bound");
            pcm.cancel();
            task.get(1, TimeUnit.SECONDS);
            assertEquals(42, worker.submit(() -> 42).get(1, TimeUnit.SECONDS));
        } finally {
            pcm.close();
            worker.shutdownNow();
        }
    }

    private static final class GateDecoder extends AbstractRadioAudioStream {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger closes = new AtomicInteger();
        final AtomicInteger reads = new AtomicInteger();
        final boolean fail;

        GateDecoder(boolean fail) { this.fail = fail; }

        @Override public AudioFormat getFormat() { return new AudioFormat(44_100, 16, 1, true, false); }

        @Override public synchronized ByteBuffer read(int amount) throws IOException {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test input timed out");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException(exception);
            }
            int call = reads.incrementAndGet();
            if (fail) throw new IOException("Broken MP3");
            if (call == 1) return ByteBuffer.allocateDirect(4).putInt(0x12345678).flip();
            complete(TerminalState.EOF);
            return ByteBuffer.allocateDirect(0);
        }

        @Override public synchronized void close() { closes.incrementAndGet(); }
    }
}
