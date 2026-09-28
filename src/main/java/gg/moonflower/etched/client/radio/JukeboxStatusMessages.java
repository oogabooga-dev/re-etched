package gg.moonflower.etched.client.radio;

import gg.moonflower.etched.common.audio.AudioProgram;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/** Record text follows the track that actually started, not the jukebox's insertion event. */
final class JukeboxStatusMessages {

    private JukeboxStatusMessages() {
    }

    @Nullable
    static Component forSnapshot(AudioProgram program, PlaybackSession.Snapshot snapshot) {
        String title = snapshot.streamTitle();
        if (snapshot.state() != RadioPlaybackState.PLAYING || title == null || title.isBlank()) {
            return null;
        }
        return program.tracks().stream().filter(track -> title.equals(track.title()))
                .findFirst().map(track -> track.artist().isBlank() ? Component.literal(title)
                        : Component.translatable("sound_source.etched.info", track.artist(), title))
                .orElseGet(() -> Component.literal(title));
    }
}
