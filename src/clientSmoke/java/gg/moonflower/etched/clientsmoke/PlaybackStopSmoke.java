package gg.moonflower.etched.clientsmoke;

import gg.moonflower.etched.client.radio.sound.PlaybackStopListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/** Exercises both required SoundEngine injections with actual vanilla audio, not direct callbacks. */
final class PlaybackStopSmoke {

    private static ProbeSound removed;
    private static ProbeSound stoppedAll;
    private static int step;
    private static int ticks;

    private PlaybackStopSmoke() {
    }

    static boolean tick(Minecraft client) {
        if (step == 4) {
            return true;
        }
        if (step == 0) {
            if (!(client.screen instanceof TitleScreen)) {
                return false;
            }
            removed = new ProbeSound();
            client.getSoundManager().play(removed);
            step = 1;
        }
        if (++ticks > 200) {
            throw new AssertionError("SoundEngine stop notification smoke timed out at step " + step);
        }
        if (step == 1 && ticks >= 20 && client.getSoundManager().isActive(removed)) {
            removed.finish();
            step = 2;
            ticks = 0;
        }
        if (step == 2 && removed.notifications == 1) {
            stoppedAll = new ProbeSound();
            client.getSoundManager().play(stoppedAll);
            step = 3;
            ticks = 0;
        }
        if (step == 3 && ticks >= 20 && client.getSoundManager().isActive(stoppedAll)) {
            client.getSoundManager().stop();
            client.getSoundManager().stop();
            if (removed.notifications != 1 || stoppedAll.notifications != 1) {
                throw new AssertionError("SoundEngine did not deliver exactly one internal removal/stopAll notification");
            }
            step = 4;
            return true;
        }
        return false;
    }

    private static final class ProbeSound extends AbstractTickableSoundInstance implements PlaybackStopListener {

        private int notifications;

        private ProbeSound() {
            super(SoundEvents.MUSIC_DISC_CAT, SoundSource.RECORDS, SoundInstance.createUnseededRandom());
            this.relative = true;
            this.attenuation = Attenuation.NONE;
            this.volume = 0.01F;
        }

        private void finish() {
            this.stop();
        }

        @Override
        public void tick() {
        }

        @Override
        public void onStop() {
            this.notifications++;
        }
    }
}
