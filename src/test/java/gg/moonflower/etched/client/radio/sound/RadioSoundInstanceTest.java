package gg.moonflower.etched.client.radio.sound;

import gg.moonflower.etched.client.radio.PlaybackOwnerKey;
import gg.moonflower.etched.client.radio.MinecraftTestBootstrap;
import gg.moonflower.etched.client.radio.PlaybackSession;
import gg.moonflower.etched.client.radio.stream.PlaybackAudioStream;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RadioSoundInstanceTest {

    static {
        MinecraftTestBootstrap.bootStrap();
    }

    @Test
    void exposesPositionalStreamingRecordsSound() {
        PlaybackSession.Attempt attempt = new PlaybackSession().start("https://radio.example/live");
        RadioSoundInstance sound = sound(attempt, new FakeAudioStream(), new AtomicInteger());

        WeighedSoundEvents resolved = sound.resolve(null);

        assertEquals(SoundSource.RECORDS, sound.getSource());
        assertEquals(SoundInstance.Attenuation.LINEAR, sound.getAttenuation());
        assertEquals(4.0F, sound.getVolume());
        assertEquals(2.5, sound.getX());
        assertEquals(3.5, sound.getY());
        assertEquals(4.5, sound.getZ());
        assertFalse(sound.isLooping());
        assertFalse(sound.isRelative());
        assertTrue(resolved.getSound(SoundInstance.createUnseededRandom()).shouldStream());
    }

    @Test
    void transfersExactStreamOnceAndReportsHandoff() throws Exception {
        PlaybackSession.Attempt attempt = new PlaybackSession().start("https://radio.example/live");
        FakeAudioStream stream = new FakeAudioStream();
        AtomicInteger started = new AtomicInteger();
        RadioSoundInstance sound = sound(attempt, stream, started);
        sound.resolve(null);

        assertSame(stream, sound.getStream(null, sound.getSound(), false).get());
        assertEquals(1, started.get());
        assertTrue(sound.streamTransferred());
        assertTrue(sound.getStream(null, sound.getSound(), false).isCompletedExceptionally());
    }

    @Test
    void cancellationStopsTickableSoundBeforeHandoff() throws Exception {
        PlaybackSession session = new PlaybackSession();
        PlaybackSession.Attempt attempt = session.start("https://radio.example/live");
        FakeAudioStream stream = new FakeAudioStream();
        RadioSoundInstance sound = sound(attempt, stream, new AtomicInteger());

        session.stop();
        sound.tick();
        assertTrue(sound.isStopped());
        assertTrue(sound.getStream(null, null, false).isCompletedExceptionally());
        await(() -> stream.closeCount.get() == 1);
    }

    @Test
    void callbackFailureClosesUntransferredStream() throws Exception {
        PlaybackSession.Attempt attempt = new PlaybackSession().start("https://radio.example/live");
        FakeAudioStream stream = new FakeAudioStream();
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath("etched_test", "radio"));
        RadioSoundInstance sound = new RadioSoundInstance(
                PlaybackOwnerKey.block(dimension, BlockPos.ZERO), attempt.generation(), stream,
                attempt.cancellation(), 4.0F, 8, () -> {
            throw new IllegalStateException("handoff failed");
        });

        assertTrue(sound.getStream(null, null, false).isCompletedExceptionally());
        assertFalse(sound.streamTransferred());
        assertTrue(sound.isStopped());
        await(() -> stream.closeCount.get() == 1);
    }

    @Test
    void stopRequestDoesNotWaitForUntransferredStreamClose() throws Exception {
        PlaybackSession.Attempt attempt = new PlaybackSession().start("https://radio.example/live");
        BlockingCloseAudioStream stream = new BlockingCloseAudioStream();
        RadioSoundInstance sound = sound(attempt, stream, new AtomicInteger());
        ExecutorService clientThread = Executors.newSingleThreadExecutor();
        Future<?> stopped = clientThread.submit(sound::requestStop);

        try {
            stopped.get(5, TimeUnit.SECONDS);
            assertTrue(stream.closeStarted.await(5, TimeUnit.SECONDS));
        } finally {
            stream.releaseClose.countDown();
            clientThread.shutdownNow();
        }
        await(() -> stream.closeCount.get() == 1);
    }

    @Test
    void reportsSoundEngineStopOnlyOnce() {
        PlaybackSession.Attempt attempt = new PlaybackSession().start("https://radio.example/live");
        AtomicInteger stopped = new AtomicInteger();
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath("etched_test", "radio"));
        RadioSoundInstance sound = new RadioSoundInstance(
                PlaybackOwnerKey.block(dimension, BlockPos.ZERO), attempt.generation(), new FakeAudioStream(),
                attempt.cancellation(), 4.0F, 8, () -> {
        }, stopped::incrementAndGet);

        PlaybackStopListener listener = sound;
        listener.onStop();
        listener.onStop();

        assertEquals(1, stopped.get());
    }

    @Test
    void engineRemovalNotificationDoesNotCloseTransferredStreamOrRepeatFailedCallback() throws Exception {
        PlaybackSession.Attempt attempt = new PlaybackSession().start("https://radio.example/live");
        FakeAudioStream stream = new FakeAudioStream();
        AtomicInteger stopped = new AtomicInteger();
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath("etched_test", "radio"));
        RadioSoundInstance sound = new RadioSoundInstance(
                PlaybackOwnerKey.block(dimension, BlockPos.ZERO), attempt.generation(), stream,
                attempt.cancellation(), 4.0F, 8, () -> {}, () -> {
                    stopped.incrementAndGet();
                    throw new IllegalStateException("fixture stop callback failure");
                });
        assertSame(stream, sound.getStream(null, null, false).get());

        PlaybackStopListener listener = sound;
        listener.onStop();
        listener.onStop();

        assertEquals(1, stopped.get());
        assertEquals(0, stream.closeCount.get());
        assertFalse(attempt.cancellation().isCancelled());
        stream.close(); // The SoundEngine consumer, not the notification, owns this stream.
        assertEquals(1, stream.closeCount.get());
    }

    @Test
    void entityStreamFollowsPositionAndClosesBeforeHandoffWhenOwnerDisappears() throws Exception {
        PlaybackSession.Attempt attempt = new PlaybackSession().start("https://audio.example/boombox.mp3");
        FakeAudioStream stream = new FakeAudioStream();
        AtomicReference<Vec3> position = new AtomicReference<>(new Vec3(1, 2, 3));
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath("etched_test", "radio"));
        RadioSoundInstance sound = new RadioSoundInstance(
                PlaybackOwnerKey.entity(dimension, new java.util.UUID(0L, 1L)), attempt.generation(),
                stream, attempt.cancellation(), 4.0F, 8, () -> {
                }, () -> {
                }, position::get);

        position.set(new Vec3(4, 5, 6));
        sound.tick();
        assertEquals(4, sound.getX());
        assertEquals(5, sound.getY());
        assertEquals(6, sound.getZ());

        position.set(null);
        sound.tick();
        assertTrue(sound.isStopped());
        assertTrue(sound.getStream(null, null, false).isCompletedExceptionally());
        await(() -> stream.closeCount.get() == 1);
    }

    @Test
    void disappearingEntityDoesNotCloseStreamAlreadyOwnedBySoundEngine() throws Exception {
        PlaybackSession.Attempt attempt = new PlaybackSession().start("https://audio.example/boombox.mp3");
        FakeAudioStream stream = new FakeAudioStream();
        AtomicReference<Vec3> position = new AtomicReference<>(Vec3.ZERO);
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath("etched_test", "radio"));
        RadioSoundInstance sound = new RadioSoundInstance(
                PlaybackOwnerKey.entity(dimension, new java.util.UUID(0L, 2L)), attempt.generation(),
                stream, attempt.cancellation(), 4.0F, 8, () -> {
                }, () -> {
                }, position::get);

        assertSame(stream, sound.getStream(null, null, false).get());
        position.set(null);
        sound.tick();
        assertTrue(sound.isStopped());
        assertEquals(0, stream.closeCount.get());
    }

    private static RadioSoundInstance sound(PlaybackSession.Attempt attempt, PlaybackAudioStream stream,
                                             AtomicInteger started) {
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath("etched_test", "radio"));
        return new RadioSoundInstance(PlaybackOwnerKey.block(dimension, new BlockPos(2, 3, 4)),
                attempt.generation(), stream, attempt.cancellation(), 4.0F, 8,
                started::incrementAndGet);
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Timed out waiting for asynchronous radio cleanup");
            }
            Thread.sleep(10L);
        }
    }

    private static class FakeAudioStream implements PlaybackAudioStream {

        private final CompletableFuture<Termination> termination = new CompletableFuture<>();
        protected final AtomicInteger closeCount = new AtomicInteger();

        @Override
        public AudioFormat getFormat() {
            return new AudioFormat(44_100, 16, 1, true, false);
        }

        @Override
        public ByteBuffer read(int amount) {
            return ByteBuffer.allocateDirect(0);
        }

        @Override
        public CompletionStage<Termination> termination() {
            return this.termination;
        }

        @Override
        public void close() {
            this.closeCount.incrementAndGet();
        }
    }

    private static final class BlockingCloseAudioStream extends FakeAudioStream {

        private final CountDownLatch closeStarted = new CountDownLatch(1);
        private final CountDownLatch releaseClose = new CountDownLatch(1);

        @Override
        public void close() {
            this.closeStarted.countDown();
            try {
                this.releaseClose.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            super.close();
        }
    }
}
