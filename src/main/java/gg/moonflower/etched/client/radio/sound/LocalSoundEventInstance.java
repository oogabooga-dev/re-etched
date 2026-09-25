package gg.moonflower.etched.client.radio.sound;

import gg.moonflower.etched.api.sound.SoundStopListener;
import gg.moonflower.etched.client.radio.AudioCancellation;
import gg.moonflower.etched.client.radio.PlaybackOwnerKey;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Block-positioned vanilla sound event with an idempotent engine-stop signal. */
final class LocalSoundEventInstance extends AbstractTickableSoundInstance implements SoundStopListener {

    private final AudioCancellation cancellation;
    private final Runnable soundStopped;
    private final AtomicBoolean stopReported = new AtomicBoolean();
    private volatile boolean stopRequested;

    LocalSoundEventInstance(PlaybackOwnerKey.BlockOwner key, ResourceLocation event,
                            AudioCancellation cancellation, Runnable soundStopped) {
        super(SoundEvent.createVariableRangeEvent(Objects.requireNonNull(event, "event")),
                SoundSource.RECORDS, SoundInstance.createUnseededRandom());
        Objects.requireNonNull(key, "key");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        this.soundStopped = Objects.requireNonNull(soundStopped, "soundStopped");
        this.volume = 4.0F;
        this.x = key.pos().getX() + 0.5;
        this.y = key.pos().getY() + 0.5;
        this.z = key.pos().getZ() + 0.5;
        this.looping = false;
        this.relative = false;
        this.attenuation = Attenuation.LINEAR;
        cancellation.onCancel(this::requestStop);
    }

    void requestStop() {
        this.stopRequested = true;
    }

    @Override
    public void tick() {
        if (this.stopRequested || this.cancellation.isCancelled()) {
            this.stop();
        }
    }

    @Override
    public void onStop() {
        if (this.stopReported.compareAndSet(false, true)) {
            try {
                this.soundStopped.run();
            } catch (RuntimeException ignored) {
            }
        }
    }
}
