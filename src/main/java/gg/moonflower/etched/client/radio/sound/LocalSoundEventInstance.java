package gg.moonflower.etched.client.radio.sound;

import gg.moonflower.etched.common.audio.AudioCancellation;
import gg.moonflower.etched.client.radio.PlaybackOwnerKey;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Vanilla sound event positioned at a block or a live entity. */
final class LocalSoundEventInstance extends AbstractTickableSoundInstance implements PlaybackStopListener {

    private final AudioCancellation cancellation;
    private final Runnable soundStopped;
    private final AtomicBoolean stopReported = new AtomicBoolean();
    private final Supplier<Vec3> entityPosition;
    private volatile boolean stopRequested;

    LocalSoundEventInstance(PlaybackOwnerKey.BlockOwner key, ResourceLocation event,
                              AudioCancellation cancellation, Runnable soundStopped, boolean muffled) {
        this(event, cancellation, soundStopped, muffled ? 2.0F : 4.0F,
                new Vec3(key.pos().getX() + 0.5, key.pos().getY() + 0.5, key.pos().getZ() + 0.5), null);
    }

    LocalSoundEventInstance(PlaybackOwnerKey.EntityOwner key, ResourceLocation event,
                            AudioCancellation cancellation, Runnable soundStopped, Supplier<Vec3> position) {
        this(event, cancellation, soundStopped, 4.0F,
                Objects.requireNonNull(position, "position").get(), position);
        Objects.requireNonNull(key, "key");
    }

    private LocalSoundEventInstance(ResourceLocation event, AudioCancellation cancellation,
                                    Runnable soundStopped, float volume, Vec3 initialPosition,
                                    Supplier<Vec3> entityPosition) {
        super(SoundEvent.createVariableRangeEvent(Objects.requireNonNull(event, "event")),
                SoundSource.RECORDS, SoundInstance.createUnseededRandom());
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        this.soundStopped = Objects.requireNonNull(soundStopped, "soundStopped");
        this.entityPosition = entityPosition;
        this.volume = volume;
        Vec3 position = Objects.requireNonNull(initialPosition, "Playback owner is unavailable");
        this.x = position.x;
        this.y = position.y;
        this.z = position.z;
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
        } else if (this.entityPosition != null) {
            Vec3 position = this.entityPosition.get();
            if (position == null) {
                this.stop();
            } else {
                this.x = position.x;
                this.y = position.y;
                this.z = position.z;
            }
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
