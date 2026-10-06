package gg.moonflower.etched.common.item;

import net.minecraft.core.BlockPos;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.RecordItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;

/** Keeps the original jukebox comparator and playing-event semantics for custom record items. */
public final class JukeboxRecordSupport {

    private JukeboxRecordSupport() {
    }

    public static boolean isCustomRecord(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        Item item = stack.getItem();
        // Type-only for already stored records, including an empty Album Cover placed by commands.
        return item instanceof EtchedMusicDiscItem || item instanceof AlbumCoverItem;
    }

    /** Vanilla discs and non-native starts use typed state; third-party RecordItems retain native playback. */
    public static boolean requiresPlaybackPacket(Item item) {
        return item != null && item != Items.AIR && (!(item instanceof RecordItem record) || VanillaRecordAdapter.isVanilla(record));
    }

    public static InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockState state = level.getBlockState(pos);
        ItemStack stack = context.getItemInHand();
        if (!state.is(Blocks.JUKEBOX) || state.getValue(JukeboxBlock.HAS_RECORD)
                || !isCustomRecord(stack) || RecordContentResolver.resolve(stack).isEmpty()) {
            return InteractionResult.PASS;
        }

        if (!level.isClientSide()) {
            if (!(level.getBlockEntity(pos) instanceof JukeboxBlockEntity jukebox)) {
                return InteractionResult.PASS;
            }
            Player player = context.getPlayer();
            jukebox.setFirstItem(stack.copyWithCount(1));
            level.gameEvent(GameEvent.BLOCK_CHANGE, pos, GameEvent.Context.of(player, state));
            stack.shrink(1);
            if (player != null) {
                player.awardStat(Stats.PLAY_RECORD);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }
}
