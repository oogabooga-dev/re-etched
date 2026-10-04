package gg.moonflower.etched.api.sound;

import gg.moonflower.etched.api.record.TrackData;
import gg.moonflower.etched.api.sound.source.AudioSource;
import gg.moonflower.etched.api.sound.stream.MonoWrapper;
import gg.moonflower.etched.api.util.DownloadProgressListener;
import gg.moonflower.etched.client.sound.EmptyAudioStream;
import gg.moonflower.etched.client.sound.SoundCache;
import gg.moonflower.etched.core.Etched;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.FileNotFoundException;
import java.util.concurrent.CompletableFuture;

/**
 * @author Ocelot
 */
public class AbstractOnlineSoundInstance extends AbstractSoundInstance {

    private static final Logger LOGGER = LogManager.getLogger();

    private final String url;
    private final String subtitle;
    private final int attenuationDistance;
    private final DownloadProgressListener progressListener;
    private final AudioSource.AudioFileType type;
    private final boolean stereo;

    public AbstractOnlineSoundInstance(String url, @Nullable String subtitle, int attenuationDistance, SoundSource source, DownloadProgressListener progressListener, AudioSource.AudioFileType type, boolean stereo) {
        this(url, subtitle, attenuationDistance, source, progressListener, type, stereo,
                Etched.CLIENT_CONFIG.forceStereo.get());
    }

    protected AbstractOnlineSoundInstance(String url, @Nullable String subtitle, int attenuationDistance,
                                          SoundSource source, DownloadProgressListener progressListener,
                                          AudioSource.AudioFileType type, boolean stereo, boolean forceStereo) {
        super(ResourceLocation.fromNamespaceAndPath(Etched.MOD_ID, DigestUtils.sha1Hex(url)), source, SoundInstance.createUnseededRandom());
        this.url = url;
        this.subtitle = subtitle;
        this.attenuationDistance = attenuationDistance;
        this.progressListener = progressListener;
        this.type = type;
        this.stereo = forceStereo || stereo;
    }

    @Override
    public WeighedSoundEvents resolve(SoundManager soundManager) {
        WeighedSoundEvents weighedSoundEvents = new WeighedSoundEvents(this.getLocation(), this.subtitle);
        weighedSoundEvents.addSound(new OnlineSound(this.getLocation(), this.url, this.attenuationDistance, this.progressListener, this.type, this.stereo));
        this.sound = weighedSoundEvents.getSound(this.random);
        return weighedSoundEvents;
    }

    public AbstractOnlineSoundInstance setLoop(boolean loop) {
        this.looping = loop;
        return this;
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary loader, Sound sound, boolean repeatInstantly) {
        if (!(sound instanceof OnlineSound onlineSound)) {
            return super.getStream(loader, sound, repeatInstantly);
        }

        if (TrackData.isLocalSound(onlineSound.getURL())) {
            WeighedSoundEvents weighedSoundEvents = Minecraft.getInstance().getSoundManager().getSoundEvent(ResourceLocation.parse(onlineSound.getURL()));
            if (weighedSoundEvents == null) {
                CompletableFuture<AudioStream> future = new CompletableFuture<>();
                future.completeExceptionally(new FileNotFoundException("Unable to play unknown soundEvent: " + sound.getPath()));
                return future;
            }

            return loader.getStream(weighedSoundEvents.getSound(this.random).getPath(), repeatInstantly).thenApply(MonoWrapper::new).handleAsync((stream, throwable) -> {
                if (throwable != null) {
                    LOGGER.error("Failed to load audio from client: {}", onlineSound.getURL(), throwable);
                    onlineSound.getProgressListener().onFail();
                    return EmptyAudioStream.INSTANCE;
                }
                onlineSound.getProgressListener().onSuccess();
                return stream;
            }, Util.backgroundExecutor());
        }

        return LegacyAudioStreamRequest.start(cancelled -> SoundCache.getAudioStream(onlineSound.getURL(),
                        new LegacyDownloadProgress(onlineSound.getProgressListener(), cancelled),
                        onlineSound.getAudioFileType()), Util.backgroundExecutor(),
                stream -> LegacyAudioDecoder.decode(stream, sound, repeatInstantly,
                        () -> onlineSound.getProgressListener().progressStartLoading()),
                () -> onlineSound.getProgressListener().onSuccess(), throwable -> {
                    LOGGER.error("Failed to load audio from url: {}", onlineSound.getURL(), throwable);
                    onlineSound.getProgressListener().onFail();
                });
    }

    public static class OnlineSound extends Sound implements SoundStreamModifier {

        private final String url;
        private final DownloadProgressListener progressListener;
        private final AudioSource.AudioFileType type;
        private final boolean stereo;

        public OnlineSound(ResourceLocation location, String url, int attenuationDistance, DownloadProgressListener progressListener, AudioSource.AudioFileType type, boolean stereo) {
            super(location.toString(), ConstantFloat.of(1.0F), ConstantFloat.of(1.0F), 1, Type.FILE, true, false, attenuationDistance);
            this.url = url;
            this.progressListener = progressListener;
            this.type = type;
            this.stereo = stereo;
        }

        public String getURL() {
            return this.url;
        }

        public DownloadProgressListener getProgressListener() {
            return this.progressListener;
        }

        public AudioSource.AudioFileType getAudioFileType() {
            return this.type;
        }

        @Override
        public AudioStream modifyStream(AudioStream stream) {
            return this.stereo ? stream : new MonoWrapper(stream);
        }
    }
}
