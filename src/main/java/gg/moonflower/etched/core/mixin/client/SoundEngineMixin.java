package gg.moonflower.etched.core.mixin.client;

import gg.moonflower.etched.client.radio.sound.PlaybackStopListener;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.util.Iterator;
import java.util.Map;

@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {

    @Shadow
    @Final
    private Map<SoundInstance, ChannelAccess.ChannelHandle> instanceToChannel;

    @Inject(method = "stopAll", at = @At("HEAD"))
    private void onAllSoundsStopped(CallbackInfo ci) {
        for (SoundInstance soundInstance : this.instanceToChannel.keySet()) {
            if (soundInstance instanceof PlaybackStopListener listener) {
                listener.onStop();
            }
        }
    }

    @Inject(method = "tickNonPaused", at = @At(value = "INVOKE", target = "Ljava/util/Map;remove(Ljava/lang/Object;)Ljava/lang/Object;", shift = At.Shift.AFTER), locals = LocalCapture.CAPTURE_FAILHARD)
    public void onSoundRemoved(CallbackInfo ci, Iterator<?> iterator, Map.Entry<?, ?> entry, ChannelAccess.ChannelHandle channelHandle2, SoundInstance soundInstance) {
        if (soundInstance instanceof PlaybackStopListener listener) {
            listener.onStop();
        }
    }
}
