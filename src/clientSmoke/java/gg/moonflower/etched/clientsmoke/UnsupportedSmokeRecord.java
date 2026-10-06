package gg.moonflower.etched.clientsmoke;

import gg.moonflower.etched.core.Etched;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegisterEvent;

import java.util.List;

/** Registered only in the transformed client smoke; never included in the release JAR. */
@Mod.EventBusSubscriber(modid = Etched.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class UnsupportedSmokeRecord extends Item {

    static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(Etched.MOD_ID, "client_smoke_unsupported_record");

    private UnsupportedSmokeRecord(Properties properties) {
        super(properties);
    }

    @SubscribeEvent
    public static void register(RegisterEvent event) {
        event.register(ForgeRegistries.Keys.ITEMS, helper ->
                helper.register(ID, new UnsupportedSmokeRecord(new Item.Properties().stacksTo(1))));
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> lines, TooltipFlag flag) {
        throw new AssertionError("Unsupported smoke record reached first-party tooltip dispatch");
    }
}
