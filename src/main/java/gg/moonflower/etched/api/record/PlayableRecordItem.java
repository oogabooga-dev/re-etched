package gg.moonflower.etched.api.record;

import gg.moonflower.etched.client.AlbumCoverCache;
import gg.moonflower.etched.common.audio.provider.AudioProviderPresentation;
import gg.moonflower.etched.core.Etched;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.net.Proxy;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public abstract class PlayableRecordItem extends Item implements PlayableRecord {

    private static final Component ALBUM = Component.translatable("item." + Etched.MOD_ID + ".etched_music_disc.album").withStyle(ChatFormatting.DARK_GRAY);

    public PlayableRecordItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> list, TooltipFlag tooltipFlag) {
        this.getAlbum(stack).ifPresent(track -> {
            boolean album = this.getTrackCount(stack) > 1;
            list.add(track.getDisplayName().copy().withStyle(ChatFormatting.GRAY));
            AudioProviderPresentation.brand(track.url())
                    .map(component -> Component.literal("  ").append(component.copy()))
                    .map(component -> album ? component.append(" ").append(ALBUM) : component)
                    .ifPresentOrElse(list::add, () -> {
                        if (album) {
                            list.add(ALBUM);
                        }
                    });
        });
    }

    @Override
    public CompletableFuture<AlbumCover> getAlbumCover(ItemStack stack, Proxy proxy, ResourceManager resourceManager) {
        return this.getAlbum(stack).map(data -> AlbumCoverCache.requestProviderResource(data.url(), proxy))
                .orElseGet(() -> CompletableFuture.completedFuture(AlbumCover.EMPTY));
    }
}
