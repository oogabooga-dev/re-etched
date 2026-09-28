package gg.moonflower.etched.common.item;

import gg.moonflower.etched.api.record.TrackData;
import gg.moonflower.etched.common.audio.AudioProgram;
import gg.moonflower.etched.common.audio.AudioTrack;
import gg.moonflower.etched.common.audio.RecordContent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.RecordItem;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** First-party disc and vanilla record adapter; does not require the public PlayableRecord API. */
public final class RecordContentResolver {

    private RecordContentResolver() {
    }

    public static Optional<RecordContent> resolve(ItemStack stack) {
        if (stack.isEmpty()) {
            return Optional.empty();
        }
        if (stack.getItem() instanceof RecordItem record) {
            try {
                AudioTrack track = new AudioTrack(AudioTrack.SourceType.SOUND_EVENT,
                        record.getSound().getLocation().toString(), "Minecraft", record.getDisplayName().getString());
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
                if (disc.getItem() instanceof RecordItem || disc.getItem() instanceof EtchedMusicDiscItem) {
                    resolve(disc).ifPresent(content -> {
                        for (AudioTrack track : content.program().tracks()) {
                            if (tracks.size() < AudioProgram.MAX_TRACKS) {
                                tracks.add(track);
                            }
                        }
                    });
                }
            }
            return fromAudioTracks(tracks);
        }
        return Optional.empty();
    }

    static Optional<RecordContent> fromDisc(ItemStack stack) {
        Optional<RecordContent> content = fromTracks(EtchedMusicDiscItem.readMusic(stack).orElseGet(() -> new TrackData[0]));
        if (content.isEmpty() || stack.getTag() == null || !stack.getTag().contains("Album", Tag.TAG_COMPOUND)) {
            return content;
        }
        Optional<RecordContent.AlbumMetadata> album = EtchedMusicDiscItem.readAlbum(stack)
                .flatMap(RecordContentResolver::albumMetadata);
        try {
            return Optional.of(new RecordContent(content.orElseThrow().program(), album));
        } catch (IllegalArgumentException exception) {
            return content;
        }
    }

    static Optional<RecordContent> fromTracks(TrackData[] data) {
        List<AudioTrack> tracks = new ArrayList<>();
        for (TrackData track : data) {
            if (tracks.size() >= AudioProgram.MAX_TRACKS) {
                break;
            }
            try {
                tracks.add(new AudioTrack(TrackData.isLocalSound(track.url())
                        ? AudioTrack.SourceType.SOUND_EVENT : AudioTrack.SourceType.REMOTE,
                        track.url(), track.artist(), track.title().getString()));
            } catch (IllegalArgumentException exception) {
                // Old disc NBT can contain URLs/metadata that the bounded internal model rejects.
            }
        }
        return fromAudioTracks(tracks);
    }

    private static Optional<RecordContent.AlbumMetadata> albumMetadata(TrackData data) {
        try {
            return Optional.of(new RecordContent.AlbumMetadata(TrackData.isLocalSound(data.url())
                    ? AudioTrack.SourceType.SOUND_EVENT : AudioTrack.SourceType.REMOTE,
                    data.url(), data.artist(), data.title().getString()));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
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
