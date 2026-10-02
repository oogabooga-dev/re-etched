package gg.moonflower.etched.client.sound;

import gg.moonflower.etched.api.sound.source.AudioSource;
import gg.moonflower.etched.api.util.DownloadProgressListener;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SoundCacheTest {

    private static final String URL = "https://audio.example/track";

    @Test
    void overlappingRequestsForTheSameUrlHaveIndependentSourcesAndMemoizedStreams() throws Exception {
        List<CompletableFuture<AudioSource>> attempts = new ArrayList<>();
        SoundCache.SourceResolver resolver = (url, listener, type) -> {
            assertEquals(URL, url);
            assertEquals(AudioSource.AudioFileType.FILE, type);
            var attempt = new CompletableFuture<AudioSource>();
            attempts.add(attempt);
            return attempt;
        };
        var first = SoundCache.getAudioStream(URL, null, AudioSource.AudioFileType.FILE, resolver);
        var second = SoundCache.getAudioStream(URL, null, AudioSource.AudioFileType.FILE, resolver);
        assertEquals(2, attempts.size());
        assertNotSame(first, second);
        var firstStream = new OwnedStream();
        var secondStream = new OwnedStream();
        attempts.get(0).complete(memoizedSource(firstStream));
        attempts.get(1).complete(memoizedSource(secondStream));
        assertNotSame(first.join(), second.join());
        try (InputStream one = first.join().openStream().join(); InputStream two = second.join().openStream().join()) {
            assertNotSame(one, two);
            assertEquals(1, one.read());
            assertEquals(1, two.read());
            one.close();
            assertTrue(firstStream.closed);
            assertFalse(secondStream.closed);
            assertEquals(2, two.read());
        }
        assertTrue(secondStream.closed);
    }

    @Test
    void cancellationAndFailureOfOneRequestDoNotAffectAnotherRequestOrItsListener() {
        for (boolean cancel : new boolean[]{false, true}) {
            List<CompletableFuture<AudioSource>> attempts = new ArrayList<>();
            List<DownloadProgressListener> listeners = new ArrayList<>();
            SoundCache.SourceResolver resolver = (url, listener, type) -> {
                listeners.add(listener);
                var attempt = new CompletableFuture<AudioSource>();
                attempts.add(attempt);
                return attempt;
            };
            Progress firstProgress = new Progress();
            Progress secondProgress = new Progress();
            var first = SoundCache.getAudioStream(URL, firstProgress, AudioSource.AudioFileType.STREAM, resolver);
            var second = SoundCache.getAudioStream(URL, secondProgress, AudioSource.AudioFileType.STREAM, resolver);
            assertEquals(List.of(firstProgress, secondProgress), listeners);
            if (cancel) {
                assertTrue(first.cancel(false));
            } else {
                attempts.get(0).completeExceptionally(new IOException("fixture failure"));
                assertThrows(CompletionException.class, first::join);
            }
            assertEquals(1, firstProgress.failures.get());
            assertEquals(0, secondProgress.failures.get());
            assertFalse(second.isDone());
            AudioSource source = memoizedSource(new OwnedStream());
            attempts.get(1).complete(source);
            assertSame(source, second.join());
            assertEquals(0, secondProgress.failures.get());
            assertTrue(first.isCompletedExceptionally());
        }
    }

    @Test
    void completedRequestsAreNotReusedAndEachCallPreservesItsInputTypeAndListener() {
        AtomicInteger calls = new AtomicInteger();
        Progress listener = new Progress();
        SoundCache.SourceResolver resolver = (url, progress, type) -> {
            assertEquals(URL, url);
            assertSame(listener, progress);
            assertEquals(AudioSource.AudioFileType.values()[calls.getAndIncrement() % 3], type);
            return CompletableFuture.completedFuture(memoizedSource(new OwnedStream()));
        };
        List<AudioSource> sources = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            sources.add(SoundCache.getAudioStream(URL, listener, AudioSource.AudioFileType.values()[i % 3], resolver).join());
        }
        assertEquals(6, calls.get());
        assertEquals(6, sources.stream().distinct().count());
        assertEquals(0, listener.failures.get());
    }

    @Test
    void malformedUrlsBecomeFailedRequestFuturesAndNotifyOnlyTheirOwnListener() {
        Progress listener = new Progress();
        MalformedURLException failure = new MalformedURLException("fixture invalid URL");
        SoundCache.SourceResolver resolver = (url, progress, type) -> { throw failure; };
        var request = SoundCache.getAudioStream("bad URL", listener, AudioSource.AudioFileType.FILE, resolver);
        assertSame(failure, assertThrows(CompletionException.class, request::join).getCause());
        assertEquals(1, listener.failures.get());
        var silent = SoundCache.getAudioStream("bad URL", null, AudioSource.AudioFileType.FILE, resolver);
        assertSame(failure, assertThrows(CompletionException.class, silent::join).getCause());
        assertEquals(1, listener.failures.get());
    }

    private static AudioSource memoizedSource(InputStream stream) {
        // Models both legacy source implementations: all opens on one source return one future/stream.
        return new AudioSource() {
            private final CompletableFuture<InputStream> opened = CompletableFuture.completedFuture(stream);

            @Override
            public CompletableFuture<InputStream> openStream() {
                return opened;
            }
        };
    }

    private static final class OwnedStream extends ByteArrayInputStream {
        private boolean closed;

        private OwnedStream() {
            super(new byte[]{1, 2, 3});
        }

        @Override
        public synchronized int read() {
            if (closed) {
                throw new IllegalStateException("Stream was closed");
            }
            return super.read();
        }

        @Override public void close() { closed = true; }
    }

    private static final class Progress implements DownloadProgressListener {
        private final AtomicInteger failures = new AtomicInteger();

        @Override public void progressStartRequest(Component component) { fail("Resolution wrapper emitted progress"); }
        @Override public void progressStartDownload(float size) { fail("Resolution wrapper downloaded media"); }
        @Override public void progressStagePercentage(int percentage) { fail("Resolution wrapper downloaded media"); }
        @Override public void progressStartLoading() { fail("Resolution wrapper decoded audio"); }
        @Override public void onSuccess() { fail("Resolution wrapper completed playback"); }
        @Override public void onFail() { failures.incrementAndGet(); }
    }
}
