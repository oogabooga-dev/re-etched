package gg.moonflower.etched.common.network.play;

import gg.moonflower.etched.common.network.play.handler.EtchedClientPlayPacketHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * @param containerId The etching menu container ID
 * @param message     The error to display in the etching table
 * @author Jackson
 */
@ApiStatus.Internal
public record ClientboundEtchingUrlErrorPacket(int containerId, String message) implements EtchedPacket {

    public static final int MAX_MESSAGE_LENGTH = 1_024;

    public ClientboundEtchingUrlErrorPacket {
        MenuPacketFields.requireContainerId(containerId);
        MenuPacketFields.requireBounded(message, MAX_MESSAGE_LENGTH, "message");
    }

    public ClientboundEtchingUrlErrorPacket(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readUtf(MAX_MESSAGE_LENGTH));
    }

    @Override
    public void writePacketData(FriendlyByteBuf buf) {
        buf.writeVarInt(this.containerId);
        buf.writeUtf(this.message, MAX_MESSAGE_LENGTH);
    }

    @Override
    public void processPacket(NetworkEvent.Context ctx) {
        EtchedClientPlayPacketHandler.handleEtchingUrlError(this, ctx);
    }
}
