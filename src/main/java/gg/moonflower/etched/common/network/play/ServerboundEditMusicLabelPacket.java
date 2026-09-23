package gg.moonflower.etched.common.network.play;

import gg.moonflower.etched.common.network.play.handler.EtchedServerPlayPacketHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * @param slot   The slot the music label is in
 * @param author The new author
 * @param title  The new title
 */
@ApiStatus.Internal
public record ServerboundEditMusicLabelPacket(int slot, String author, String title) implements EtchedPacket {

    public static final int MAX_TEXT_LENGTH = 128;

    public ServerboundEditMusicLabelPacket {
        if (!Inventory.isHotbarSlot(slot) && slot != 40) {
            throw new IllegalArgumentException("Invalid music label slot: " + slot);
        }
        MenuPacketFields.requireBounded(author, MAX_TEXT_LENGTH, "author");
        MenuPacketFields.requireBounded(title, MAX_TEXT_LENGTH, "title");
    }

    public ServerboundEditMusicLabelPacket(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readUtf(MAX_TEXT_LENGTH), buf.readUtf(MAX_TEXT_LENGTH));
    }

    @Override
    public void writePacketData(FriendlyByteBuf buf) {
        buf.writeVarInt(this.slot);
        buf.writeUtf(this.author, MAX_TEXT_LENGTH);
        buf.writeUtf(this.title, MAX_TEXT_LENGTH);
    }

    @Override
    public void processPacket(NetworkEvent.Context ctx) {
        EtchedServerPlayPacketHandler.handleEditMusicLabel(this, ctx);
    }
}
