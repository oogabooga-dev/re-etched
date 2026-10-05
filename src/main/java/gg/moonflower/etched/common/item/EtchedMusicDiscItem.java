package gg.moonflower.etched.common.item;

import gg.moonflower.etched.api.record.PlayableRecordItem;
import gg.moonflower.etched.api.record.TrackData;
import gg.moonflower.etched.common.audio.AudioNbtCodec;
import gg.moonflower.etched.common.audio.AudioProgram;
import gg.moonflower.etched.common.audio.AudioTrack;
import gg.moonflower.etched.common.audio.RecordContent;
import gg.moonflower.etched.core.Etched;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Optional;

/**
 * @author Ocelot
 */
public class EtchedMusicDiscItem extends PlayableRecordItem {

    public static final String CONTENT_TAG = "AudioContent";

    public EtchedMusicDiscItem(Properties properties) {
        super(properties);
    }

    @Override
    public Optional<TrackData[]> getMusic(ItemStack stack) {
        return readMusic(stack);
    }

    /** Only the versioned audio envelope is accepted; legacy disc data is intentionally not migrated. */
    public static Optional<RecordContent> readContent(ItemStack stack) {
        CompoundTag nbt = stack.getTag();
        if (nbt == null || !(nbt.get(CONTENT_TAG) instanceof CompoundTag content)) {
            return Optional.empty();
        }
        return AudioNbtCodec.readRecordContent(content).result();
    }

    static Optional<TrackData[]> readMusic(ItemStack stack) {
        // Temporary presentation projection for the still-live metadata/UI consumers.
        return readContent(stack).map(content -> content.program().tracks().stream()
                .map(track -> presentation(track.source(), track.artist(), track.title())).toArray(TrackData[]::new));
    }

    @Override
    public Optional<TrackData> getAlbum(ItemStack stack) {
        return readAlbum(stack);
    }

    static Optional<TrackData> readAlbum(ItemStack stack) {
        return readContent(stack).map(content -> content.album()
                .map(album -> presentation(album.source(), album.artist(), album.title()))
                .orElseGet(() -> {
                    AudioTrack first = content.program().tracks().get(0);
                    return presentation(first.source(), first.artist(), first.title());
                }));
    }

    private static TrackData presentation(String source, String artist, String title) {
        return new TrackData(source, artist, Component.literal(title));
    }

    @Override
    public int getTrackCount(ItemStack stack) {
        return countTracks(stack);
    }

    static int countTracks(ItemStack stack) {
        return readContent(stack).map(content -> content.program().tracks().size()).orElse(0);
    }

    /**
     * Retrieves the label pattern from the specified stack.
     *
     * @param stack The stack to get the pattern from
     * @return The pattern for that item
     */
    public static LabelPattern getPattern(ItemStack stack) {
        CompoundTag nbt = stack.getTag();
        if (nbt == null || !nbt.contains("Pattern", Tag.TAG_ANY_NUMERIC)) {
            return LabelPattern.FLAT;
        }
        int id = nbt.getByte("Pattern");
        return id < 0 || id >= LabelPattern.values().length ? LabelPattern.FLAT : LabelPattern.values()[id];
    }

    /**
     * Retrieves the color of the physical disc from the specified stack.
     *
     * @param stack The stack to get the color from
     * @return The color for the physical disc
     */
    public static int getDiscColor(ItemStack stack) {
        CompoundTag nbt = stack.getTag();
        if (nbt == null) {
            return 0x515151;
        }

        // Convert old colors
        if (nbt.contains("PrimaryColor", Tag.TAG_ANY_NUMERIC)) {
            nbt.putInt("DiscColor", nbt.getInt("PrimaryColor"));
            nbt.remove("PrimaryColor");
        }

        if (!nbt.contains("DiscColor", Tag.TAG_ANY_NUMERIC)) {
            return 0x515151;
        }
        return nbt.getInt("DiscColor");
    }

    /**
     * Retrieves the primary color of the label from the specified stack.
     *
     * @param stack The stack to get the color from
     * @return The color for the label
     */
    public static int getLabelPrimaryColor(ItemStack stack) {
        CompoundTag nbt = stack.getTag();
        if (nbt == null) {
            return 0xFFFFFF;
        }

        // Convert old colors
        CompoundTag labelTag = nbt.getCompound("LabelColor");
        if (nbt.contains("SecondaryColor", Tag.TAG_ANY_NUMERIC)) {
            labelTag.putInt("Primary", nbt.getInt("SecondaryColor"));
            labelTag.putInt("Secondary", nbt.getInt("SecondaryColor"));
            nbt.put("LabelColor", labelTag);
            nbt.remove("SecondaryColor");
        }

        return labelTag.contains("Primary", Tag.TAG_ANY_NUMERIC) ? labelTag.getInt("Primary") : 0xFFFFFF;
    }

    /**
     * Retrieves the secondary color of the label from the specified stack.
     *
     * @param stack The stack to get the color from
     * @return The color for the label
     */
    public static int getLabelSecondaryColor(ItemStack stack) {
        CompoundTag nbt = stack.getTag();
        if (nbt == null) {
            return 0xFFFFFF;
        }

        // Convert old colors
        CompoundTag labelTag = nbt.getCompound("LabelColor");
        if (nbt.contains("SecondaryColor", Tag.TAG_ANY_NUMERIC)) {
            labelTag.putInt("Primary", nbt.getInt("SecondaryColor"));
            labelTag.putInt("Secondary", nbt.getInt("SecondaryColor"));
            nbt.put("LabelColor", labelTag);
            nbt.remove("SecondaryColor");
        }

        return labelTag.contains("Secondary", Tag.TAG_ANY_NUMERIC) ? labelTag.getInt("Secondary") : 0xFFFFFF;
    }

    /**
     * Sets the URL for the specified stack.
     *
     * @param stack  The stack to set NBT for
     * @param tracks The tracks to apply to the disk. If more than one are provided, the first is treated as the album data
     */
    public static void setMusic(ItemStack stack, TrackData... tracks) {
        if (tracks.length == 0) {
            clearContent(stack);
            return;
        }
        // Temporary input boundary for metadata consumers: validate the whole result before touching NBT.
        int firstTrack = tracks.length == 1 ? 0 : 1;
        if (tracks.length - firstTrack > AudioProgram.MAX_TRACKS) {
            throw new IllegalArgumentException("Disc exceeds the track limit");
        }
        var program = new ArrayList<AudioTrack>(tracks.length - firstTrack);
        for (int i = firstTrack; i < tracks.length; i++) {
            program.add(audioTrack(tracks[i]));
        }
        Optional<RecordContent.AlbumMetadata> album = Optional.empty();
        if (firstTrack == 1) {
            AudioTrack descriptor = audioTrack(tracks[0]);
            album = Optional.of(new RecordContent.AlbumMetadata(descriptor.sourceType(), descriptor.source(),
                    descriptor.artist(), descriptor.title()));
        }
        setContent(stack, new RecordContent(new AudioProgram(AudioProgram.Kind.FINITE, program), album));
    }

    private static AudioTrack audioTrack(TrackData track) {
        return new AudioTrack(TrackData.isLocalSound(track.url())
                ? AudioTrack.SourceType.SOUND_EVENT : AudioTrack.SourceType.REMOTE,
                track.url(), track.artist(), track.title().getString());
    }

    public static void setContent(ItemStack stack, RecordContent content) {
        CompoundTag encoded = AudioNbtCodec.write(content);
        stack.getOrCreateTag().put(CONTENT_TAG, encoded);
        stack.removeTagKey("Music");
        stack.removeTagKey("Album");
    }

    public static void clearContent(ItemStack stack) {
        stack.removeTagKey(CONTENT_TAG);
        stack.removeTagKey("Music");
        stack.removeTagKey("Album");
    }

    /**
     * Sets the pattern for the specified stack.
     *
     * @param stack   The stack to set NBT for
     * @param pattern The pattern to apply to the disk or <code>null</code> to remove and default to {@link LabelPattern#FLAT}
     */
    public static void setPattern(ItemStack stack, @Nullable LabelPattern pattern) {
        if (pattern == null) {
            stack.removeTagKey("Pattern");
        } else {
            stack.getOrCreateTag().putByte("Pattern", (byte) pattern.ordinal());
        }
    }

    /**
     * Sets the color for the specified stack.
     *
     * @param stack          The stack to set NBT for
     * @param primaryColor   The color to use for the physical disk
     * @param secondaryColor The color to use for the label
     */
    public static void setColor(ItemStack stack, int discColor, int primaryColor, int secondaryColor) {
        CompoundTag tag = stack.getOrCreateTag();
        tag.putInt("DiscColor", discColor);

        CompoundTag labelTag = tag.getCompound("LabelColor");
        labelTag.putInt("Primary", primaryColor);
        labelTag.putInt("Secondary", secondaryColor);
        tag.put("LabelColor", labelTag);
    }

    /**
     * @author Jackson
     */
    public enum LabelPattern {

        FLAT, CROSS, EYE, PARALLEL, STAR, GOLD(true);

        private final boolean simple;
        private final Pair<ResourceLocation, ResourceLocation> textures;

        LabelPattern() {
            this(false);
        }

        LabelPattern(boolean simple) {
            this.simple = simple;

            String name = this.name().toLowerCase(Locale.ROOT);
            this.textures = Pair.of(
                    ResourceLocation.fromNamespaceAndPath(Etched.MOD_ID, "textures/item/" + name + "_label" + (simple ? "" : "_top") + ".png"),
                    ResourceLocation.fromNamespaceAndPath(Etched.MOD_ID, "textures/item/" + name + "_label" + (simple ? "" : "_bottom") + ".png")
            );
        }

        /**
         * @return A pair of {@link ResourceLocation} for a top and bottom texture. If the pattern is simple, both locations are the same.
         */
        public Pair<ResourceLocation, ResourceLocation> getTextures() {
            return this.textures;
        }

        /**
         * @return Whether the label pattern supports two colors.
         */
        public boolean isSimple() {
            return this.simple;
        }

        /**
         * @return Whether this label can be colored
         */
        public boolean isColorable() {
            return this != GOLD;
        }
    }
}
