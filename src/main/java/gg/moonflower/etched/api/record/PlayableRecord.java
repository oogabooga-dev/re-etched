package gg.moonflower.etched.api.record;

import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.net.Proxy;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Denotes an item as having the capability of being played as a record item.
 *
 * @author Ocelot
 * @since 2.0.0
 */
public interface PlayableRecord {

    /**
     * Checks to see if the specified stack can be played.
     *
     * @param stack The stack to check
     * @return Whether that stack can play
     */
    static boolean isPlayableRecord(ItemStack stack) {
        return stack.getItem() instanceof PlayableRecord && ((PlayableRecord) stack.getItem()).canPlay(stack);
    }

    /**
     * Retrieves the music for the specified stack.
     *
     * @param stack The stack to check
     * @return The tracks on that record
     */
    static Optional<TrackData[]> getStackMusic(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof PlayableRecord record)) {
            return Optional.empty();
        }
        return record.getMusic(stack);
    }

    /**
     * Retrieves the album music for the specified stack.
     *
     * @param stack The stack to check
     * @return The album track on that record
     */
    static Optional<TrackData> getStackAlbum(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof PlayableRecord record)) {
            return Optional.empty();
        }
        return record.getAlbum(stack);
    }

    /**
     * Retrieves the number of tracks on the specified stack.
     *
     * @param stack The stack to check
     * @return The number of tracks on the record
     */
    static int getStackTrackCount(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof PlayableRecord record)) {
            return 0;
        }
        return record.getTrackCount(stack);
    }

    /**
     * Checks to see if this item can be played.
     *
     * @param stack The stack to check
     * @return Whether it can play
     */
    default boolean canPlay(ItemStack stack) {
        return this.getMusic(stack).isPresent();
    }

    /**
     * Retrieves the album cover for this item.
     *
     * @param stack The stack to get art for
     * @return A future for a potential cover
     */
    @OnlyIn(Dist.CLIENT)
    CompletableFuture<AlbumCover> getAlbumCover(ItemStack stack, Proxy proxy, ResourceManager resourceManager);

    /**
     * Retrieves the music URL from the specified stack.
     *
     * @param stack The stack to get NBT from
     * @return The optional URL for that item
     */
    Optional<TrackData[]> getMusic(ItemStack stack);

    /**
     * Retrieves the album data from the specified stack.
     *
     * @param stack The stack to get the album for
     * @return The album data or the first track if not an album
     */
    Optional<TrackData> getAlbum(ItemStack stack);

    /**
     * Retrieves the number of tracks in the specified stack.
     *
     * @param stack The stack to get tracks for
     * @return The number of tracks
     */
    int getTrackCount(ItemStack stack);
}
