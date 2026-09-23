package gg.moonflower.etched.common.network.play;

import gg.moonflower.etched.common.network.EtchedProtocol;
import gg.moonflower.etched.common.network.play.handler.EtchedServerPlayPacketHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * @param containerId The radio menu container ID
 * @param url         The radio URL to submit, or an empty string to stop
 */
@ApiStatus.Internal
public record ServerboundSetRadioUrlPacket(int containerId, String url) implements EtchedPacket {

    public static final int MAX_URL_LENGTH = EtchedProtocol.MAX_URL_LENGTH;

    public ServerboundSetRadioUrlPacket {
        MenuPacketFields.requireContainerId(containerId);
        MenuPacketFields.requireBounded(url, MAX_URL_LENGTH, "url");
    }

    public ServerboundSetRadioUrlPacket(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readUtf(MAX_URL_LENGTH));
    }

    @Override
    public void writePacketData(FriendlyByteBuf buf) {
        buf.writeVarInt(this.containerId);
        buf.writeUtf(this.url, MAX_URL_LENGTH);
    }

    @Override
    public void processPacket(NetworkEvent.Context ctx) {
        EtchedServerPlayPacketHandler.handleSetRadioUrl(this, ctx);
    }
}
