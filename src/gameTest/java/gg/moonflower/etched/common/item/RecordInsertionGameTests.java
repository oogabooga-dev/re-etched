package gg.moonflower.etched.common.item;

import gg.moonflower.etched.common.audio.AudioProgram;
import gg.moonflower.etched.common.audio.AudioTrack;
import gg.moonflower.etched.common.audio.RecordContent;
import gg.moonflower.etched.common.menu.AlbumCoverMenu;
import gg.moonflower.etched.common.menu.BoomboxMenu;
import gg.moonflower.etched.core.Etched;
import gg.moonflower.etched.core.registry.EtchedItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Collections;
import java.util.ArrayList;
import java.util.List;

@GameTestHolder(Etched.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RecordInsertionGameTests {

    private RecordInsertionGameTests() {
    }

    @GameTest(template = "empty")
    public static void menusAndDirectClicksUseTheSameFirstPartyContentRules(GameTestHelper helper) {
        var player = helper.makeMockSurvivalPlayer();
        var albumMenu = new AlbumCoverMenu(1, player.getInventory());
        var boomboxMenu = new BoomboxMenu(2, player.getInventory());
        var slot = new Slot(new SimpleContainer(1), 0, 0, 0);
        var rejected = new ArrayList<>(rejectedRecords());
        rejected.add(foreignDisc());
        for (ItemStack record : rejected) {
            helper.assertFalse(albumMenu.getSlot(0).mayPlace(record), "Album menu accepted unsupported/invalid content");
            helper.assertFalse(boomboxMenu.getSlot(0).mayPlace(record), "Boombox menu accepted unsupported/invalid content");
            helper.assertTrue(RecordContentResolver.resolve(record).isEmpty(), "Rejected content resolved a partial program");
            ItemStack before = record.copy();
            for (ItemStack container : List.of(new ItemStack(EtchedItems.BOOMBOX.get()), new ItemStack(EtchedItems.ALBUM_COVER.get()))) {
                container.getItem().overrideOtherStackedOnMe(container, record, slot, ClickAction.SECONDARY, player, SlotAccess.NULL);
                helper.assertTrue(ItemStack.matches(record, before), "Rejected click consumed/changed the source record");
                helper.assertTrue(container.getTag() == null, "Rejected click wrote container content");
                slot.set(record.copy());
                container.getItem().overrideStackedOnOther(container, slot, ClickAction.SECONDARY, player);
                helper.assertTrue(ItemStack.matches(slot.getItem(), before), "Rejected inverse click consumed/changed the source");
                helper.assertTrue(container.getTag() == null, "Rejected inverse click wrote container content");
            }
        }
        for (ItemStack disc : List.of(disc(2, "https://audio.example/track"), new ItemStack(Items.MUSIC_DISC_CAT))) {
            helper.assertTrue(albumMenu.getSlot(0).mayPlace(disc), "Album menu rejected a first-party disc");
            helper.assertTrue(boomboxMenu.getSlot(0).mayPlace(disc), "Boombox menu rejected a first-party disc");
            ItemStack album = album(List.of(disc));
            helper.assertFalse(albumMenu.getSlot(0).mayPlace(album), "Album menu accepted a nested album");
            helper.assertTrue(boomboxMenu.getSlot(0).mayPlace(album), "Boombox menu rejected a playable album");
            ItemStack boombox = new ItemStack(EtchedItems.BOOMBOX.get());
            ItemStack source = album.copy();
            boombox.getItem().overrideOtherStackedOnMe(boombox, source, slot, ClickAction.SECONDARY, player, SlotAccess.NULL);
            helper.assertTrue(source.isEmpty() && ItemStack.matches(BoomboxItem.getRecord(boombox), album),
                    "Direct boombox click lost/duplicated the accepted album");
            ItemStack cover = new ItemStack(EtchedItems.ALBUM_COVER.get());
            ItemStack discSource = disc.copy();
            cover.getItem().overrideOtherStackedOnMe(cover, discSource, slot, ClickAction.SECONDARY, player, SlotAccess.NULL);
            helper.assertTrue(discSource.isEmpty() && ItemStack.matches(AlbumCoverItem.getRecords(cover).get(0), disc),
                    "Direct album click lost/duplicated the accepted disc");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void albumResolutionRejectsBadMembersAndWholeProgramOverflowWithoutTruncation(GameTestHelper helper) {
        ItemStack first = disc(2, "https://audio.example/first");
        ItemStack second = disc(1, "https://audio.example/last");
        var resolved = RecordContentResolver.resolve(album(List.of(first, second))).orElseThrow();
        helper.assertTrue(resolved.program().tracks().stream().map(AudioTrack::source).toList().equals(List.of(
                "https://audio.example/first", "https://audio.example/first", "https://audio.example/last")),
                "Album aggregation lost sequence order");
        for (ItemStack rejected : rejectedRecords()) {
            helper.assertTrue(RecordContentResolver.resolve(album(List.of(first, rejected, second))).isEmpty(),
                    "Album skipped an unsupported/invalid/nested member");
        }
        helper.assertTrue(RecordContentResolver.resolve(album(List.of(first, foreignDisc(), second))).isEmpty(),
                "Album admitted a third-party RecordItem via the vanilla metadata mixin");
        helper.assertTrue(RecordContentResolver.resolve(album(List.of(first, album(List.of(second))))).isEmpty(),
                "Album recursively admitted another playable album");
        ItemStack hundred = disc(AudioProgram.MAX_TRACKS, "https://audio.example/short");
        helper.assertTrue(RecordContentResolver.resolve(album(List.of(hundred))).orElseThrow().program().tracks().size()
                == AudioProgram.MAX_TRACKS, "Album rejected the exact track-count boundary");
        helper.assertTrue(RecordContentResolver.resolve(album(List.of(hundred, second))).isEmpty(),
                "Album truncated tracks above the total limit");
        ItemStack textHeavy = disc(5, "https://audio.example/" + "x".repeat(7000));
        helper.assertTrue(RecordContentResolver.resolve(textHeavy).isPresent(), "Text-budget fixture exceeds a single disc budget");
        helper.assertTrue(RecordContentResolver.resolve(album(List.of(textHeavy, textHeavy))).isEmpty(),
                "Album exceeded the aggregate text budget");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void jukeboxUseAndAutomationRejectInvalidContentAndInsertOneOwnedRecord(GameTestHelper helper) {
        helper.setBlock(BlockPos.ZERO, Blocks.JUKEBOX);
        var jukebox = (JukeboxBlockEntity) helper.getBlockEntity(BlockPos.ZERO);
        var player = helper.makeMockSurvivalPlayer();
        BlockPos absolute = helper.absolutePos(BlockPos.ZERO);
        var hit = new BlockHitResult(Vec3.atCenterOf(absolute), Direction.UP, absolute, false);
        for (ItemStack record : rejectedRecords()) {
            helper.assertFalse(jukebox.canPlaceItem(0, record), "Jukebox automation accepted invalid content");
            player.setItemInHand(InteractionHand.MAIN_HAND, record);
            helper.assertTrue(record.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit)) == InteractionResult.PASS,
                    "Jukebox use accepted invalid/unsupported content");
            helper.assertTrue(record.getCount() == 1 && jukebox.getFirstItem().isEmpty(), "Rejected record was consumed/stored");
            helper.assertBlockProperty(BlockPos.ZERO, JukeboxBlock.HAS_RECORD, false);
        }
        ItemStack source = album(List.of(disc(2, "https://audio.example/ordered")));
        source.setCount(2);
        ItemStack expected = source.copyWithCount(1);
        helper.assertTrue(jukebox.canPlaceItem(0, source), "Jukebox automation rejected a playable album");
        player.setItemInHand(InteractionHand.MAIN_HAND, source);
        helper.assertTrue(source.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit)).consumesAction(),
                "Jukebox use did not insert a playable album");
        helper.assertTrue(source.getCount() == 1 && ItemStack.matches(jukebox.getFirstItem(), expected),
                "Jukebox failed to consume/store exactly one record");
        helper.assertFalse(jukebox.canPlaceItem(0, source), "Occupied jukebox accepted another record");
        helper.assertTrue(source.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit)) == InteractionResult.PASS
                && source.getCount() == 1, "Occupied jukebox consumed another record");
        source.getOrCreateTag().putString("CallerMutation", "after insertion");
        helper.assertFalse(jukebox.getFirstItem().getTag().contains("CallerMutation"), "Jukebox retained caller-owned NBT");
        helper.succeed();
    }

    private static List<ItemStack> rejectedRecords() {
        ItemStack invalid = disc(1, "https://audio.example/invalid");
        invalid.getTag().getCompound(EtchedMusicDiscItem.CONTENT_TAG).getCompound("Program")
                .getList("Tracks", Tag.TAG_COMPOUND).getCompound(0).putString("SourceType", "invalid");
        ItemStack legacy = new ItemStack(EtchedItems.ETCHED_MUSIC_DISC.get());
        legacy.getOrCreateTag().put("Music", new CompoundTag());
        ItemStack unsupported = new ItemStack(ForgeRegistries.ITEMS.getValue(UnsupportedInsertionRecord.ID));
        return List.of(new ItemStack(Items.PAPER), unsupported, legacy, invalid,
                new ItemStack(EtchedItems.ALBUM_COVER.get()), album(List.of(invalid)), album(List.of(unsupported)));
    }

    private static ItemStack disc(int count, String source) {
        ItemStack stack = new ItemStack(EtchedItems.ETCHED_MUSIC_DISC.get());
        EtchedMusicDiscItem.setContent(stack, new RecordContent(new AudioProgram(AudioProgram.Kind.FINITE,
                Collections.nCopies(count, new AudioTrack(AudioTrack.SourceType.REMOTE, source, "Artist", "Title")))));
        return stack;
    }

    private static ItemStack foreignDisc() {
        return new ItemStack(ForgeRegistries.ITEMS.getValue(UnsupportedInsertionRecord.RECORD_ID));
    }

    private static ItemStack album(List<ItemStack> records) {
        ItemStack stack = new ItemStack(EtchedItems.ALBUM_COVER.get());
        AlbumCoverItem.setRecords(stack, records);
        return stack;
    }
}
