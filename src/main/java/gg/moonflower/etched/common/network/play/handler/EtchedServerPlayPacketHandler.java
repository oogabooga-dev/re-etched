package gg.moonflower.etched.common.network.play.handler;

import gg.moonflower.etched.common.item.SimpleMusicLabelItem;
import gg.moonflower.etched.common.menu.EtchingMenu;
import gg.moonflower.etched.common.menu.RadioMenu;
import gg.moonflower.etched.common.network.play.ServerboundEditMusicLabelPacket;
import gg.moonflower.etched.common.network.play.ServerboundSetUrlPacket;
import gg.moonflower.etched.core.registry.EtchedItems;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public class EtchedServerPlayPacketHandler {

    public static void handleSetUrl(ServerboundSetUrlPacket pkt, NetworkEvent.Context ctx) {
        ServerPlayer player = ctx.getSender();
        if (player == null) {
            return;
        }

        ctx.enqueueWork(() -> {
            if (player.containerMenu instanceof EtchingMenu menu) {
                menu.setUrl(pkt.url());
            } else if (player.containerMenu instanceof RadioMenu menu && menu.stillValid(player)) {
                menu.submitUrl(pkt.url());
            }
        });
    }

    public static void handleEditMusicLabel(ServerboundEditMusicLabelPacket pkt, NetworkEvent.Context ctx) {
        int slot = pkt.slot();
        if (!Inventory.isHotbarSlot(slot) && slot != 40) {
            return;
        }

        ServerPlayer player = ctx.getSender();
        if (player == null) {
            return;
        }

        ItemStack labelStack = player.getInventory().getItem(slot);
        if (!labelStack.is(EtchedItems.MUSIC_LABEL.get())) {
            return;
        }

        ctx.enqueueWork(() -> {
            SimpleMusicLabelItem.setTitle(labelStack, StringUtils.normalizeSpace(pkt.title()));
            SimpleMusicLabelItem.setAuthor(labelStack, StringUtils.normalizeSpace(pkt.author()));
        });
    }

}
