package gg.moonflower.etched.common.network;

import gg.moonflower.etched.common.network.play.*;
import io.netty.handler.codec.EncoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.function.Function;

public class EtchedMessages {

    public static final SimpleChannel PLAY = NetworkRegistry.newSimpleChannel(
            EtchedProtocol.CHANNEL_NAME,
            () -> EtchedProtocol.VERSION,
            EtchedProtocol::accepts,
            EtchedProtocol::accepts);

    public static synchronized void init() {
        register(EtchedProtocol.CLIENTBOUND_ETCHING_URL_ERROR, ClientboundEtchingUrlErrorPacket::new);
        register(EtchedProtocol.CLIENTBOUND_PLAY_ENTITY_MUSIC, ClientboundPlayEntityMusicPacket::new);
        register(EtchedProtocol.CLIENTBOUND_PLAY_MUSIC, ClientboundPlayMusicPacket::new);
        register(EtchedProtocol.CLIENTBOUND_RADIO_MENU_INIT, ClientboundRadioMenuInitPacket::new);
        register(EtchedProtocol.SERVERBOUND_SET_ETCHING_URL, ServerboundSetEtchingUrlPacket::new);
        register(EtchedProtocol.SERVERBOUND_EDIT_MUSIC_LABEL, ServerboundEditMusicLabelPacket::new);
        register(EtchedProtocol.SERVERBOUND_SET_RADIO_URL, ServerboundSetRadioUrlPacket::new);
    }

    private static <MSG extends EtchedPacket> void register(EtchedProtocol.PacketContract<MSG> contract, Function<FriendlyByteBuf, MSG> decoder) {
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
        }, Optional.of(contract.direction()));
    }
}
