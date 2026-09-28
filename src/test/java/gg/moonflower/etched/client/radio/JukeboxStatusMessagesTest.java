package gg.moonflower.etched.client.radio;

import gg.moonflower.etched.common.audio.AudioProgram;
import gg.moonflower.etched.common.audio.AudioTrack;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JukeboxStatusMessagesTest {

    static {
        MinecraftTestBootstrap.bootStrap();
    }

    private static final AudioProgram ALBUM = new AudioProgram(AudioProgram.Kind.FINITE, List.of(
            new AudioTrack(AudioTrack.SourceType.SOUND_EVENT, "minecraft:music_disc.13", "", "Vanilla disc"),
            new AudioTrack(AudioTrack.SourceType.REMOTE, "https://audio.example/song.mp3", "Artist", "Second track")));

    @Test
    void displaysOnlyAnActuallyPlayingFiniteTrack() {
        PlaybackSession session = new PlaybackSession();
        PlaybackSession.Attempt attempt = session.start(ALBUM.tracks().get(0).source());
        assertNull(JukeboxStatusMessages.forSnapshot(ALBUM, session.snapshot()));
        session.advance(attempt, RadioPlaybackState.CONNECTING, 1L);
        session.offerStreamTitle(attempt, "Vanilla disc");
        session.applyPendingStreamTitle();
        assertNull(JukeboxStatusMessages.forSnapshot(ALBUM, session.snapshot()));
        session.advance(attempt, RadioPlaybackState.BUFFERING, 2L);
        session.advance(attempt, RadioPlaybackState.PLAYING, 3L);
        assertEquals("Vanilla disc", JukeboxStatusMessages.forSnapshot(ALBUM, session.snapshot()).getString());

        session.advanceToNextTrack(attempt);
        assertNull(JukeboxStatusMessages.forSnapshot(ALBUM, session.snapshot()));
        session.offerStreamTitle(attempt, "Second track");
        session.applyPendingStreamTitle();
        session.advance(attempt, RadioPlaybackState.BUFFERING, 4L);
        session.advance(attempt, RadioPlaybackState.PLAYING, 5L);
        assertTrue(JukeboxStatusMessages.forSnapshot(ALBUM, session.snapshot()).getContents()
                instanceof TranslatableContents);
        session.complete(attempt);
        assertNull(JukeboxStatusMessages.forSnapshot(ALBUM, session.snapshot()));
    }

    @Test
    void liveMetadataUsesItsOwnTitleIfNoAlbumTrackMatches() {
        PlaybackSession session = new PlaybackSession();
        PlaybackSession.Attempt attempt = session.start(ALBUM.tracks().get(1).source());
        session.advance(attempt, RadioPlaybackState.CONNECTING, 1L);
        session.advance(attempt, RadioPlaybackState.BUFFERING, 2L);
        session.advance(attempt, RadioPlaybackState.PLAYING, 3L);
        session.offerStreamTitle(attempt, "Stream title");
        session.applyPendingStreamTitle();
        assertEquals("Stream title", JukeboxStatusMessages.forSnapshot(ALBUM, session.snapshot()).getString());
    }
}
