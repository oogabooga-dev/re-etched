package gg.moonflower.etched.common.item;

import gg.moonflower.etched.api.record.TrackData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.RecordItem;

/** Legacy metadata view for vanilla discs without relying on the RecordItem API mixin. */
public final class VanillaRecordAdapter {

    private VanillaRecordAdapter() {
    }

    @SuppressWarnings("deprecation") // Vanilla's built-in item registry is needed before Forge registry bootstrap in tests.
    public static boolean isVanilla(RecordItem record) {
        var id = BuiltInRegistries.ITEM.getKey(record);
        return id.getNamespace().equals("minecraft") && BuiltInRegistries.ITEM.get(id) == record;
    }

    public static TrackData[] music(RecordItem record) {
        Component desc = Component.translatable(record.getDescriptionId() + ".desc");
        String[] parts = desc.getString().split("-", 2);
        if (parts.length < 2) {
            return new TrackData[]{new TrackData(record.getSound().getLocation().toString(), "Minecraft", desc)};
        }
        return new TrackData[]{new TrackData(record.getSound().getLocation().toString(), parts[0].trim(),
                Component.literal(parts[1].trim()).withStyle(desc.getStyle()))};
    }
}
