package gg.moonflower.etched.common.network.play.handler;

import gg.moonflower.etched.client.screen.EtchingScreen;
import gg.moonflower.etched.client.screen.RadioScreen;
import gg.moonflower.etched.client.radio.JukeboxPlayback;
import gg.moonflower.etched.common.network.play.*;
import gg.moonflower.etched.core.mixin.client.LevelRendererAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.ApiStatus;

import java.util.Map;

@ApiStatus.Internal
public class EtchedClientPlayPacketHandler {

    public static void handlePlayMusicPacket(ClientboundPlayMusicPacket pkt, NetworkEvent.Context ctx) {
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        if (level == null || !level.dimension().equals(pkt.dimension())) {
            return;
        }

        ctx.enqueueWork(() -> {
            if (client.level != level) {
                return;
            }
            boolean expected = JukeboxPlayback.acceptPacket(pkt, JukeboxPlayback.hasRecord(level.getBlockState(pkt.pos())));
            if (!expected) {
                return;
            }
            BlockPos pos = pkt.pos();
            Map<BlockPos, SoundInstance> playingRecords = ((LevelRendererAccessor) client.levelRenderer).getPlayingRecords();
            SoundInstance soundInstance = playingRecords.get(pos);

            // A managed stop owns no native wrapper: a late stop must not close a third-party replacement.
            if (soundInstance != null && !pkt.isStop()) {
                client.getSoundManager().stop(soundInstance);
                playingRecords.remove(pos);
            }

            // Unsupported replacements retire the previous owner, never start a second engine.
            JukeboxPlayback.applyPacket(pkt);
        });
    }

    public static void handleEtchingUrlError(ClientboundEtchingUrlErrorPacket pkt, NetworkEvent.Context ctx) {
        ctx.enqueueWork(() -> {
            if (Minecraft.getInstance().screen instanceof EtchingScreen screen
                    && screen.getMenu().containerId == pkt.containerId()) {
                screen.setReason(pkt.message());
            }
        });
    }

    public static void handleRadioMenuInit(ClientboundRadioMenuInitPacket pkt, NetworkEvent.Context ctx) {
        ctx.enqueueWork(() -> {
            if (Minecraft.getInstance().screen instanceof RadioScreen screen
                    && screen.getMenu().containerId == pkt.containerId()) {
                screen.receiveUrl(pkt.url());
            }
        });
    }

}
