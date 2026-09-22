package gg.moonflower.etched.client.radio.sound;

import gg.moonflower.etched.client.radio.RadioCancellation;
import gg.moonflower.etched.client.radio.RadioKey;
import gg.moonflower.etched.client.radio.RadioResourceDisposer;
import gg.moonflower.etched.client.radio.stream.RadioAudioStream;
import gg.moonflower.etched.api.sound.SoundStopListener;
import gg.moonflower.etched.core.Etched;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.valueproviders.ConstantFloat;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Positional streaming sound backed exclusively by one radio audio stream. */
public final class RadioSoundInstance extends AbstractTickableSoundInstance implements SoundStopListener {

    private static final ResourceLocation LOCATION = ResourceLocation.fromNamespaceAndPath(Etched.MOD_ID, "radio_stream");
    private static final SoundEvent EVENT = SoundEvent.createVariableRangeEvent(LOCATION);

    private final RadioKey key;
    private final long generation;
    private final RadioAudioStream stream;
    private final RadioCancellation cancellation;
    private final int attenuationDistance;
    private final Runnable streamHandedOff;
    private final Runnable soundStopped;
    private volatile boolean transferred;
    private boolean untransferredClosed;
    private boolean stopReported;
    private volatile boolean stopRequested;

    public RadioSoundInstance(RadioKey key, long generation, RadioAudioStream stream,
                               RadioCancellation cancellation, float volume,
                               int attenuationDistance, Runnable streamStarted) {
        this(key, generation, stream, cancellation, volume, attenuationDistance, streamStarted, () -> {
        });
    }

    public RadioSoundInstance(RadioKey key, long generation, RadioAudioStream stream,
                              RadioCancellation cancellation, float volume,
                              int attenuationDistance, Runnable streamHandedOff, Runnable soundStopped) {
        super(EVENT, SoundSource.RECORDS, SoundInstance.createUnseededRandom());
        this.key = Objects.requireNonNull(key, "key");
        this.generation = generation;
        this.stream = Objects.requireNonNull(stream, "stream");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        if (!Float.isFinite(volume) || volume < 0.0F) {
            throw new IllegalArgumentException("volume must be finite and non-negative");
        }
        if (attenuationDistance <= 0) {
            throw new IllegalArgumentException("attenuationDistance must be positive");
        }
        this.attenuationDistance = attenuationDistance;
        this.streamHandedOff = Objects.requireNonNull(streamHandedOff, "streamHandedOff");
        this.soundStopped = Objects.requireNonNull(soundStopped, "soundStopped");
        this.volume = volume;
        this.x = key.pos().getX() + 0.5;
        this.y = key.pos().getY() + 0.5;
        this.z = key.pos().getZ() + 0.5;
        this.looping = false;
        this.relative = false;
        this.attenuation = Attenuation.LINEAR;
        cancellation.onCancel(this::requestStop);
    }

    public RadioKey key() {
        return this.key;
    }

    public long generation() {
        return this.generation;
    }

    public boolean streamTransferred() {
        return this.transferred;
    }

    public void requestStop() {
        boolean closeStream;
        synchronized (this) {
            this.stopRequested = true;
            closeStream = !this.transferred && !this.untransferredClosed;
            this.untransferredClosed |= closeStream;
        }
        if (closeStream) {
            RadioResourceDisposer.dispose(this::closeUntransferred);
        }
    }

    @Override
    public WeighedSoundEvents resolve(SoundManager soundManager) {
        WeighedSoundEvents event = new WeighedSoundEvents(this.getLocation(), null);
        event.addSound(new Sound(this.getLocation().toString(), ConstantFloat.of(1.0F),
                ConstantFloat.of(1.0F), 1, Sound.Type.FILE, true, false,
                this.attenuationDistance));
        this.sound = event.getSound(this.random);
        return event;
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary loader, Sound sound,
                                                     boolean repeatInstantly) {
        boolean closeStream = false;
        boolean unavailable;
        synchronized (this) {
            unavailable = this.cancellation.isCancelled() || this.stopRequested;
            if (unavailable) {
                closeStream = !this.untransferredClosed;
                this.untransferredClosed = true;
            } else if (this.transferred) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Radio audio stream was already transferred"));
            } else {
                this.transferred = true;
            }
        }
        if (unavailable) {
            if (closeStream) {
                RadioResourceDisposer.dispose(this::closeUntransferred);
            }
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Radio sound was stopped before stream handoff"));
        }
        try {
            this.streamHandedOff.run();
        } catch (Throwable exception) {
            synchronized (this) {
                this.transferred = false;
                this.untransferredClosed = true;
                this.stopRequested = true;
            }
            this.stop();
            RadioResourceDisposer.dispose(this::closeUntransferred);
            return CompletableFuture.failedFuture(exception);
        }
        return CompletableFuture.completedFuture(this.stream);
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public void tick() {
        if (this.stopRequested || this.cancellation.isCancelled()) {
            this.requestStop();
            this.stop();
        }
    }

    @Override
    public void onStop() {
        synchronized (this) {
            if (this.stopReported) {
                return;
            }
            this.stopReported = true;
        }
        try {
            this.soundStopped.run();
        } catch (RuntimeException ignored) {
        }
    }

    private void closeUntransferred() {
        try {
            this.stream.close();
        } catch (java.io.IOException ignored) {
        }
    }
}
