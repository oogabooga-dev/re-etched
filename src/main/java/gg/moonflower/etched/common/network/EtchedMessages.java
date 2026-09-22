package gg.moonflower.etched.common.network;

import gg.moonflower.etched.common.network.play.*;
import gg.moonflower.etched.core.Etched;
import io.netty.handler.codec.EncoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.function.Function;

public class EtchedMessages {

    public static final SimpleChannel PLAY = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(Etched.MOD_ID, "play"),
            () -> EtchedLegacyProtocol.VERSION,
            EtchedLegacyProtocol.VERSION::equals,
            EtchedLegacyProtocol.VERSION::equals);

    public static synchronized void init() {
        register(EtchedLegacyProtocol.CLIENTBOUND_INVALID_ETCH_URL, ClientboundInvalidEtchUrlPacket::new);
        register(EtchedLegacyProtocol.CLIENTBOUND_PLAY_ENTITY_MUSIC, ClientboundPlayEntityMusicPacket::new);
        register(EtchedLegacyProtocol.CLIENTBOUND_PLAY_MUSIC, ClientboundPlayMusicPacket::new);
        register(EtchedLegacyProtocol.CLIENTBOUND_SET_URL, ClientboundSetUrlPacket::new);
        register(EtchedLegacyProtocol.SERVERBOUND_SET_URL, ServerboundSetUrlPacket::new);
        register(EtchedLegacyProtocol.SERVERBOUND_EDIT_MUSIC_LABEL, ServerboundEditMusicLabelPacket::new);
        register(EtchedLegacyProtocol.SET_ALBUM_JUKEBOX_TRACK, SetAlbumJukeboxTrackPacket::new);
    }

    private static <MSG extends EtchedPacket> void register(EtchedLegacyProtocol.PacketContract<MSG> contract, Function<FriendlyByteBuf, MSG> decoder) {
        PLAY.registerMessage(contract.id(), contract.type(), (msg, friendlyByteBuf) -> {
            try {
                msg.writePacketData(friendlyByteBuf);
            } catch (Exception e) {
                throw new EncoderException(e);
            }
        }, decoder, (msg, ctx) -> {
            NetworkEvent.Context context = ctx.get();
            msg.processPacket(context);
            context.setPacketHandled(true);
        }, Optional.ofNullable(contract.direction()));
    }
}
