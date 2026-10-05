package gg.moonflower.etched.client.radio.sound;

import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.ApiStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Internal vanilla-record delegate. Only reports engine removal; never owns the delegate's stream.
 *
 * @author Ocelot
 */
@ApiStatus.Internal
public class RecordSoundInstance implements SoundInstance, PlaybackStopListener {

    private static final Logger LOGGER = LogManager.getLogger();
    private final SoundInstance source;
    private final Consumer<SoundInstance> stopped;
    private final AtomicBoolean stopReported = new AtomicBoolean();

    private RecordSoundInstance(SoundInstance source, Consumer<SoundInstance> stopped) {
        this.source = Objects.requireNonNull(source, "source");
        this.stopped = Objects.requireNonNull(stopped, "stopped");
    }

    public static RecordSoundInstance wrap(SoundInstance source, Consumer<SoundInstance> stopped) {
        return source instanceof TickableSoundInstance tickable
                ? new TickableRecordSound(tickable, stopped) : new RecordSoundInstance(source, stopped);
    }

    @Override
    public ResourceLocation getLocation() {
        return this.source.getLocation();
    }

    @Nullable
    @Override
    public WeighedSoundEvents resolve(SoundManager soundManager) {
        return this.source.resolve(soundManager);
    }

    @Override
    public Sound getSound() {
        return this.source.getSound();
    }

    @Override
    public SoundSource getSource() {
        return this.source.getSource();
    }

    @Override
    public boolean isLooping() {
        return this.source.isLooping();
    }

    @Override
    public boolean isRelative() {
        return this.source.isRelative();
    }

    @Override
    public int getDelay() {
        return this.source.getDelay();
    }

    @Override
    public float getVolume() {
        return this.source.getVolume();
    }

    @Override
    public float getPitch() {
        return this.source.getPitch();
    }

    @Override
    public double getX() {
        return this.source.getX();
    }

    @Override
    public double getY() {
        return this.source.getY();
    }

    @Override
    public double getZ() {
        return this.source.getZ();
    }

    @Override
    public Attenuation getAttenuation() {
        return this.source.getAttenuation();
    }

    @Override
    public boolean canStartSilent() {
        return this.source.canStartSilent();
    }

    @Override
    public boolean canPlaySound() {
        return this.source.canPlaySound();
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping) {
        return this.source.getStream(soundBuffers, sound, looping);
    }

    @Override
    public void onStop() {
        if (this.stopReported.compareAndSet(false, true)) {
            try {
                this.stopped.accept(this);
            } catch (RuntimeException failure) {
                LOGGER.warn("Could not notify vanilla record removal", failure);
            }
        }
    }

    private static final class TickableRecordSound extends RecordSoundInstance implements TickableSoundInstance {

        private final TickableSoundInstance tickable;

        private TickableRecordSound(TickableSoundInstance source, Consumer<SoundInstance> stopped) {
            super(source, stopped);
            this.tickable = source;
        }

        @Override
        public void tick() {
            this.tickable.tick();
        }

        @Override
        public boolean isStopped() {
            return this.tickable.isStopped();
        }
    }
}
