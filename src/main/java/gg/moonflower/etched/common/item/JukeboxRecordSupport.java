package gg.moonflower.etched.common.item;

import gg.moonflower.etched.api.record.PlayableRecord;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.RecordItem;

/** Keeps the original jukebox comparator and playing-event semantics for custom record items. */
public final class JukeboxRecordSupport {

    private JukeboxRecordSupport() {
    }

    public static boolean isCustomRecord(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        Item item = stack.getItem();
        if (item instanceof RecordItem) {
            return false;
        }
        // First-party items no longer rely on the compatibility API for jukebox behavior.
        if (item instanceof EtchedMusicDiscItem || item instanceof AlbumCoverItem) {
            return true;
        }
        // Keep third-party PlayableRecord items working as before.
        return item instanceof PlayableRecord;
    }
}
