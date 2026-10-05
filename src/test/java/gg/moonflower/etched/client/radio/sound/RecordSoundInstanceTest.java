package gg.moonflower.etched.client.radio.sound;

import gg.moonflower.etched.client.radio.MinecraftTestBootstrap;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RecordSoundInstanceTest {

    static {
        MinecraftTestBootstrap.bootStrap();
    }

    @Test
    void delegatesVanillaPropertiesResolutionAndStreamWithoutTakingOwnership() throws Exception {
        FakeSound source = new FakeSound();
        AtomicReference<SoundInstance> removed = new AtomicReference<>();
        RecordSoundInstance wrapped = RecordSoundInstance.wrap(source, removed::set);
        assertFalse(wrapped instanceof TickableSoundInstance);
        assertEquals(source.getLocation(), wrapped.getLocation());
        assertSame(source.getSound(), wrapped.getSound());
        assertSame(source.getSource(), wrapped.getSource());
        assertEquals(source.getVolume(), wrapped.getVolume());
        assertEquals(source.getPitch(), wrapped.getPitch());
        assertEquals(source.getDelay(), wrapped.getDelay());
        assertEquals(source.getX(), wrapped.getX());
        assertEquals(source.getY(), wrapped.getY());
        assertEquals(source.getZ(), wrapped.getZ());
        assertEquals(source.getAttenuation(), wrapped.getAttenuation());
        assertEquals(source.isRelative(), wrapped.isRelative());
        assertEquals(source.isLooping(), wrapped.isLooping());
        assertEquals(source.canStartSilent(), wrapped.canStartSilent());
        assertEquals(source.canPlaySound(), wrapped.canPlaySound());
        assertSame(source.resolved, wrapped.resolve(null));
        assertEquals(1, source.resolves.get());

        assertSame(source.open, wrapped.getStream(null, source.getSound(), true));
        assertTrue(source.repeat);
        assertSame(source.stream, source.open.join());
        wrapped.onStop();
        assertSame(wrapped, removed.get());
        assertFalse(source.open.isCancelled());
        assertEquals(0, source.stream.closes.get());
        source.stream.close();
        assertEquals(1, source.stream.closes.get());
    }

    @Test
    void tickableDelegateKeepsItsTickAndStoppedState() {
        FakeTickableSound source = new FakeTickableSound();
        RecordSoundInstance wrapped = RecordSoundInstance.wrap(source, removed -> {});
        TickableSoundInstance tickable = assertInstanceOf(TickableSoundInstance.class, wrapped);
        assertFalse(tickable.isStopped());
        tickable.tick();
        assertEquals(1, source.ticks);
        assertEquals(12.5, wrapped.getX());
        source.stopped = true;
        assertTrue(tickable.isStopped());
    }

    @Test
    void notificationIsExactlyOnceEvenWhenItsCallbackFails() {
        AtomicInteger notifications = new AtomicInteger();
        RecordSoundInstance wrapped = RecordSoundInstance.wrap(new FakeSound(), removed -> {
            notifications.incrementAndGet();
            throw new IllegalStateException("fixture record callback failure");
        });
        assertDoesNotThrow(wrapped::onStop);
        assertDoesNotThrow(wrapped::onStop);
        assertEquals(1, notifications.get());
    }

    @Test
    void concurrentRemovalNotificationsCannotDeliverTwice() throws Exception {
        AtomicInteger notifications = new AtomicInteger();
        RecordSoundInstance wrapped = RecordSoundInstance.wrap(new FakeSound(), removed -> notifications.incrementAndGet());
        var workers = Executors.newFixedThreadPool(4);
        try {
            var calls = new ArrayList<Callable<Void>>();
            for (int i = 0; i < 32; i++) {
                calls.add(() -> { wrapped.onStop(); return null; });
            }
            for (var call : workers.invokeAll(calls, 5, TimeUnit.SECONDS)) {
                call.get();
            }
            assertEquals(1, notifications.get());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void missingDelegateOrCallbackIsRejectedBeforeSoundEngineRegistration() {
        assertThrows(NullPointerException.class, () -> RecordSoundInstance.wrap(null, removed -> {}));
        assertThrows(NullPointerException.class, () -> RecordSoundInstance.wrap(new FakeSound(), null));
    }

    private static class FakeSound extends AbstractSoundInstance {

        private final AtomicInteger resolves = new AtomicInteger();
        private final WeighedSoundEvents resolved = new WeighedSoundEvents(this.getLocation(), null);
        private final FakeStream stream = new FakeStream();
        private final CompletableFuture<AudioStream> open = CompletableFuture.completedFuture(this.stream);
        private boolean repeat;

        private FakeSound() {
            super(SoundEvents.MUSIC_DISC_CAT, SoundSource.RECORDS, SoundInstance.createUnseededRandom());
            this.sound = new Sound(this.getLocation().toString(), ConstantFloat.of(1), ConstantFloat.of(1),
                    1, Sound.Type.FILE, true, false, 16);
            this.volume = 0.7F;
            this.pitch = 0.8F;
            this.delay = 3;
            this.looping = true;
            this.x = 1.5;
            this.y = 2.5;
            this.z = 3.5;
        }

        @Override
        public WeighedSoundEvents resolve(SoundManager manager) {
            this.resolves.incrementAndGet();
            return this.resolved;
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }

        @Override
        public boolean canPlaySound() {
            return false;
        }

        @Override
        public CompletableFuture<AudioStream> getStream(SoundBufferLibrary loader, Sound sound, boolean repeat) {
            assertSame(this.sound, sound);
            this.repeat = repeat;
            return this.open;
        }
    }

    private static final class FakeTickableSound extends FakeSound implements TickableSoundInstance {

        private int ticks;
        private boolean stopped;

        @Override
        public void tick() {
            this.ticks++;
            this.x = 12.5;
        }

        @Override
        public boolean isStopped() {
            return this.stopped;
        }
    }

    private static final class FakeStream implements AudioStream {

        private final AtomicInteger closes = new AtomicInteger();

        @Override
        public AudioFormat getFormat() {
            return new AudioFormat(44100, 16, 1, true, false);
        }

        @Override
        public ByteBuffer read(int amount) {
            return ByteBuffer.allocate(0);
        }

        @Override
        public void close() {
            this.closes.incrementAndGet();
        }
    }
}
