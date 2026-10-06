package gg.moonflower.etched.common.item;

import gg.moonflower.etched.common.audio.AudioProgram;
import gg.moonflower.etched.common.audio.AudioTrack;
import gg.moonflower.etched.common.audio.RecordContent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.RecordItem;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** First-party record content; unsupported third-party entries reject the managed program. */
public final class RecordContentResolver {

    private RecordContentResolver() {
    }

    public static Optional<RecordContent> resolve(ItemStack stack) {
        if (stack.isEmpty()) {
            return Optional.empty();
        }
        if (stack.getItem() instanceof RecordItem record && VanillaRecordAdapter.isVanilla(record)) {
            try {
                AudioTrack track = new AudioTrack(AudioTrack.SourceType.SOUND_EVENT,
                        record.getSound().getLocation().toString(), "", record.getDisplayName().getString());
                return Optional.of(new RecordContent(new AudioProgram(AudioProgram.Kind.FINITE, List.of(track))));
            } catch (IllegalArgumentException exception) {
                return Optional.empty();
            }
        }
        if (stack.getItem() instanceof EtchedMusicDiscItem) {
            return fromDisc(stack);
        }
        if (stack.getItem() instanceof AlbumCoverItem) {
            return fromRecords(AlbumCoverItem.readRecords(stack));
        }
        return Optional.empty();
    }

    /** Album slots accept only valid first-party discs, never another album or a legacy provider. */
    public static boolean isPlayableDisc(ItemStack stack) {
        return !(stack.getItem() instanceof AlbumCoverItem) && resolve(stack).isPresent();
    }

    static Optional<RecordContent> fromRecords(Collection<ItemStack> records) {
        List<AudioTrack> tracks = new ArrayList<>();
        for (ItemStack disc : records) {
            if (disc.isEmpty()) {
                continue;
            }
            // Reject a whole album rather than silently dropping unsupported/invalid discs or later tracks.
            if (disc.getItem() instanceof AlbumCoverItem) {
                return Optional.empty();
            }
            Optional<RecordContent> content = resolve(disc);
            if (content.isEmpty() || content.orElseThrow().program().tracks().size() > AudioProgram.MAX_TRACKS - tracks.size()) {
                return Optional.empty();
            }
            tracks.addAll(content.orElseThrow().program().tracks());
        }
        return fromAudioTracks(tracks);
    }

    static Optional<RecordContent> fromDisc(ItemStack stack) {
        return EtchedMusicDiscItem.readContent(stack);
    }

    private static Optional<RecordContent> fromAudioTracks(List<AudioTrack> tracks) {
        if (tracks.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new RecordContent(new AudioProgram(AudioProgram.Kind.FINITE, tracks)));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
