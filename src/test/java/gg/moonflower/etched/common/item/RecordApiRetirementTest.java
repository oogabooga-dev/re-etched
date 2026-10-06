package gg.moonflower.etched.common.item;

import net.minecraft.world.item.Item;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RecordApiRetirementTest {

    @Test
    void legacyRecordApiMixinAndCoverCacheClassesAreAbsentRatherThanForwarded() {
        for (String type : new String[]{"gg.moonflower.etched.api.record.AlbumCover",
                "gg.moonflower.etched.api.record.PlayableRecord", "gg.moonflower.etched.api.record.PlayableRecordItem",
                "gg.moonflower.etched.api.record.TrackData", "gg.moonflower.etched.core.mixin.RecordItemMixin",
                "gg.moonflower.etched.client.AlbumCoverCache"}) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(type, false, getClass().getClassLoader()), type);
        }
    }

    @Test
    void firstPartyItemsExtendItemDirectlyAndHaveNoLegacyProjectionMethods() {
        for (Class<?> type : new Class<?>[]{EtchedMusicDiscItem.class, AlbumCoverItem.class}) {
            assertSame(Item.class, type.getSuperclass());
            for (var method : type.getDeclaredMethods()) {
                assertFalse(java.util.Set.of("getMusic", "readMusic", "getAlbum", "readAlbum", "getTrackCount", "countTracks",
                        "getAlbumCover", "flattenMusic", "flattenPrograms", "setMusic").contains(method.getName()), method.toString());
            }
        }
    }
}
