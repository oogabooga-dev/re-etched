package gg.moonflower.etched.api.sound;

import gg.moonflower.etched.client.sound.RecordSoundCompletion;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StopListeningSoundTest {
    @Test
    void drainedTrackAdvancesExactlyOnceAndReleasesItsLoader() {
        FiniteSound source = new FiniteSound(true);
        AtomicInteger next = new AtomicInteger();
        StopListeningSound wrapper = StopListeningSound.create(source, next::incrementAndGet);
        wrapper.onStop();
        wrapper.onStop();
        assertEquals(1, next.get());
        assertEquals(1, source.stops);
    }

    @Test
    void failedAudioDoesNotStartAnInfiniteAlbumRetryLoop() {
        FiniteSound source = new FiniteSound(false);
        AtomicInteger next = new AtomicInteger();
        StopListeningSound wrapper = StopListeningSound.create(source, next::incrementAndGet);
        wrapper.onStop();
        assertEquals(0, next.get());
        assertEquals(1, source.stops);
    }

    @Test
    void manualStopCancelsLoadingImmediatelyAndSuppressesEvenAnAlreadyDecodedEof() {
        FiniteSound source = new FiniteSound(true);
        AtomicInteger next = new AtomicInteger();
        StopListeningSound wrapper = StopListeningSound.create(source, next::incrementAndGet);
        wrapper.stopListening();
        assertEquals(1, source.stops);
        wrapper.onStop();
        wrapper.stopListening();
        assertEquals(1, source.stops);
        assertEquals(0, next.get());
    }

    private static final class FiniteSound extends AbstractSoundInstance implements RecordSoundCompletion {
        private final boolean completed;
        private int stops;

        private FiniteSound(boolean completed) {
            super(new ResourceLocation("etched_test", "disc"), SoundSource.RECORDS, SoundInstance.createUnseededRandom());
            this.completed = completed;
        }

        public boolean completedNormally() { return this.completed; }

        public void onStop() { this.stops++; }
    }
}
