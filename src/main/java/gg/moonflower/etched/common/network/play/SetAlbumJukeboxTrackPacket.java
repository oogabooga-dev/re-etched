package gg.moonflower.etched.common.network.play;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * Compatibility tombstone for the removed Album Jukebox packet. Its ID and codec
 * remain registered for protocol 3 compatibility.
 *
 * @param playingIndex The legacy playing index
 * @param track        The legacy track index
 * @author Ocelot
 */
@ApiStatus.Internal
public record SetAlbumJukeboxTrackPacket(int playingIndex, int track) implements EtchedPacket {

    public SetAlbumJukeboxTrackPacket(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readVarInt());
    }

    @Override
    public void writePacketData(FriendlyByteBuf buf) {
        buf.writeVarInt(this.playingIndex);
        buf.writeVarInt(this.track);
    }

    @Override
    public void processPacket(NetworkEvent.Context ctx) {
    }
}
