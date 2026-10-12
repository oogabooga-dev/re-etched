package gg.moonflower.etched.client.sound;

import gg.moonflower.etched.api.record.TrackData;
import gg.moonflower.etched.api.sound.OnlineRecordSoundInstance;
import gg.moonflower.etched.api.sound.download.SoundSourceManager;
import gg.moonflower.etched.api.sound.source.AudioSource;
import gg.moonflower.etched.api.util.DownloadProgressListener;
import gg.moonflower.etched.client.radio.stream.RadioAudioStream;
import gg.moonflower.etched.client.radio.RecordStreamLoader;
import gg.moonflower.etched.core.Etched;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.world.entity.Entity;

import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/** Legacy-compatible sound facade; online disc audio uses the proven radio pipeline. */
public final class BufferedRecordSound extends OnlineRecordSoundInstance implements RecordSoundCompletion {
    private final String url;
    private final boolean legacy;
    private final DownloadProgressListener progress;
    private final boolean stereo;
    private final BooleanSupplier ownerAlive;
    private RecordStreamLoader.Load load;
    private boolean stopped;
    private volatile boolean completed;

    public BufferedRecordSound(String url, Entity entity, int distance, DownloadProgressListener progress) {
        super(url, entity, distance, progress, AudioSource.AudioFileType.FILE);
        this.url = url;
        this.legacy = TrackData.isLocalSound(url) || SoundSourceManager.hasCustomSource(url);
        this.progress = progress;
        this.stereo = Etched.CLIENT_CONFIG.forceStereo.get() || entity == Minecraft.getInstance().player;
        this.ownerAlive = () -> Minecraft.getInstance().level == entity.level()
                && entity.isAlive() && !entity.isRemoved() && entity.level().getEntity(entity.getId()) == entity;
        this.x = entity.getX();
        this.y = entity.getY();
        this.z = entity.getZ();
    }

    public BufferedRecordSound(String url, double x, double y, double z, float volume, int distance,
                               DownloadProgressListener progress, BooleanSupplier ownerAlive) {
        super(url, x, y, z, volume, distance, progress, AudioSource.AudioFileType.FILE);
        this.url = url;
        this.legacy = TrackData.isLocalSound(url) || SoundSourceManager.hasCustomSource(url);
        this.progress = progress;
        this.stereo = Etched.CLIENT_CONFIG.forceStereo.get();
        this.ownerAlive = ownerAlive;
    }

    @Override
    public synchronized CompletableFuture<AudioStream> getStream(SoundBufferLibrary buffers, Sound sound,
                                                                 boolean repeatInstantly) {
        if (this.legacy) {
            return super.getStream(buffers, sound, repeatInstantly);
        }
        if (this.stopped || this.load != null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Disc sound is no longer available"));
        }
        this.load = RecordStreamLoader.getInstance().open(this.url, this.stereo);
        RecordStreamLoader.Load current = this.load;
        return current.stream().<AudioStream>thenApply(audio -> {
            synchronized (this) {
                if (this.stopped || !current.transfer(audio)) {
                    throw new IllegalStateException("Disc stopped before stream handoff");
                }
                audio.termination().thenAccept(termination -> {
                    this.completed = termination.state() == RadioAudioStream.TerminalState.EOF;
                    if (termination.state() == RadioAudioStream.TerminalState.FAILED) {
                        this.notifyProgress(false);
                    }
                });
            }
            this.notifyProgress(true);
            return audio;
        }).exceptionally(failure -> {
            this.notifyProgress(false);
            return EmptyAudioStream.INSTANCE;
        });
    }

    private void notifyProgress(boolean success) {
        Minecraft.getInstance().execute(() -> {
            synchronized (this) {
                if (this.stopped || !this.ownerAlive.getAsBoolean()) {
                    return;
                }
                if (success) {
                    this.progress.onSuccess();
                } else {
                    this.progress.onFail();
                }
            }
        });
    }

    /** A failed download or manual stop must never turn into an automatic repeat. */
    @Override
    public boolean completedNormally() {
        return this.legacy || this.completed;
    }

    public boolean hasOwner() {
        return this.ownerAlive.getAsBoolean();
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.ownerAlive.getAsBoolean()) {
            this.onStop();
        }
    }

    @Override
    public synchronized boolean isStopped() {
        return this.stopped || super.isStopped();
    }

    @Override
    public void onStop() {
        RecordStreamLoader.Load current;
        synchronized (this) {
            this.stopped = true;
            current = this.load;
        }
        if (current != null) {
            current.close();
        }
    }
}
