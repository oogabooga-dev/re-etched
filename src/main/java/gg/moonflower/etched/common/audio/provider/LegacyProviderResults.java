package gg.moonflower.etched.common.audio.provider;

import gg.moonflower.etched.api.record.TrackData;
import gg.moonflower.etched.common.audio.AudioProgram;
import gg.moonflower.etched.common.audio.AudioTrack;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** Bounded snapshots at the synchronous third-party API boundary; not a sandbox for provider I/O. */
public final class LegacyProviderResults {

    public static final int MAX_FIELD_LENGTH = 8192;
    public static final int MAX_METADATA_ENTRIES = AudioProgram.MAX_TRACKS + 1; // optional album descriptor

    private LegacyProviderResults() {
    }

    public static TrackData[] tracks(List<TrackData> entries) throws IOException {
        if (entries == null || entries.isEmpty() || entries.size() > MAX_METADATA_ENTRIES) {
            throw new IOException("Provider returned missing or excessive track metadata");
        }
        List<TrackData> copy = new ArrayList<>();
        for (TrackData track : entries) {
            if (copy.size() >= MAX_METADATA_ENTRIES || track == null || track.title() == null) {
                throw new IOException("Provider returned invalid track metadata");
            }
            source(track.url());
            field(track.artist());
            try {
                // Component.copy() does not deeply copy mutable siblings/styles.
                String json = Component.Serializer.toJson(track.title());
                field(json);
                Component title = Component.Serializer.fromJson(json);
                if (title == null) {
                    throw new IOException("Provider returned a missing title");
                }
                copy.add(new TrackData(track.url(), track.artist(), title));
            } catch (RuntimeException exception) {
                throw new IOException("Provider returned invalid title metadata", exception);
            }
        }
        return copy.toArray(TrackData[]::new);
    }

    private static void source(String source) throws IOException {
        field(source);
        if (source.isEmpty() || source.endsWith(":")) {
            throw new IOException("Provider returned an empty track source");
        }
        try {
            boolean local = TrackData.isLocalSound(source) && !source.startsWith("http:") && !source.startsWith("https:");
            new AudioTrack(local ? AudioTrack.SourceType.SOUND_EVENT
                    : AudioTrack.SourceType.REMOTE, source, "", "");
        } catch (RuntimeException exception) {
            throw new IOException("Provider returned an invalid track source", exception);
        }
    }

    public static URI remote(String source) throws IOException {
        field(source);
        try {
            new AudioTrack(AudioTrack.SourceType.REMOTE, source, "", "");
            return URI.create(source);
        } catch (RuntimeException exception) {
            throw new IOException("Provider returned an invalid HTTP(S) destination", exception);
        }
    }

    private static void field(String value) throws IOException {
        if (value == null || value.length() > MAX_FIELD_LENGTH) {
            throw new IOException("Provider returned missing or excessive metadata fields");
        }
    }
}
