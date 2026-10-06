package gg.moonflower.etched.common.item;

import gg.moonflower.etched.client.radio.MinecraftTestBootstrap;
import gg.moonflower.etched.common.audio.AudioNbtCodec;
import gg.moonflower.etched.common.audio.AudioProgram;
import gg.moonflower.etched.common.audio.AudioTrack;
import gg.moonflower.etched.common.audio.RecordContent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EtchedMusicDiscItemCharacterizationTest {

    static {
        MinecraftTestBootstrap.bootStrap();
    }

    private static final AudioTrack ALBUM = track("album");
    private static final AudioTrack FIRST = track("first");
    private static final AudioTrack SECOND = track("second");

    @Test
    void singleTrackRoundTripsThroughVersionedContentWithPresentationAlbumFallback() {
        ItemStack stack = new ItemStack(Items.PAPER);

        EtchedMusicDiscItem.setContent(stack, content(null, FIRST));

        var content = EtchedMusicDiscItem.readContent(stack).orElseThrow();
        assertEquals(List.of(FIRST), content.program().tracks());
        assertTrue(content.album().isEmpty());
        assertEquals(FIRST.source(), RecordPresentation.source(content));
        assertFalse(stack.getOrCreateTag().contains("Album"));
        assertFalse(stack.getOrCreateTag().contains("Music"));
        var encoded = stack.getTag().getCompound(EtchedMusicDiscItem.CONTENT_TAG);
        assertEquals(AudioNbtCodec.SCHEMA_VERSION, encoded.getInt("SchemaVersion"));
        assertEquals("finite", encoded.getCompound("Program").getString("Kind"));
        assertFalse(encoded.contains("AlbumMetadata"));
        assertEquals(AudioTrack.SourceType.REMOTE, EtchedMusicDiscItem.readContent(stack).orElseThrow()
                .program().tracks().get(0).sourceType());
    }

    @Test
    void albumMetadataIsSeparateAndTrackOrderIsPreserved() {
        ItemStack stack = new ItemStack(Items.PAPER);
        EtchedMusicDiscItem.setContent(stack, content(ALBUM, FIRST, SECOND));
        var content = EtchedMusicDiscItem.readContent(stack).orElseThrow();
        assertEquals(List.of(FIRST, SECOND), content.program().tracks());
        assertEquals(ALBUM.source(), content.album().orElseThrow().source());
        assertEquals(ALBUM.source(), RecordPresentation.source(content));
        assertFalse(stack.getTag().contains("Music"));
        assertFalse(stack.getTag().contains("Album"));
    }

    @Test
    void clearingAudioContentPreservesUnrelatedPresentationAndRemovesObsoleteAudioKeys() {
        ItemStack stack = new ItemStack(Items.PAPER);
        EtchedMusicDiscItem.setContent(stack, content(ALBUM, FIRST, SECOND));
        stack.getOrCreateTag().putInt("DiscColor", 0x123456);
        stack.getTag().put("Music", legacyTrack(FIRST));
        stack.getTag().put("Album", legacyTrack(ALBUM));

        EtchedMusicDiscItem.clearContent(stack);

        assertTrue(EtchedMusicDiscItem.readContent(stack).isEmpty());
        assertFalse(stack.getOrCreateTag().contains("Music"));
        assertFalse(stack.getOrCreateTag().contains("Album"));
        assertFalse(stack.getOrCreateTag().contains(EtchedMusicDiscItem.CONTENT_TAG));
        assertEquals(0x123456, stack.getTag().getInt("DiscColor"));
    }

    @Test
    void oldSingleAndAlbumLayoutsAreNotReadOrModified() {
        for (boolean album : new boolean[]{false, true}) {
            ItemStack stack = new ItemStack(Items.PAPER);
            if (album) {
                ListTag oldMusic = new ListTag();
                oldMusic.add(legacyTrack(FIRST));
                oldMusic.add(legacyTrack(SECOND));
                stack.getOrCreateTag().put("Music", oldMusic);
                stack.getTag().put("Album", legacyTrack(ALBUM));
            } else {
                stack.getOrCreateTag().put("Music", legacyTrack(FIRST));
            }
            CompoundTag before = stack.getTag().copy();
            assertRejected(stack);
            assertEquals(before, stack.getTag());
        }
    }

    @Test
    void malformedFutureOversizeAndLiveEnvelopesDoNotFallBackToLegacyOrPartialPrograms() {
        for (String fault : List.of("wrong-envelope", "no-version", "future", "live", "invalid-track", "invalid-album",
                "too-many", "wrong-list", "long-title", "total-text")) {
            ItemStack stack = new ItemStack(Items.PAPER);
            EtchedMusicDiscItem.setContent(stack, content(ALBUM, FIRST, SECOND));
            stack.getTag().put("Music", legacyTrack(FIRST));
            CompoundTag content = stack.getTag().getCompound(EtchedMusicDiscItem.CONTENT_TAG);
            CompoundTag program = content.getCompound("Program");
            ListTag tracks = program.getList("Tracks", Tag.TAG_COMPOUND);
            switch (fault) {
                case "wrong-envelope" -> stack.getTag().putString(EtchedMusicDiscItem.CONTENT_TAG, "invalid");
                case "no-version" -> content.remove("SchemaVersion");
                case "future" -> content.putInt("SchemaVersion", AudioNbtCodec.SCHEMA_VERSION + 1);
                case "live" -> {
                    program.putString("Kind", "live");
                    tracks.remove(1);
                }
                case "invalid-track" -> tracks.add(1, new CompoundTag());
                case "invalid-album" -> content.getCompound("AlbumMetadata").putString("Source", "file:///private");
                case "too-many" -> {
                    while (tracks.size() <= AudioProgram.MAX_TRACKS) {
                        tracks.add(tracks.getCompound(0).copy());
                    }
                }
                case "wrong-list" -> program.putString("Tracks", "invalid");
                case "long-title" -> tracks.getCompound(1).putString("Title", "x".repeat(AudioTrack.MAX_METADATA_LENGTH + 1));
                case "total-text" -> {
                    tracks.clear();
                    for (int i = 0; i < 10; i++) {
                        var track = new CompoundTag();
                        track.putString("SourceType", "remote");
                        track.putString("Source", "https://audio.example/" + "x".repeat(7000));
                        tracks.add(track);
                    }
                }
                default -> throw new AssertionError(fault);
            }
            CompoundTag before = stack.getTag().copy();
            assertRejected(stack);
            assertEquals(before, stack.getTag(), fault);
        }
    }

    @Test
    void invalidTypedContentCannotBeConstructedAndMutateTheExistingDisc() {
        ItemStack stack = new ItemStack(Items.PAPER);
        EtchedMusicDiscItem.setContent(stack, content(null, FIRST));
        CompoundTag before = stack.getTag().copy();
        for (java.util.function.Supplier<AudioTrack> invalid : List.<java.util.function.Supplier<AudioTrack>>of(
                () -> new AudioTrack(FIRST.sourceType(), FIRST.source(), "x".repeat(129), FIRST.title()),
                () -> new AudioTrack(FIRST.sourceType(), FIRST.source(), FIRST.artist(), "x".repeat(129)),
                () -> new AudioTrack(FIRST.sourceType(), "file:///unsafe", FIRST.artist(), FIRST.title()),
                () -> new AudioTrack(FIRST.sourceType(), "https://user@audio.example/track", FIRST.artist(), FIRST.title()))) {
            assertThrows(IllegalArgumentException.class, () -> EtchedMusicDiscItem.setContent(stack, content(null, invalid.get())));
            assertEquals(before, stack.getTag());
        }
        assertThrows(IllegalArgumentException.class, () -> EtchedMusicDiscItem.setContent(stack,
                new RecordContent(new AudioProgram(AudioProgram.Kind.FINITE, java.util.Collections.nCopies(
                        AudioProgram.MAX_TRACKS + 1, new AudioTrack(AudioTrack.SourceType.REMOTE, FIRST.source(), "Artist", "First"))))));
        assertEquals(before, stack.getTag());
    }

    @Test
    void typedContentAndPresentationHaveIndependentOwnershipAndKeepCanonicalLocalSources() {
        ItemStack stack = new ItemStack(Items.PAPER);
        var content = new RecordContent(new AudioProgram(AudioProgram.Kind.FINITE, List.of(
                new AudioTrack(AudioTrack.SourceType.SOUND_EVENT, "test:music/track", "Artist", "Track"))));
        EtchedMusicDiscItem.setContent(stack, content);
        assertEquals(content, EtchedMusicDiscItem.readContent(stack).orElseThrow());
        var snapshot = EtchedMusicDiscItem.readContent(stack).orElseThrow();
        assertThrows(UnsupportedOperationException.class, () -> snapshot.program().tracks().clear());
        RecordPresentation.displayName(snapshot).getSiblings().add(Component.literal(" changed"));
        assertEquals(content, EtchedMusicDiscItem.readContent(stack).orElseThrow());
        assertEquals("Track", snapshot.program().tracks().get(0).title());
        stack.getTag().getCompound(EtchedMusicDiscItem.CONTENT_TAG).getCompound("Program")
                .getList("Tracks", Tag.TAG_COMPOUND).getCompound(0).putString("Title", "Changed NBT");
        assertEquals("Track", content.program().tracks().get(0).title());
        assertEquals("Changed NBT", EtchedMusicDiscItem.readContent(stack).orElseThrow().program().tracks().get(0).title());
    }

    @Test
    void writingContentDoesNotRetainOldAudioKeysOrReadThemAsFallback() {
        ItemStack stack = new ItemStack(Items.PAPER);
        stack.getOrCreateTag().put("Music", legacyTrack(FIRST));
        stack.getTag().put("Album", legacyTrack(ALBUM));
        stack.getTag().putString("Marker", "retained");
        EtchedMusicDiscItem.setContent(stack, content(null, SECOND));
        assertFalse(stack.getTag().contains("Music"));
        assertFalse(stack.getTag().contains("Album"));
        assertEquals("retained", stack.getTag().getString("Marker"));
        assertEquals(List.of(SECOND), EtchedMusicDiscItem.readContent(stack).orElseThrow().program().tracks());
    }

    private static void assertRejected(ItemStack stack) {
        assertTrue(EtchedMusicDiscItem.readContent(stack).isEmpty());
        assertTrue(RecordContentResolver.fromDisc(stack).isEmpty());
    }

    @Test
    void readingLegacyColorsMigratesThemToCurrentKeys() {
        ItemStack stack = new ItemStack(Items.PAPER);
        stack.getOrCreateTag().putInt("PrimaryColor", 0x123456);
        stack.getOrCreateTag().putInt("SecondaryColor", 0xABCDEF);

        assertEquals(0x123456, EtchedMusicDiscItem.getDiscColor(stack));
        assertEquals(0xABCDEF, EtchedMusicDiscItem.getLabelPrimaryColor(stack));
        assertEquals(0xABCDEF, EtchedMusicDiscItem.getLabelSecondaryColor(stack));
        assertFalse(stack.getOrCreateTag().contains("PrimaryColor"));
        assertFalse(stack.getOrCreateTag().contains("SecondaryColor"));
        assertEquals(0x123456, stack.getOrCreateTag().getInt("DiscColor"));
    }

    @Test
    void invalidPatternFallsBackToFlat() {
        ItemStack stack = new ItemStack(Items.PAPER);
        stack.getOrCreateTag().putByte("Pattern", (byte) 127);

        assertEquals(EtchedMusicDiscItem.LabelPattern.FLAT, EtchedMusicDiscItem.getPattern(stack));
    }

    private static AudioTrack track(String name) {
        return new AudioTrack(AudioTrack.SourceType.REMOTE, "https://audio.example/" + name + ".mp3", "Artist", name);
    }

    private static RecordContent content(AudioTrack album, AudioTrack... tracks) {
        var program = new AudioProgram(AudioProgram.Kind.FINITE, List.of(tracks));
        return new RecordContent(program, Optional.ofNullable(album).map(data -> new RecordContent.AlbumMetadata(
                data.sourceType(), data.source(), data.artist(), data.title())));
    }

    /** Golden pre-v5 payload, used only to prove rejection; production has no legacy codec. */
    private static CompoundTag legacyTrack(AudioTrack data) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Url", data.source());
        tag.putString("Author", data.artist());
        tag.putString("Title", Component.Serializer.toJson(Component.literal(data.title())));
        return tag;
    }
}
