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
            boolean expected = JukeboxPlayback.acceptPacket(pkt.dimension(), pkt.pos(), pkt.itemId());
            if (!expected || !JukeboxPlayback.hasRecord(level.getBlockState(pkt.pos()))) {
                return;
            }
            BlockPos pos = pkt.pos();
            Map<BlockPos, SoundInstance> playingRecords = ((LevelRendererAccessor) client.levelRenderer).getPlayingRecords();
            SoundInstance soundInstance = playingRecords.get(pos);

            if (soundInstance != null) {
                client.getSoundManager().stop(soundInstance);
                playingRecords.remove(pos);
            }

            // Unsupported replacements retire the previous owner, never start a second engine.
            if (!pkt.program().map(program -> JukeboxPlayback.startProgram(pos, program)).orElse(false)) {
                JukeboxPlayback.stop(pos);
            }
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
