package gg.moonflower.etched.core.mixin.client;

import gg.moonflower.etched.client.radio.sound.RecordSoundInstance;
import gg.moonflower.etched.client.radio.AudioPlaybackManager;
import gg.moonflower.etched.client.radio.PlaybackOwnerKey;
import gg.moonflower.etched.client.GuiHook;
import gg.moonflower.etched.client.radio.JukeboxPlayback;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.RecordItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

    @Shadow
    private ClientLevel level;

    @Shadow
    @Final
    private Map<BlockPos, SoundInstance> playingRecords;

    @Shadow
    protected abstract void notifyNearbyEntities(Level level, BlockPos blockPos, boolean bl);

    @Inject(method = "playStreamingMusic(Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/item/RecordItem;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;setNowPlaying(Lnet/minecraft/network/chat/Component;)V", shift = At.Shift.BEFORE))
    public void preNowPlaying(SoundEvent arg, BlockPos arg2, RecordItem musicDiscItem, CallbackInfo ci) {
        if (!this.level.getBlockState(arg2.above()).isAir() || !GuiHook.canShowPlayingText(arg2.getX() + 0.5, arg2.getY() + 0.5, arg2.getZ() + 0.5)) {
            GuiHook.setHidePlayingText(true);
        }
    }

    @Inject(method = "playStreamingMusic(Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/item/RecordItem;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;setNowPlaying(Lnet/minecraft/network/chat/Component;)V", shift = At.Shift.AFTER))
    public void postNowPlaying(SoundEvent arg, BlockPos arg2, RecordItem musicDiscItem, CallbackInfo ci) {
        GuiHook.setHidePlayingText(false);
    }

    @Inject(method = "levelEvent", at = @At("HEAD"), cancellable = true)
    private void etched$ignoreEjectedRecordStart(int event, BlockPos pos, int data, CallbackInfo ci) {
        if (event != 1010 && event != 1011) {
            return;
        }
        boolean hasRecord = JukeboxPlayback.hasRecord(this.level.getBlockState(pos));
        JukeboxPlayback.levelEvent(this.level.dimension(), event, pos, data, hasRecord);
        if (event == 1010 && !hasRecord) {
            ci.cancel();
        }
    }

    @Inject(method = "playStreamingMusic(Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/item/RecordItem;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void etched$playManagedRecord(SoundEvent sound, BlockPos pos, RecordItem disc, CallbackInfo ci) {
        if (sound == null) {
            JukeboxPlayback.stop(pos);
        } else if (disc != null && JukeboxPlayback.start(pos, new ItemStack(disc))) {
            SoundInstance previous = this.playingRecords.remove(pos);
            if (previous != null) {
                net.minecraft.client.Minecraft.getInstance().getSoundManager().stop(previous);
            }
            ci.cancel();
        } else {
            JukeboxPlayback.stop(pos);
        }
    }

    @Redirect(method = "playStreamingMusic(Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/item/RecordItem;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/sounds/SoundManager;play(Lnet/minecraft/client/resources/sounds/SoundInstance;)V", remap = true), remap = false)
    private void etched$playVanillaRecord(SoundManager sounds, SoundInstance soundInstance,
                                         SoundEvent event, BlockPos pos, RecordItem disc) {
        ClientLevel ownerLevel = this.level;
        BlockPos ownerPos = pos.immutable();
        RecordSoundInstance wrapped = RecordSoundInstance.wrap(soundInstance, removed -> {
            if (this.level != ownerLevel) {
                return;
            }
            SoundInstance current = this.playingRecords.get(ownerPos);
            if ((current != null && current != removed)
                    || AudioPlaybackManager.getInstance().getPlaybackState(
                    PlaybackOwnerKey.block(ownerLevel.dimension(), ownerPos)).isPresent()) {
                return;
            }
            this.notifyNearbyEntities(ownerLevel, ownerPos, false);
        });
        // Vanilla already inserted the delegate. The map and engine must share the same wrapper.
        this.playingRecords.put(ownerPos, wrapped);
        sounds.play(wrapped);
    }
}
