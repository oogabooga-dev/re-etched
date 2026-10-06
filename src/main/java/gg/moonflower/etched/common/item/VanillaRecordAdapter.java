package gg.moonflower.etched.common.item;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.RecordItem;

/** First-party vanilla disc admission without modifying RecordItem's type or metadata. */
public final class VanillaRecordAdapter {

    private VanillaRecordAdapter() {
    }

    @SuppressWarnings("deprecation") // Vanilla's built-in item registry is needed before Forge registry bootstrap in tests.
    public static boolean isVanilla(RecordItem record) {
        var id = BuiltInRegistries.ITEM.getKey(record);
        return id.getNamespace().equals("minecraft") && BuiltInRegistries.ITEM.get(id) == record;
    }

}
