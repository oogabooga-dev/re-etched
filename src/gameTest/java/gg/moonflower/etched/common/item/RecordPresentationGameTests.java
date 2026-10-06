package gg.moonflower.etched.common.item;

import gg.moonflower.etched.api.record.TrackData;
import gg.moonflower.etched.common.audio.AudioNbtCodec;
import gg.moonflower.etched.common.audio.AudioProgram;
import gg.moonflower.etched.common.audio.AudioTrack;
import gg.moonflower.etched.common.audio.RecordContent;
import gg.moonflower.etched.common.menu.AlbumCoverMenu;
import gg.moonflower.etched.core.Etched;
import gg.moonflower.etched.core.registry.EtchedItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

@GameTestHolder(Etched.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RecordPresentationGameTests {

    private RecordPresentationGameTests() {
    }

    @GameTest(template = "empty")
    public static void versionedDiscsRoundTripAndLegacyOrInvalidDiscsAreNotInsertionSources(GameTestHelper helper) {
        var descriptor = new TrackData("minecraft:music_disc.blocks", "Minecraft", Component.literal("Blocks"));
        ItemStack valid = new ItemStack(EtchedItems.ETCHED_MUSIC_DISC.get());
        EtchedMusicDiscItem.setContent(valid, content(null, descriptor));
        ItemStack restored = ItemStack.of(valid.save(new CompoundTag()));
        helper.assertTrue(AlbumCoverMenu.isValid(restored), "Versioned disc was not accepted for album insertion");
        helper.assertTrue(RecordContentResolver.resolve(restored).orElseThrow().program().tracks().get(0).source()
                .equals(descriptor.url()), "Versioned disc lost its local source during stack persistence");
        helper.assertTrue(restored.getTag().getCompound(EtchedMusicDiscItem.CONTENT_TAG).getInt("SchemaVersion")
                == AudioNbtCodec.SCHEMA_VERSION, "Stack persistence lost the audio version");

        ItemStack old = new ItemStack(EtchedItems.ETCHED_MUSIC_DISC.get());
        CompoundTag legacy = new CompoundTag();
        legacy.putString("Url", descriptor.url());
        legacy.putString("Author", descriptor.artist());
        legacy.putString("Title", Component.Serializer.toJson(descriptor.title()));
        old.getOrCreateTag().put("Music", legacy);
        ItemStack invalid = valid.copy();
        invalid.getTag().getCompound(EtchedMusicDiscItem.CONTENT_TAG).getCompound("Program")
                .getList("Tracks", Tag.TAG_COMPOUND).getCompound(0).putString("SourceType", "invalid");
        helper.setBlock(BlockPos.ZERO, Blocks.JUKEBOX);
        var player = helper.makeMockSurvivalPlayer();
        BlockPos absolute = helper.absolutePos(BlockPos.ZERO);
        var hit = new BlockHitResult(Vec3.atCenterOf(absolute), Direction.UP, absolute, false);
        for (ItemStack disc : List.of(old, invalid)) {
            helper.assertFalse(AlbumCoverMenu.isValid(disc), "Unversioned/invalid disc was accepted for album insertion");
            helper.assertTrue(RecordContentResolver.resolve(disc).isEmpty(), "Unversioned/invalid disc became managed content");
            player.setItemInHand(InteractionHand.MAIN_HAND, disc);
            helper.assertTrue(disc.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit)) == InteractionResult.PASS,
                    "Unversioned/invalid disc was inserted into the jukebox");
            helper.assertBlockProperty(BlockPos.ZERO, JukeboxBlock.HAS_RECORD, false);
            helper.assertTrue(disc.getCount() == 1, "Rejected disc was consumed");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void builtInAndUnbrandedRecordTooltipsRetainAlbumPresentationWithoutProviderRegistration(GameTestHelper helper) {
        for (String source : List.of("https://artist.bandcamp.com/album/test", "https://soundcloud.com/artist/sets/test",
                "https://audio.example/track.mp3", "minecraft:music_disc.blocks")) {
            for (int albumTracks : new int[]{0, 1, 2}) {
                boolean album = albumTracks > 0;
                ItemStack stack = new ItemStack(EtchedItems.ETCHED_MUSIC_DISC.get());
                var descriptor = new TrackData(source, "Artist", Component.literal("Title"));
                if (album) {
                    EtchedMusicDiscItem.setContent(stack, content(descriptor, albumTracks == 1
                            ? new TrackData[]{descriptor.withTitle(Component.literal("One"))}
                            : new TrackData[]{descriptor.withTitle(Component.literal("One")), descriptor.withTitle(Component.literal("Two"))}));
                } else {
                    EtchedMusicDiscItem.setContent(stack, content(null, descriptor));
                }
                var tooltip = new ArrayList<Component>();
                stack.getItem().appendHoverText(stack, helper.getLevel(), tooltip, TooltipFlag.Default.NORMAL);
                helper.assertTrue(tooltip.get(0).getString().equals(descriptor.getDisplayName().getString()),
                        "Tooltip lost the artist/title");
                boolean branded = source.contains("bandcamp.com") || source.contains("soundcloud.com");
                helper.assertTrue(tooltip.size() == (album || branded ? 2 : 1), "Tooltip has missing or extra lines");
                if (branded) {
                    var brand = tooltip.get(1).getSiblings().get(0);
                    String key = source.contains("bandcamp.com") ? "bandcamp" : "sound_cloud";
                    helper.assertTrue(brand.getContents() instanceof TranslatableContents text
                            && text.getKey().equals("sound_source.etched." + key), "Tooltip lost the built-in brand");
                    helper.assertTrue(brand.getStyle().getColor().getValue() == (key.equals("bandcamp") ? 0x477987 : 0xFF5500),
                            "Tooltip lost the service brand color");
                }
                if (album) {
                    var line = tooltip.get(1);
                    var albumText = branded ? line.getSiblings().get(line.getSiblings().size() - 1) : line;
                    helper.assertTrue(albumText.getContents() instanceof TranslatableContents text
                            && text.getKey().equals("item.etched.etched_music_disc.album"), "Tooltip lost the album marker");
                }
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void storedUnsupportedRecordsNeverReachLegacyTooltipMetadata(GameTestHelper helper) {
        ItemStack unsupported = new ItemStack(ForgeRegistries.ITEMS.getValue(UnsupportedInsertionRecord.ID));
        ItemStack foreign = new ItemStack(ForgeRegistries.ITEMS.getValue(UnsupportedInsertionRecord.RECORD_ID));
        ItemStack invalid = new ItemStack(EtchedItems.ETCHED_MUSIC_DISC.get());
        invalid.getOrCreateTag().put("Music", new CompoundTag());
        for (ItemStack record : List.of(unsupported, foreign, invalid)) {
            ItemStack album = new ItemStack(EtchedItems.ALBUM_COVER.get());
            AlbumCoverItem.setRecords(album, List.of(record));
            var lines = new ArrayList<Component>();
            album.getItem().appendHoverText(album, helper.getLevel(), lines, TooltipFlag.Default.NORMAL);
            helper.assertTrue(lines.isEmpty(), "Album tooltip dispatched unsupported/invalid metadata");
            ItemStack boombox = new ItemStack(EtchedItems.BOOMBOX.get());
            BoomboxItem.setRecord(boombox, record);
            boombox.getItem().appendHoverText(boombox, helper.getLevel(), lines, TooltipFlag.Default.NORMAL);
            helper.assertTrue(lines.size() == 1 && lines.get(0).getContents() instanceof TranslatableContents hint
                    && hint.getKey().equals("item.etched.boombox.pause"), "Boombox tooltip published unsupported record metadata");
        }
        ItemStack valid = new ItemStack(EtchedItems.ETCHED_MUSIC_DISC.get());
        EtchedMusicDiscItem.setContent(valid, content(null, new TrackData("minecraft:music_disc.cat", "Artist", Component.literal("Title"))));
        ItemStack album = new ItemStack(EtchedItems.ALBUM_COVER.get());
        AlbumCoverItem.setRecords(album, List.of(unsupported, valid, new ItemStack(Items.MUSIC_DISC_CAT)));
        var lines = new ArrayList<Component>();
        album.getItem().appendHoverText(album, helper.getLevel(), lines, TooltipFlag.Default.NORMAL);
        helper.assertTrue(lines.size() == 2 && lines.get(0).equals(RecordPresentation.tooltip(
                        EtchedMusicDiscItem.readContent(valid).orElseThrow()).get(0)),
                "Album inventory tooltip lost supported entries next to an unsupported stored item");
        var vanilla = new ArrayList<Component>();
        Items.MUSIC_DISC_CAT.appendHoverText(new ItemStack(Items.MUSIC_DISC_CAT), helper.getLevel(), vanilla, TooltipFlag.Default.NORMAL);
        helper.assertTrue(lines.get(1).equals(vanilla.get(0)), "Album inventory tooltip changed vanilla presentation");
        helper.succeed();
    }

    private static RecordContent content(TrackData album, TrackData... tracks) {
        var program = new AudioProgram(AudioProgram.Kind.FINITE, java.util.Arrays.stream(tracks)
                .map(track -> new AudioTrack(TrackData.isLocalSound(track.url()) ? AudioTrack.SourceType.SOUND_EVENT
                        : AudioTrack.SourceType.REMOTE, track.url(), track.artist(), track.title().getString())).toList());
        return new RecordContent(program, java.util.Optional.ofNullable(album).map(data -> new RecordContent.AlbumMetadata(
                TrackData.isLocalSound(data.url()) ? AudioTrack.SourceType.SOUND_EVENT : AudioTrack.SourceType.REMOTE,
                data.url(), data.artist(), data.title().getString())));
    }
}
