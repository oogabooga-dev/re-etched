package gg.moonflower.etched.common.audio;

import gg.moonflower.etched.common.item.AlbumCoverItem;
import gg.moonflower.etched.common.item.EtchedMusicDiscItem;
import gg.moonflower.etched.core.Etched;
import gg.moonflower.etched.core.registry.EtchedItems;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

@GameTestHolder(Etched.MOD_ID)
@PrefixGameTestTemplate(false)
public final class JukeboxSnapshotGameTests {

    private JukeboxSnapshotGameTests() {
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void playingAlbumSnapshotsPreserveProgramAndServerDuration(GameTestHelper helper) {
        var jukebox = place(helper);
        var custom = new ItemStack(EtchedItems.ETCHED_MUSIC_DISC.get());
        var track = new AudioTrack(AudioTrack.SourceType.REMOTE, "https://audio.example/album.mp3", "Artist", "Track");
        EtchedMusicDiscItem.setContent(custom, new RecordContent(new AudioProgram(AudioProgram.Kind.FINITE, List.of(track))));
        var album = new ItemStack(EtchedItems.ALBUM_COVER.get());
        AlbumCoverItem.setRecords(album, List.of(new ItemStack(Items.MUSIC_DISC_CAT), custom));
        jukebox.setFirstItem(album);
        var saved = jukebox.saveWithoutMetadata();
        var clock = ServerPlaybackClock.get(helper.getLevel());
        long before = clock.current();
        var first = JukeboxServerPlayback.snapshot(jukebox).orElseThrow();
        var second = JukeboxServerPlayback.snapshot(jukebox).orElseThrow();
        helper.assertTrue(first.revision() == before + 1L && second.revision() == before + 2L,
                "Directed snapshots did not allocate fresh shared server revisions");
        helper.assertTrue(first.program().equals(second.program()) && first.program().orElseThrow().tracks().size() == 2
                        && first.program().orElseThrow().tracks().get(1).equals(track),
                "Snapshot lost the whole ordered mixed album");
        helper.assertTrue(first.pos().equals(jukebox.getBlockPos()) && first.dimension().equals(helper.getLevel().dimension()),
                "Snapshot changed the block owner identity");
        helper.assertTrue(saved.equals(jukebox.saveWithoutMetadata()), "Snapshot restarted or mutated the server jukebox");
        jukebox.removeFirstItem();
        helper.assertTrue(clock.current() == before + 3L, "Stop did not supersede all directed snapshot revisions");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void attachedDiskRestoredJukeboxCanSnapshotWithoutServerRestart(GameTestHelper helper) {
        var jukebox = place(helper);
        jukebox.setFirstItem(new ItemStack(Items.MUSIC_DISC_CAT));
        var saved = jukebox.saveWithoutMetadata();
        jukebox.removeFirstItem();
        var state = Blocks.JUKEBOX.defaultBlockState().setValue(JukeboxBlock.HAS_RECORD, true);
        helper.getLevel().setBlockAndUpdate(jukebox.getBlockPos(), state);
        var restored = new JukeboxBlockEntity(jukebox.getBlockPos(), state);
        restored.load(saved);
        helper.getLevel().setBlockEntity(restored);
        var before = restored.saveWithoutMetadata();
        var clock = ServerPlaybackClock.get(helper.getLevel());
        long stoppedRevision = clock.current();
        var snapshot = JukeboxServerPlayback.snapshot(restored).orElseThrow();
        helper.assertTrue(snapshot.revision() == stoppedRevision + 1L
                        && snapshot.program().orElseThrow().tracks().get(0).source().equals("minecraft:music_disc.cat"),
                "Restored owner did not get a fresh authoritative vanilla program");
        helper.assertTrue(before.equals(restored.saveWithoutMetadata()), "Restore snapshot reset the saved playback duration");
        helper.assertTrue(JukeboxServerPlayback.snapshot(jukebox).isEmpty(), "Replaced block entity published a stale snapshot");
        restored.removeFirstItem();
        helper.assertTrue(clock.current() == stoppedRevision + 2L, "Restored stop did not supersede its snapshot");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void emptyStoppedInvalidAndMissingRecordSnapshotsDoNotAllocate(GameTestHelper helper) {
        var jukebox = place(helper);
        var clock = ServerPlaybackClock.get(helper.getLevel());
        long before = clock.current();
        helper.assertTrue(JukeboxServerPlayback.snapshot(jukebox).isEmpty() && clock.current() == before,
                "Empty jukebox allocated a snapshot");
        jukebox.setFirstItem(new ItemStack(Items.MUSIC_DISC_CAT));
        var saved = jukebox.saveWithoutMetadata();
        saved.putBoolean("IsPlaying", false);
        jukebox.load(saved);
        before = clock.current();
        helper.assertTrue(!jukebox.isRecordPlaying() && !jukebox.getFirstItem().isEmpty(), "Stopped disc fixture was not retained");
        helper.assertTrue(JukeboxServerPlayback.snapshot(jukebox).isEmpty() && clock.current() == before,
                "Stopped retained disc was auto-started by snapshot");
        jukebox.removeFirstItem();
        jukebox.setFirstItem(new ItemStack(EtchedItems.ALBUM_COVER.get()));
        before = clock.current();
        helper.assertTrue(JukeboxServerPlayback.snapshot(jukebox).isEmpty() && clock.current() == before,
                "Invalid empty album allocated a snapshot ticket");
        jukebox.removeFirstItem();
        jukebox.setFirstItem(new ItemStack(Items.MUSIC_DISC_CAT));
        helper.getLevel().setBlockAndUpdate(jukebox.getBlockPos(),
                Blocks.JUKEBOX.defaultBlockState().setValue(JukeboxBlock.HAS_RECORD, false));
        before = clock.current();
        helper.assertTrue(JukeboxServerPlayback.snapshot(jukebox).isEmpty() && clock.current() == before,
                "Missing HAS_RECORD allocated a snapshot");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void nativeThirdPartyDiscSnapshotsDoNotTakePlaybackOwnership(GameTestHelper helper) {
        var jukebox = place(helper);
        var foreign = ForgeRegistries.ITEMS.getValue(ResourceLocation.fromNamespaceAndPath(Etched.MOD_ID, "gametest_foreign_disc"));
        jukebox.setFirstItem(new ItemStack(foreign));
        var clock = ServerPlaybackClock.get(helper.getLevel());
        long before = clock.current();
        var saved = jukebox.saveWithoutMetadata();
        helper.assertTrue(jukebox.isRecordPlaying(), "Native disc fixture did not start");
        helper.assertTrue(JukeboxServerPlayback.snapshot(jukebox).isEmpty() && clock.current() == before,
                "Native third-party disc acquired a managed snapshot");
        helper.assertTrue(saved.equals(jukebox.saveWithoutMetadata()), "Snapshot mutated native playback");
        helper.succeed();
    }

    private static JukeboxBlockEntity place(GameTestHelper helper) {
        helper.setBlock(BlockPos.ZERO, Blocks.JUKEBOX);
        return (JukeboxBlockEntity) helper.getBlockEntity(BlockPos.ZERO);
    }
}
