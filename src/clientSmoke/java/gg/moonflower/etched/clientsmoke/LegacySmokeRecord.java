package gg.moonflower.etched.clientsmoke;

import gg.moonflower.etched.api.record.AlbumCover;
import gg.moonflower.etched.api.record.PlayableRecord;
import gg.moonflower.etched.api.record.TrackData;
import gg.moonflower.etched.core.Etched;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegisterEvent;

import java.net.Proxy;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Registered only in the transformed client smoke; never included in the release JAR. */
@Mod.EventBusSubscriber(modid = Etched.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class LegacySmokeRecord extends Item implements PlayableRecord {

    static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(Etched.MOD_ID, "client_smoke_legacy_record");
    private static final TrackData TRACK = new TrackData("minecraft:music_disc.cat", "Smoke",
            Component.literal("Unsupported legacy record"));

    private LegacySmokeRecord(Properties properties) {
        super(properties);
    }

    @SubscribeEvent
    public static void register(RegisterEvent event) {
        event.register(ForgeRegistries.Keys.ITEMS, helper ->
                helper.register(ID, new LegacySmokeRecord(new Item.Properties().stacksTo(1))));
    }

    @Override
    public CompletableFuture<AlbumCover> getAlbumCover(ItemStack stack, Proxy proxy, ResourceManager resources) {
        throw new AssertionError("Unsupported legacy record reached getAlbumCover");
    }

    @Override
    public Optional<TrackData[]> getMusic(ItemStack stack) {
        throw new AssertionError("Unsupported legacy record reached getMusic");
    }

    @Override
    public Optional<TrackData> getAlbum(ItemStack stack) {
        return Optional.of(TRACK);
    }

    @Override
    public int getTrackCount(ItemStack stack) {
        return 1;
    }
}
