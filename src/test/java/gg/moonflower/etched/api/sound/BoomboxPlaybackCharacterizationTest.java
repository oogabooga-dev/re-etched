package gg.moonflower.etched.api.sound;

import gg.moonflower.etched.api.sound.source.AudioSource;
import gg.moonflower.etched.api.util.DownloadProgressListener;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoomboxPlaybackCharacterizationTest {

    private static final DownloadProgressListener NOOP_PROGRESS = new DownloadProgressListener() {
        @Override
        public void progressStartRequest(Component component) {
        }

        @Override
        public void progressStartDownload(float size) {
        }

        @Override
        public void progressStagePercentage(int percentage) {
        }

        @Override
        public void progressStartLoading() {
        }

        @Override
        public void onSuccess() {
        }

        @Override
        public void onFail() {
        }
    };

    @Test
    void tickablePlaybackFollowsItsOwnerAndStopsWhenRemoved() {
        MutableOwner owner = new MutableOwner();
        OnlineRecordSoundInstance source = sound(owner);
        StopListeningSound wrapped = StopListeningSound.create(source, () -> {
        });
        TickableSoundInstance tickable = assertInstanceOf(TickableSoundInstance.class, wrapped);

        owner.set(1.25, 2.5, 3.75);
        tickable.tick();
        assertEquals(1.25, wrapped.getX());
        assertEquals(2.5, wrapped.getY());
        assertEquals(3.75, wrapped.getZ());
        assertFalse(tickable.isStopped());

        owner.set(-4.0, 8.0, 16.0);
        tickable.tick();
        assertEquals(-4.0, wrapped.getX());
        assertEquals(8.0, wrapped.getY());
        assertEquals(16.0, wrapped.getZ());

        owner.alive = false;
        tickable.tick();
        assertTrue(tickable.isStopped());
    }

    @Test
    void completionAdvancesUnlessReplacementSuppressesTheCallback() {
        AtomicInteger advances = new AtomicInteger();
        StopListeningSound completed = StopListeningSound.create(sound(new MutableOwner()), advances::incrementAndGet);
        completed.onStop();
        assertEquals(1, advances.get());

        StopListeningSound replaced = StopListeningSound.create(sound(new MutableOwner()), advances::incrementAndGet);
        replaced.stopListening();
        replaced.onStop();
        assertEquals(1, advances.get());
    }

    @Test
    void loopingRestartsAtTrackZeroOnlyAfterASequenceHasStarted() {
        assertEquals(0, EntityPlaybackSequence.getLoopFallbackTrack(3, true));
        assertEquals(-1, EntityPlaybackSequence.getLoopFallbackTrack(0, true));
        assertEquals(-1, EntityPlaybackSequence.getLoopFallbackTrack(3, false));
    }

    private static OnlineRecordSoundInstance sound(OnlineRecordSoundInstance.Owner owner) {
        return new OnlineRecordSoundInstance("https://audio.example/track.mp3", owner, 4.0F, 8,
                NOOP_PROGRESS, AudioSource.AudioFileType.FILE, false);
    }

    private static final class MutableOwner implements OnlineRecordSoundInstance.Owner {

        private boolean alive = true;
        private double x;
        private double y;
        private double z;

        private void set(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public boolean isAlive() {
            return this.alive;
        }

        @Override
        public double getX() {
            return this.x;
        }

        @Override
        public double getY() {
            return this.y;
        }

        @Override
        public double getZ() {
            return this.z;
        }
    }
}
