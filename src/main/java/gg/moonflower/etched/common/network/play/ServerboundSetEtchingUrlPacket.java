package gg.moonflower.etched.common.network.play;

import gg.moonflower.etched.common.network.EtchedProtocol;
import gg.moonflower.etched.common.network.play.handler.EtchedServerPlayPacketHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * @param containerId The etching menu container ID
 * @param url         The URL to set in the etching table
 * @author Jackson
 */
@ApiStatus.Internal
public record ServerboundSetEtchingUrlPacket(int containerId, String url) implements EtchedPacket {

    public static final int MAX_URL_LENGTH = EtchedProtocol.MAX_URL_LENGTH;

    public ServerboundSetEtchingUrlPacket {
        MenuPacketFields.requireContainerId(containerId);
        MenuPacketFields.requireBounded(url, MAX_URL_LENGTH, "url");
    }

    public ServerboundSetEtchingUrlPacket(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readUtf(MAX_URL_LENGTH));
    }

    @Override
    public void writePacketData(FriendlyByteBuf buf) {
        buf.writeVarInt(this.containerId);
        buf.writeUtf(this.url, MAX_URL_LENGTH);
    }

    @Override
    public void processPacket(NetworkEvent.Context ctx) {
        EtchedServerPlayPacketHandler.handleSetEtchingUrl(this, ctx);
    }
}
