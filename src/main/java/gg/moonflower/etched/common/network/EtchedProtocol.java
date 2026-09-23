package gg.moonflower.etched.common.network;

import gg.moonflower.etched.common.network.play.*;
import gg.moonflower.etched.core.Etched;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.network.NetworkDirection;

/**
 * The protocol boundary for incompatible 5.x development builds.
 */
public final class EtchedProtocol {

    public static final ResourceLocation CHANNEL_NAME =
            ResourceLocation.fromNamespaceAndPath(Etched.MOD_ID, "play");
    public static final String VERSION = "5";
    static final PacketContract<ClientboundInvalidEtchUrlPacket> CLIENTBOUND_INVALID_ETCH_URL =
            new PacketContract<>(0, ClientboundInvalidEtchUrlPacket.class, NetworkDirection.PLAY_TO_CLIENT);
    static final PacketContract<ClientboundPlayEntityMusicPacket> CLIENTBOUND_PLAY_ENTITY_MUSIC =
            new PacketContract<>(1, ClientboundPlayEntityMusicPacket.class, NetworkDirection.PLAY_TO_CLIENT);
    static final PacketContract<ClientboundPlayMusicPacket> CLIENTBOUND_PLAY_MUSIC =
            new PacketContract<>(2, ClientboundPlayMusicPacket.class, NetworkDirection.PLAY_TO_CLIENT);
    static final PacketContract<ClientboundSetUrlPacket> CLIENTBOUND_SET_URL =
            new PacketContract<>(3, ClientboundSetUrlPacket.class, NetworkDirection.PLAY_TO_CLIENT);
    static final PacketContract<ServerboundSetUrlPacket> SERVERBOUND_SET_URL =
            new PacketContract<>(4, ServerboundSetUrlPacket.class, NetworkDirection.PLAY_TO_SERVER);
    static final PacketContract<ServerboundEditMusicLabelPacket> SERVERBOUND_EDIT_MUSIC_LABEL =
            new PacketContract<>(5, ServerboundEditMusicLabelPacket.class, NetworkDirection.PLAY_TO_SERVER);

    private EtchedProtocol() {
    }

    public static boolean accepts(String remoteVersion) {
        return VERSION.equals(remoteVersion);
    }

    public static IExtensionPoint.DisplayTest displayTest() {
        return new IExtensionPoint.DisplayTest(VERSION,
                (remoteVersion, isFromServer) -> accepts(remoteVersion));
    }

    record PacketContract<MSG extends EtchedPacket>(int id, Class<MSG> type, NetworkDirection direction) {
    }
}
