package gg.moonflower.etched.gametest;

import gg.moonflower.etched.api.record.PlayableRecord;
import gg.moonflower.etched.api.record.TrackData;
import gg.moonflower.etched.common.item.AlbumCoverItem;
import gg.moonflower.etched.common.item.BoomboxItem;
import gg.moonflower.etched.common.item.EtchedMusicDiscItem;
import gg.moonflower.etched.common.network.play.ClientboundPlayMusicPacket;
import gg.moonflower.etched.core.Etched;
import gg.moonflower.etched.core.registry.EtchedBlocks;
import gg.moonflower.etched.core.registry.EtchedItems;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@GameTestHolder(Etched.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EtchedGameTests {

    private EtchedGameTests() {
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void forgeRuntimeLoadsModAndCommonMixins(GameTestHelper helper) {
        helper.assertTrue(ModList.get().isLoaded(Etched.MOD_ID), "Etched was not loaded by Forge");
        helper.assertTrue(
                ForgeRegistries.BLOCKS.getValue(EtchedBlocks.RADIO.getId()) == EtchedBlocks.RADIO.get(),
                "Etched blocks were not registered");
        ResourceLocation bardId = ResourceLocation.fromNamespaceAndPath(Etched.MOD_ID, "bard");
        helper.assertFalse(ForgeRegistries.POI_TYPES.containsKey(bardId), "The removed bard POI was registered");
        helper.assertFalse(ForgeRegistries.VILLAGER_PROFESSIONS.containsKey(bardId),
                "The removed bard profession was registered");
        helper.assertTrue(Items.MUSIC_DISC_13 instanceof PlayableRecord, "Etched common mixins were not applied");
        try {
            Files.writeString(Path.of("etched-gametest-success"), "passed\n");
        } catch (IOException exception) {
            throw new IllegalStateException("Could not record the Etched GameTest result", exception);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void albumCoverRoundTripsThroughVanillaJukebox(GameTestHelper helper) {
        BlockPos jukeboxPos = BlockPos.ZERO;
        BlockPos absoluteJukeboxPos = helper.absolutePos(jukeboxPos);
        helper.setBlock(jukeboxPos, Blocks.JUKEBOX);

        ItemStack etchedDisc = new ItemStack(EtchedItems.ETCHED_MUSIC_DISC.get());
        etchedDisc.getOrCreateTag().putString("CharacterizationMarker", "nested-record-data");
        EtchedMusicDiscItem.setMusic(etchedDisc,
                new TrackData("https://audio.example/first.mp3", "Artist", Component.literal("First")),
                new TrackData("https://audio.example/second.mp3", "Artist", Component.literal("Second")));

        ItemStack albumCover = new ItemStack(EtchedItems.ALBUM_COVER.get());
        albumCover.getOrCreateTag().putString("CharacterizationMarker", "album-data");
        AlbumCoverItem.setRecords(albumCover, List.of(etchedDisc));
        ItemStack expectedAlbumCover = albumCover.copy();

        Player player = helper.makeMockSurvivalPlayer();
        player.setItemInHand(InteractionHand.MAIN_HAND, albumCover);
        BlockHitResult hit = new BlockHitResult(
                Vec3.atCenterOf(absoluteJukeboxPos), Direction.UP, absoluteJukeboxPos, false);
        player.getItemInHand(InteractionHand.MAIN_HAND)
                .useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));

        helper.assertTrue(player.getItemInHand(InteractionHand.MAIN_HAND).isEmpty(),
                "Inserting the Album Cover did not consume the held stack");
        helper.assertBlockProperty(jukeboxPos, JukeboxBlock.HAS_RECORD, true);
        JukeboxBlockEntity jukebox = (JukeboxBlockEntity) helper.getBlockEntity(jukeboxPos);
        helper.assertTrue(ItemStack.matches(expectedAlbumCover, jukebox.getFirstItem()),
                "The jukebox did not preserve the inserted Album Cover data");
        helper.assertTrue(helper.getBlockState(jukeboxPos)
                        .getAnalogOutputSignal(helper.getLevel(), absoluteJukeboxPos) == 15,
                "The Album Cover did not produce the custom-record comparator signal");

        helper.useBlock(jukeboxPos, player);

        helper.assertBlockProperty(jukeboxPos, JukeboxBlock.HAS_RECORD, false);
        helper.assertTrue(jukebox.getFirstItem().isEmpty(), "The jukebox retained the ejected Album Cover");
        helper.assertTrue(helper.getBlockState(jukeboxPos)
                        .getAnalogOutputSignal(helper.getLevel(), absoluteJukeboxPos) == 0,
                "The empty jukebox retained a comparator signal");
        List<ItemEntity> ejectedItems = helper.getEntities(EntityType.ITEM, jukeboxPos, 2.0);
        helper.assertTrue(ejectedItems.size() == 1, "The jukebox did not eject exactly one item");
        helper.assertTrue(ItemStack.matches(expectedAlbumCover, ejectedItems.get(0).getItem()),
                "The ejected Album Cover lost item or nested record data");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void vanillaJukeboxPacketPreservesAlbumTrackSequence(GameTestHelper helper) {
        BlockPos jukeboxPos = BlockPos.ZERO;
        BlockPos absoluteJukeboxPos = helper.absolutePos(jukeboxPos);
        helper.setBlock(jukeboxPos, Blocks.JUKEBOX);

        TrackData first = track("first");
        TrackData second = track("second");
        TrackData third = track("third");
        ItemStack multiTrackDisc = new ItemStack(EtchedItems.ETCHED_MUSIC_DISC.get());
        EtchedMusicDiscItem.setMusic(multiTrackDisc, track("album"), first, second);
        ItemStack singleTrackDisc = new ItemStack(EtchedItems.ETCHED_MUSIC_DISC.get());
        EtchedMusicDiscItem.setMusic(singleTrackDisc, third);
        ItemStack albumCover = new ItemStack(EtchedItems.ALBUM_COVER.get());
        AlbumCoverItem.setRecords(albumCover, List.of(multiTrackDisc, singleTrackDisc));

        JukeboxBlockEntity jukebox = (JukeboxBlockEntity) helper.getBlockEntity(jukeboxPos);
        jukebox.setFirstItem(albumCover);
        ClientboundPlayMusicPacket sentPacket =
                new ClientboundPlayMusicPacket(jukebox.getFirstItem().copy(), absoluteJukeboxPos);

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            sentPacket.writePacketData(buffer);
            ClientboundPlayMusicPacket receivedPacket = new ClientboundPlayMusicPacket(buffer);
            TrackData[] tracks = receivedPacket.tracks();

            helper.assertTrue(receivedPacket.pos().equals(absoluteJukeboxPos),
                    "The jukebox playback packet changed its position");
            helper.assertTrue(tracks.length == 3, "The Album Cover packet did not contain every track");
            helper.assertTrue(tracks[0].equals(first), "The first Album Cover track changed order");
            helper.assertTrue(tracks[1].equals(second), "The second Album Cover track changed order");
            helper.assertTrue(tracks[2].equals(third), "The third Album Cover track changed order");
        } finally {
            buffer.release();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void boomboxStackPreservesAlbumTrackSequence(GameTestHelper helper) {
        TrackData first = track("first");
        TrackData second = track("second");
        TrackData third = track("third");
        ItemStack multiTrackDisc = new ItemStack(EtchedItems.ETCHED_MUSIC_DISC.get());
        EtchedMusicDiscItem.setMusic(multiTrackDisc, track("album"), first, second);
        ItemStack singleTrackDisc = new ItemStack(EtchedItems.ETCHED_MUSIC_DISC.get());
        EtchedMusicDiscItem.setMusic(singleTrackDisc, third);
        ItemStack albumCover = new ItemStack(EtchedItems.ALBUM_COVER.get());
        AlbumCoverItem.setRecords(albumCover, List.of(multiTrackDisc, singleTrackDisc));
        ItemStack boombox = new ItemStack(EtchedItems.BOOMBOX.get());
        BoomboxItem.setRecord(boombox, albumCover);

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeItem(boombox);
            ItemStack receivedBoombox = buffer.readItem();
            ItemStack receivedAlbumCover = BoomboxItem.getRecord(receivedBoombox);
            TrackData[] tracks = PlayableRecord.getStackMusic(receivedAlbumCover).orElseGet(() -> new TrackData[0]);

            helper.assertTrue(receivedAlbumCover.is(EtchedItems.ALBUM_COVER.get()),
                    "The synchronized boombox did not retain its Album Cover");
            helper.assertTrue(tracks.length == 3, "The boombox Album Cover did not contain every track");
            helper.assertTrue(tracks[0].equals(first), "The first boombox track changed order");
            helper.assertTrue(tracks[1].equals(second), "The second boombox track changed order");
            helper.assertTrue(tracks[2].equals(third), "The third boombox track changed order");
        } finally {
            buffer.release();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void boomboxPauseAndRecordReplacementSurviveSynchronization(GameTestHelper helper) {
        ItemStack firstRecord = new ItemStack(EtchedItems.ETCHED_MUSIC_DISC.get());
        firstRecord.getOrCreateTag().putString("CharacterizationMarker", "first-record");
        EtchedMusicDiscItem.setMusic(firstRecord, track("first"));
        ItemStack replacementRecord = new ItemStack(EtchedItems.ETCHED_MUSIC_DISC.get());
        replacementRecord.getOrCreateTag().putString("CharacterizationMarker", "replacement-record");
        EtchedMusicDiscItem.setMusic(replacementRecord, track("replacement"));

        ItemStack boombox = new ItemStack(EtchedItems.BOOMBOX.get());
        BoomboxItem.setRecord(boombox, firstRecord);
        BoomboxItem.setPaused(boombox, true);

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeItem(boombox);
            ItemStack synchronizedBoombox = buffer.readItem();

            helper.assertTrue(BoomboxItem.isPaused(synchronizedBoombox),
                    "The synchronized boombox lost its paused state");
            helper.assertTrue(ItemStack.matches(firstRecord, BoomboxItem.getRecord(synchronizedBoombox)),
                    "The synchronized boombox changed its record data");

            BoomboxItem.setRecord(synchronizedBoombox, replacementRecord);
            helper.assertTrue(BoomboxItem.isPaused(synchronizedBoombox),
                    "Replacing the record unpaused the boombox");
            helper.assertTrue(ItemStack.matches(replacementRecord, BoomboxItem.getRecord(synchronizedBoombox)),
                    "The boombox did not replace its record data");

            BoomboxItem.setPaused(synchronizedBoombox, false);
            helper.assertFalse(BoomboxItem.isPaused(synchronizedBoombox),
                    "The boombox remained paused after resuming");
            helper.assertTrue(ItemStack.matches(replacementRecord, BoomboxItem.getRecord(synchronizedBoombox)),
                    "Resuming the boombox changed its replacement record");

            BoomboxItem.setRecord(synchronizedBoombox, ItemStack.EMPTY);
            helper.assertFalse(BoomboxItem.hasRecord(synchronizedBoombox),
                    "The boombox retained its record after removal");
            helper.assertTrue(BoomboxItem.getRecord(synchronizedBoombox).isEmpty(),
                    "The cleared boombox returned a record");
        } finally {
            buffer.release();
        }
        helper.succeed();
    }

    private static TrackData track(String name) {
        return new TrackData("https://audio.example/" + name + ".mp3", "Artist", Component.literal(name));
    }
}
