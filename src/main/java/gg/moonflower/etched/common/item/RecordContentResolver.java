package gg.moonflower.etched.common.item;

import gg.moonflower.etched.api.record.PlayableRecord;
import gg.moonflower.etched.common.audio.AudioProgram;
import gg.moonflower.etched.common.audio.AudioTrack;
import gg.moonflower.etched.common.audio.RecordContent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.RecordItem;

import java.util.ArrayList;
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
            List<AudioTrack> tracks = new ArrayList<>();
            for (ItemStack disc : AlbumCoverItem.readRecords(stack)) {
                // No recursive albums, and each record has at most one bounded program.
                if ((disc.getItem() instanceof RecordItem record && VanillaRecordAdapter.isVanilla(record))
                        || disc.getItem() instanceof EtchedMusicDiscItem) {
                    resolve(disc).ifPresent(content -> {
                        for (AudioTrack track : content.program().tracks()) {
                            if (tracks.size() < AudioProgram.MAX_TRACKS) {
                                tracks.add(track);
                            }
                        }
                    });
                } else if (disc.getItem() instanceof PlayableRecord) {
                    // Never silently discard third-party tracks from a legacy Album Cover.
                    return Optional.empty();
                }
            }
            return fromAudioTracks(tracks);
        }
        return Optional.empty();
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
