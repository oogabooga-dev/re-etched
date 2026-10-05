package gg.moonflower.etched.api.sound;

import gg.moonflower.etched.api.sound.stream.MonoWrapper;
import gg.moonflower.etched.client.cache.LegacyAudioLoader;
import gg.moonflower.etched.client.radio.MinecraftTestBootstrap;
import gg.moonflower.etched.client.radio.source.AudioResolveContext;
import gg.moonflower.etched.client.radio.source.AudioResolveLimits;
import gg.moonflower.etched.common.audio.AudioCancellation;
import gg.moonflower.etched.common.audio.net.TestAudioHttpResponse;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.util.valueproviders.ConstantFloat;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class LegacyAudioDecoderTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @Test
    void loadingCallbackFailureClosesInputBeforeAnyCodecIsInvoked() {
        var input = new TrackedInput(new byte[]{42});
        IllegalStateException failure = new IllegalStateException("fixture loading listener failure");
        assertSame(failure, assertThrows(CompletionException.class, () -> LegacyAudioDecoder.decode(input,
                () -> { throw failure; }, stream -> stream,
                List.of(stream -> { throw new AssertionError("Loading failure reached decoder"); }))).getCause());
        assertEquals(1, input.closes.get());
    }

    @Test
    void uncheckedLoadingFailureStillRetiresInput() {
        var input = new TrackedInput(new byte[]{42});
        LinkageError failure = new LinkageError("fixture callback linkage failure");
        assertSame(failure, assertThrows(CompletionException.class, () -> LegacyAudioDecoder.decode(input,
                () -> { throw failure; }, stream -> stream, List.of())).getCause());
        assertEquals(1, input.closes.get());
    }

    @Test
    void codecFallbackResetsInputInOrderAndTransfersOwnershipOnlyAfterModification() throws Exception {
        var input = new TrackedInput(new byte[]{42, 43});
        List<Integer> order = new ArrayList<>();
        AtomicReference<FakeAudio> decoded = new AtomicReference<>();
        var result = LegacyAudioDecoder.decode(input, () -> order.add(0), stream -> {
            assertSame(decoded.get(), stream);
            order.add(3);
            return stream;
        }, List.of(stream -> {
            order.add(1);
            assertEquals(42, stream.read());
            throw new IOException("fixture first codec failure");
        }, stream -> {
            order.add(2);
            assertEquals(42, stream.read());
            var audio = new FakeAudio(stream);
            decoded.set(audio);
            return audio;
        }, stream -> { throw new AssertionError("Successful codec reached another fallback"); }));
        assertEquals(List.of(0, 1, 2, 3), order);
        assertEquals(0, input.closes.get());
        assertSame(decoded.get().getFormat(), result.getFormat());
        assertEquals(43, result.read(1).get() & 0xFF);
        result.close();
        result.close();
        assertThrows(IOException.class, () -> result.read(1));
        assertEquals(1, decoded.get().closes.get());
        assertEquals(1, input.closes.get());
    }

    @Test
    void allCodecFailuresKeepTheirOrderAndCloseInput() {
        var input = new TrackedInput(new byte[]{42});
        IOException first = new IOException("fixture ogg failure");
        IOException second = new IOException("fixture wave failure");
        IOException third = new IOException("fixture mp3 failure");
        Throwable failure = assertThrows(CompletionException.class, () -> LegacyAudioDecoder.decode(input,
                () -> {}, stream -> stream, List.of(stream -> { throw first; }, stream -> { throw second; },
                        stream -> { throw third; }))).getCause();
        assertInstanceOf(UnsupportedAudioFileException.class, failure);
        assertArrayEquals(new Throwable[]{first, second, third}, failure.getSuppressed());
        assertEquals(1, input.closes.get());
    }

    @Test
    void resetFailureClosesInputAndDoesNotTryAnotherCodec() {
        var input = new TrackedInput(new byte[32_768]);
        IOException codecFailure = new IOException("fixture oversized probe failure");
        Throwable failure = assertThrows(CompletionException.class, () -> LegacyAudioDecoder.decode(input,
                () -> {}, stream -> stream, List.of(stream -> {
                    assertEquals(16_384, stream.readNBytes(16_384).length);
                    throw codecFailure;
                }, stream -> { throw new AssertionError("Failed reset reached another codec"); }))).getCause();
        assertInstanceOf(IOException.class, failure);
        assertArrayEquals(new Throwable[]{codecFailure}, failure.getSuppressed());
        assertEquals(1, input.closes.get());
    }

    @Test
    void modifierFailureClosesLiveDecoderWithoutCodecFallback() {
        var input = new TrackedInput(new byte[]{42});
        AtomicReference<FakeAudio> decoded = new AtomicReference<>();
        IllegalArgumentException failure = new IllegalArgumentException("fixture modifier failure");
        assertSame(failure, assertThrows(CompletionException.class, () -> LegacyAudioDecoder.decode(input,
                () -> {}, stream -> { throw failure; }, List.of(stream -> {
                    var audio = new FakeAudio(stream);
                    decoded.set(audio);
                    return audio;
                }, stream -> { throw new AssertionError("Modifier failure reached another codec"); }))).getCause());
        assertEquals(1, decoded.get().closes.get());
        assertEquals(1, input.closes.get());
    }

    @Test
    void nullModifierResultAndMonoConstructionFailureBothRetireTheDecoder() {
        for (boolean nullModifier : new boolean[]{false, true}) {
            var input = new TrackedInput(new byte[]{42});
            AtomicInteger closes = new AtomicInteger();
            assertThrows(CompletionException.class, () -> LegacyAudioDecoder.decode(input, () -> {},
                    stream -> nullModifier ? null : new MonoWrapper(stream), List.of(stream -> new FakeAudio(stream) {
                        @Override public AudioFormat getFormat() { throw new IllegalStateException("fixture format failure"); }
                        @Override public void close() throws IOException { closes.incrementAndGet(); super.close(); }
                    })));
            assertEquals(1, closes.get());
            assertEquals(1, input.closes.get());
        }
    }

    @Test
    void codecErrorIsNotRetriedAndStillClosesInput() {
        var input = new TrackedInput(new byte[]{42});
        LinkageError failure = new LinkageError("fixture codec linkage failure");
        assertSame(failure, assertThrows(CompletionException.class, () -> LegacyAudioDecoder.decode(input,
                () -> {}, stream -> stream, List.of(stream -> { throw failure; },
                        stream -> { throw new AssertionError("Codec error reached fallback"); }))).getCause());
        assertEquals(1, input.closes.get());
    }

    @Test
    void successCallbackFailureClosesPublishedDecoderAndInput() {
        var input = new TrackedInput(new byte[]{42});
        AtomicReference<FakeAudio> decoded = new AtomicReference<>();
        IllegalStateException failure = new IllegalStateException("fixture success listener failure");
        var pending = LegacyAudioStreamRequest.start(CompletableFuture.completedFuture(
                        () -> CompletableFuture.completedFuture(input)), Runnable::run,
                owned -> LegacyAudioDecoder.decode(owned, () -> {}, audio -> audio, List.of(source -> {
                    var audio = new FakeAudio(source);
                    decoded.set(audio);
                    return audio;
                })), () -> { throw failure; }, error -> { throw new AssertionError("Success failure invoked onFail"); });
        assertSame(failure, assertThrows(CompletionException.class, pending::join).getCause());
        assertEquals(1, decoded.get().closes.get());
        assertEquals(1, input.closes.get());
    }

    @Test
    void successfulCallbackLeavesStreamOwnedByConsumer() throws Exception {
        var input = new TrackedInput(new byte[]{42});
        AtomicInteger successes = new AtomicInteger();
        var pending = LegacyAudioStreamRequest.start(CompletableFuture.completedFuture(
                        () -> CompletableFuture.completedFuture(input)), Runnable::run,
                owned -> LegacyAudioDecoder.decode(owned, () -> {}, audio -> audio, List.of(FakeAudio::new)),
                successes::incrementAndGet, error -> { throw new AssertionError(error); });
        var stream = pending.join();
        assertEquals(1, successes.get());
        assertEquals(0, input.closes.get());
        stream.close();
        assertEquals(1, input.closes.get());
    }

    @Test
    void failedCleanupKeepsThePrimaryFailureAndStillAttemptsInputClose() {
        IOException inputFailure = new IOException("fixture input close failure");
        TrackedInput input = new TrackedInput(new byte[]{42}) {
            @Override public void close() throws IOException { super.close(); throw inputFailure; }
        };
        IOException decoderFailure = new IOException("fixture decoder close failure");
        var decoded = new FakeAudio(input) {
            @Override public void close() throws IOException { throw decoderFailure; }
        };
        IllegalStateException failure = new IllegalStateException("fixture modifier failure");
        assertSame(failure, assertThrows(CompletionException.class, () -> LegacyAudioDecoder.decode(input,
                () -> {}, stream -> { throw failure; }, List.of(stream -> decoded))).getCause());
        assertArrayEquals(new Throwable[]{decoderFailure, inputFailure}, failure.getSuppressed());
        assertEquals(1, input.closeCount());
    }

    @Test
    void consumerCloseStillClosesInputWhenDecoderCloseFailsAndIsIdempotent() throws Exception {
        var input = new TrackedInput(new byte[]{42});
        IOException failure = new IOException("fixture decoder close failure");
        AtomicInteger decoderCloses = new AtomicInteger();
        var stream = LegacyAudioDecoder.decode(input, () -> {}, audio -> audio, List.of(source -> new FakeAudio(source) {
            @Override public void close() throws IOException { decoderCloses.incrementAndGet(); throw failure; }
        }));
        assertSame(failure, assertThrows(IOException.class, stream::close));
        stream.close();
        assertEquals(1, decoderCloses.get());
        assertEquals(1, input.closes.get());
    }

    @Test
    void defaultWaveCodecStillDeliversPcmAndLoopingRetainsInputOwnership() throws Exception {
        for (boolean repeat : new boolean[]{false, true}) {
            var input = new TrackedInput(wave());
            try (var stream = LegacyAudioDecoder.decode(input, () -> {}, audio -> audio,
                    LegacyAudioDecoder.codecs(repeat).subList(1, 2))) {
                assertEquals(1, stream.getFormat().getChannels());
                assertEquals(16, stream.getFormat().getSampleSizeInBits());
                ByteBuffer pcm = stream.read(8);
                assertEquals(8, pcm.remaining());
                assertEquals(1, pcm.order(ByteOrder.nativeOrder()).getShort());
                if (repeat) {
                    assertEquals(8, stream.read(8).remaining());
                }
            }
            assertEquals(1, input.closes.get());
        }
    }

    @Test
    void defaultMp3CodecStillFollowsWaveFallbackAndDeliversPcm() throws Exception {
        byte[] bytes;
        try (var fixture = getClass().getResourceAsStream("/gg/moonflower/etched/client/radio/audio/mono.mp3")) {
            bytes = fixture.readAllBytes();
        }
        var input = new TrackedInput(bytes);
        try (var stream = LegacyAudioDecoder.decode(input, () -> {}, audio -> audio,
                LegacyAudioDecoder.codecs(false).subList(1, 3))) {
            assertEquals(1, stream.getFormat().getChannels());
            assertEquals(16, stream.getFormat().getSampleSizeInBits());
            assertTrue(stream.read(4096).remaining() > 0);
        }
        assertEquals(1, input.closes.get());
    }

    @Test
    void defaultOggDecodeAndMonoModificationRetainConsumerOwnershipForFiniteAndLoopingAudio() throws Exception {
        byte[] bytes;
        try (var fixture = getClass().getResourceAsStream("/gg/moonflower/etched/client/radio/audio/stereo.ogg")) {
            bytes = fixture.readAllBytes();
        }
        var sound = new Sound("etched:fixture", ConstantFloat.of(1.0F), ConstantFloat.of(1.0F),
                1, Sound.Type.FILE, true, false, 16);
        for (boolean repeat : new boolean[]{false, true}) {
            var input = new TrackedInput(bytes);
            var stream = LegacyAudioDecoder.decode(input, sound, repeat, () -> {});
            assertEquals(1, stream.getFormat().getChannels());
            assertTrue(stream.read(1024).remaining() > 0);
            assertEquals(0, input.closes.get());
            stream.close();
            stream.close();
            assertEquals(1, input.closes.get());
        }
    }

    @Test
    void decodeCallbackFailureClosesAndCancelsItsOwnedCommonTransportResponse() throws Exception {
        var body = new TrackedInput(new byte[]{42});
        AudioCancellation cancellation = new AudioCancellation();
        URI uri = URI.create("https://audio.example/decode-failure");
        Function<AudioCancellation, AudioResolveContext> contexts = token -> new AudioResolveContext(
                (request, scope) -> TestAudioHttpResponse.owned(uri, 200, Map.of(), body, scope),
                ignored -> {}, token, AudioResolveLimits.DEFAULT);
        InputStream input = LegacyAudioLoader.stream(uri, cancellation, contexts);
        assertThrows(CompletionException.class, () -> LegacyAudioDecoder.decode(input,
                () -> { throw new IllegalStateException("fixture listener failure"); }, audio -> audio, List.of()));
        assertTrue(cancellation.isCancelled());
        assertEquals(1, body.closes.get());
    }

    private static byte[] wave() {
        ByteBuffer bytes = ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put(new byte[]{'R', 'I', 'F', 'F'}).putInt(44).put(new byte[]{'W', 'A', 'V', 'E'});
        bytes.put(new byte[]{'f', 'm', 't', ' '}).putInt(16).putShort((short) 1).putShort((short) 1);
        bytes.putInt(44100).putInt(88200).putShort((short) 2).putShort((short) 16);
        bytes.put(new byte[]{'d', 'a', 't', 'a'}).putInt(8);
        bytes.putShort((short) 1).putShort((short) 2).putShort((short) 3).putShort((short) 4);
        return bytes.array();
    }

    private static class TrackedInput extends ByteArrayInputStream {
        private final AtomicInteger closes = new AtomicInteger();
        private TrackedInput(byte[] bytes) { super(bytes); }
        private int closeCount() { return this.closes.get(); }
        @Override public void close() throws IOException { this.closes.incrementAndGet(); }
    }

    private static class FakeAudio implements AudioStream {
        private static final AudioFormat FORMAT = new AudioFormat(44100, 16, 1, true, false);
        private final InputStream input;
        private final AtomicInteger closes = new AtomicInteger();
        private FakeAudio(InputStream input) { this.input = input; }
        @Override public AudioFormat getFormat() { return FORMAT; }
        @Override public ByteBuffer read(int amount) throws IOException { return ByteBuffer.wrap(this.input.readNBytes(amount)); }
        @Override public void close() throws IOException { this.closes.incrementAndGet(); this.input.close(); }
    }
}
