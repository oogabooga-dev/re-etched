package gg.moonflower.etched.client.radio;

import gg.moonflower.etched.api.record.PlayableRecord;
import gg.moonflower.etched.core.mixin.client.GuiAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Keeps radio overlays and nearby-record state aligned with actual playback. */
final class MinecraftRadioPlaybackEffects implements PlaybackEffects {

    private final Map<PlaybackOwnerKey.BlockOwner, ActiveEffect> active = new HashMap<>();

    @Override
    public void update(PlaybackOwnerKey key, PlaybackSession.Snapshot snapshot) {
        if (key instanceof PlaybackOwnerKey.BlockOwner blockOwner) {
            this.updateBlock(blockOwner, snapshot);
        }
    }

    private void updateBlock(PlaybackOwnerKey.BlockOwner key, PlaybackSession.Snapshot snapshot) {
        Component message = RadioStatusMessages.forSnapshot(snapshot);
        if (message == null) {
            this.stopBlock(key);
            return;
        }
        ClientLevel level = getLevel(key);
        if (level == null) {
            this.stopBlock(key);
            return;
        }

        ActiveEffect effect = this.active.computeIfAbsent(key, ignored -> new ActiveEffect());
        boolean playing = snapshot.state() == RadioPlaybackState.PLAYING;
        if (effect.playing && !playing) {
            effect.playing = false;
            this.setRecordPlayingNearby(level, key, false);
            this.refreshActiveNearbyState();
        }
        RadioFailure.Code failureCode = snapshot.failure() == null ? null : snapshot.failure().code();
        boolean messageChanged = effect.state != snapshot.state()
                || snapshot.state() == RadioPlaybackState.PLAYING
                && !Objects.equals(effect.streamTitle, snapshot.streamTitle())
                || snapshot.state() == RadioPlaybackState.FAILED
                && effect.failureCode != failureCode;
        if (messageChanged) {
            this.clearOverlay(effect);
            effect.state = snapshot.state();
            effect.failureCode = failureCode;
            effect.streamTitle = snapshot.streamTitle();
        }
        if (effect.overlay == null) {
            effect.overlay = this.showOverlay(key, message, playing);
        }
        effect.playing = playing;
        if (playing) {
            this.setRecordPlayingNearby(level, key, true);
        }
    }

    @Override
    public void stop(PlaybackOwnerKey key) {
        if (key instanceof PlaybackOwnerKey.BlockOwner blockOwner) {
            this.stopBlock(blockOwner);
        }
    }

    private void stopBlock(PlaybackOwnerKey.BlockOwner key) {
        ActiveEffect effect = this.active.remove(key);
        if (effect == null) {
            return;
        }

        ClientLevel level = getLevel(key);
        if (level != null && effect.playing) {
            this.setRecordPlayingNearby(level, key, false);
        }
        this.clearOverlay(effect);
        this.refreshActiveNearbyState();
    }

    @Nullable
    private Component showOverlay(PlaybackOwnerKey.BlockOwner key, Component message, boolean playing) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = getLevel(key);
        if (level == null || playing && !level.getBlockState(key.pos().above()).isAir()
                || !PlayableRecord.canShowMessage(
                key.pos().getX() + 0.5, key.pos().getY() + 0.5, key.pos().getZ() + 0.5)) {
            return null;
        }

        minecraft.gui.setOverlayMessage(message, true);
        return message;
    }

    private void refreshActiveNearbyState() {
        for (Map.Entry<PlaybackOwnerKey.BlockOwner, ActiveEffect> entry : this.active.entrySet()) {
            if (!entry.getValue().playing) {
                continue;
            }
            PlaybackOwnerKey.BlockOwner activeKey = entry.getKey();
            ClientLevel level = getLevel(activeKey);
            if (level == null) {
                continue;
            }
            this.setRecordPlayingNearby(level, activeKey, true);
        }
    }

    private void setRecordPlayingNearby(ClientLevel level, PlaybackOwnerKey.BlockOwner key, boolean playing) {
        for (LivingEntity living : level.getEntitiesOfClass(
                LivingEntity.class, new AABB(key.pos()).inflate(3.45))) {
            living.setRecordPlayingNearby(key.pos(), playing);
        }
    }

    private void clearOverlay(ActiveEffect effect) {
        if (effect.overlay == null) {
            return;
        }
        GuiAccessor gui = (GuiAccessor) Minecraft.getInstance().gui;
        if (gui.getOverlayMessageString() == effect.overlay) {
            gui.setOverlayMessageTime(0);
        }
        effect.overlay = null;
    }

    @Nullable
    private static ClientLevel getLevel(PlaybackOwnerKey.BlockOwner key) {
        ClientLevel level = Minecraft.getInstance().level;
        return level != null && level.dimension().equals(key.dimension()) ? level : null;
    }

    private static final class ActiveEffect {

        private boolean playing;
        private RadioPlaybackState state;
        private RadioFailure.Code failureCode;
        private String streamTitle;
        private Component overlay;
    }
}
