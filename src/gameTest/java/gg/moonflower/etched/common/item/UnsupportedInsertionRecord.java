package gg.moonflower.etched.common.item;

import gg.moonflower.etched.core.Etched;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.RecordItem;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegisterEvent;

import java.util.List;

/** GameTest-only unsupported item: first-party presentation must never call its tooltip. */
@Mod.EventBusSubscriber(modid = Etched.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class UnsupportedInsertionRecord extends Item {

    static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(Etched.MOD_ID, "gametest_unsupported_record");
    static final ResourceLocation RECORD_ID = ResourceLocation.fromNamespaceAndPath(Etched.MOD_ID, "gametest_foreign_disc");

    private UnsupportedInsertionRecord() {
        super(new Item.Properties().stacksTo(1));
    }

    @SubscribeEvent
    public static void register(RegisterEvent event) {
        event.register(ForgeRegistries.Keys.ITEMS, helper -> {
            helper.register(ID, new UnsupportedInsertionRecord());
            helper.register(RECORD_ID, new RecordItem(1, () -> SoundEvents.MUSIC_DISC_CAT, new Item.Properties().stacksTo(1), 185));
        });
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> lines, TooltipFlag flag) {
        throw new AssertionError("Unsupported item reached first-party tooltip dispatch");
    }
}
