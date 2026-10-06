package gg.moonflower.etched.common.menu;

import gg.moonflower.etched.common.audio.AudioProgram;
import gg.moonflower.etched.common.audio.AudioTrack;
import gg.moonflower.etched.common.audio.RecordContent;
import gg.moonflower.etched.common.item.RecordPresentation;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.Optional;

/** Pure, bounded metadata editing; album descriptors are never implicit playback entries. */
@ApiStatus.Internal
public final class EtchingMetadata {

    private EtchingMetadata() {
    }

    static AudioTrack.SourceType sourceType(String source) {
        if (source == null) {
            return null;
        }
        // ResourceLocation accepts // paths too; URL-shaped inputs must use remote validation.
        AudioTrack.SourceType type = source.contains(":/") || source.regionMatches(true, 0, "http:", 0, 5)
                || source.regionMatches(true, 0, "https:", 0, 6)
                ? AudioTrack.SourceType.REMOTE : AudioTrack.SourceType.SOUND_EVENT;
        try {
            new AudioTrack(type, source, "", "");
            return type;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public static String source(RecordContent content) {
        return RecordPresentation.source(content);
    }

    static RecordContent direct(String source, Optional<RecordContent> previous, boolean hasLabel,
                                String artist, String title) {
        AudioTrack.SourceType type = sourceType(source);
        if (type == null) {
            throw new IllegalArgumentException("Invalid etching source");
        }
        // Re-etching an unchanged local program retains all tracks and explicit album metadata.
        if (type == AudioTrack.SourceType.SOUND_EVENT && previous.isPresent()
                && source(previous.orElseThrow()).equals(new AudioTrack(type, source, "", "").source())
                && (!hasLabel || previous.orElseThrow().album().isPresent()
                    || previous.orElseThrow().program().tracks().size() > 1)) {
            return previous.orElseThrow();
        }
        AudioTrack first = previous.map(content -> content.program().tracks().get(0)).orElse(null);
        String selectedArtist = hasLabel ? artist : first == null ? "Unknown" : first.artist();
        String selectedTitle = hasLabel ? title : first == null ? "Custom Music" : first.title();
        return new RecordContent(new AudioProgram(AudioProgram.Kind.FINITE,
                List.of(new AudioTrack(type, source, selectedArtist, selectedTitle))));
    }

    static RecordContent fallbackArtist(RecordContent content, String artist) {
        var tracks = content.program().tracks().stream().map(track -> new AudioTrack(track.sourceType(), track.source(),
                track.artist().equals("Unknown") ? artist : track.artist(), track.title())).toList();
        var album = content.album().map(data -> new RecordContent.AlbumMetadata(data.sourceType(), data.source(),
                data.artist().equals("Unknown") ? artist : data.artist(), data.title()));
        return new RecordContent(new AudioProgram(AudioProgram.Kind.FINITE, tracks), album);
    }
}
