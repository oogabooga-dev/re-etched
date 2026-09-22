package gg.moonflower.etched.api.sound;

import gg.moonflower.etched.api.sound.source.AudioSource;
import gg.moonflower.etched.api.util.DownloadProgressListener;
import gg.moonflower.etched.core.Etched;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;

/**
 * @author Ocelot
 */
public class OnlineRecordSoundInstance extends AbstractOnlineSoundInstance implements TickableSoundInstance {

    private final Owner owner;
    private boolean stopped;

    public OnlineRecordSoundInstance(String url, Entity entity, float volume, int attenuationDistance, DownloadProgressListener progressListener, AudioSource.AudioFileType type) {
        this(url, owner(entity), volume, attenuationDistance, progressListener, type,
                entity == Minecraft.getInstance().player, Etched.CLIENT_CONFIG.forceStereo.get());
    }

    OnlineRecordSoundInstance(String url, Owner owner, float volume, int attenuationDistance,
                              DownloadProgressListener progressListener, AudioSource.AudioFileType type,
                              boolean stereo) {
        this(url, owner, volume, attenuationDistance, progressListener, type, stereo, false);
    }

    private OnlineRecordSoundInstance(String url, Owner owner, float volume, int attenuationDistance,
                                      DownloadProgressListener progressListener, AudioSource.AudioFileType type,
                                      boolean stereo, boolean forceStereo) {
        super(url, null, attenuationDistance, SoundSource.RECORDS, progressListener, type, stereo, forceStereo);
        this.volume = volume;
        this.owner = owner;
    }

    public OnlineRecordSoundInstance(String url, Entity entity, int attenuationDistance, DownloadProgressListener progressListener, AudioSource.AudioFileType type) {
        this(url, entity, 4.0F, attenuationDistance, progressListener, type);
    }

    public OnlineRecordSoundInstance(String url, double x, double y, double z, float volume, int attenuationDistance, DownloadProgressListener progressListener, AudioSource.AudioFileType type) {
        this(url, null, volume, attenuationDistance, progressListener, type,
                Minecraft.getInstance().player == null, Etched.CLIENT_CONFIG.forceStereo.get());
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public OnlineRecordSoundInstance(String url, double x, double y, double z, int attenuationDistance, DownloadProgressListener progressListener, AudioSource.AudioFileType type) {
        this(url, x, y, z, 4.0F, attenuationDistance, progressListener, type);
    }

    @Override
    public void tick() {
        if (this.owner == null) {
            return;
        }

        if (!this.owner.isAlive()) {
            this.stopped = true;
        } else {
            this.x = this.owner.getX();
            this.y = this.owner.getY();
            this.z = this.owner.getZ();
        }
    }

    @Override
    public boolean isStopped() {
        return this.stopped;
    }

    private static Owner owner(Entity entity) {
        if (entity == null) {
            return null;
        }
        return new Owner() {
            @Override
            public boolean isAlive() {
                return entity.isAlive();
            }

            @Override
            public double getX() {
                return entity.getX();
            }

            @Override
            public double getY() {
                return entity.getY();
            }

            @Override
            public double getZ() {
                return entity.getZ();
            }
        };
    }

    interface Owner {

        boolean isAlive();

        double getX();

        double getY();

        double getZ();
    }
}
