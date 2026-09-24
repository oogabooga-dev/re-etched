package gg.moonflower.etched.client.radio;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RadioStatusMessagesTest {

    @Test
    void mapsLifecycleStatesWithoutExposingInternalFailures() {
        assertNull(message(RadioPlaybackState.STOPPED, null, null));
        assertTranslation("screen.etched.radio.status.resolving",
                message(RadioPlaybackState.RESOLVING, null, null));
        assertTranslation("screen.etched.radio.status.connecting",
                message(RadioPlaybackState.CONNECTING, null, null));
        assertTranslation("screen.etched.radio.status.buffering",
                message(RadioPlaybackState.BUFFERING, null, null));
        assertTranslation("screen.etched.radio.status.reconnecting",
                message(RadioPlaybackState.RECONNECT_WAIT,
                        RadioFailure.recoverable(RadioFailure.Code.HTTP_STATUS, "secret", null), null));
        assertTranslation("screen.etched.radio.error.timeout",
                message(RadioPlaybackState.FAILED,
                        RadioFailure.fatal(RadioFailure.Code.CONNECT_TIMEOUT, "secret", null), null));
    }

    @Test
    void displaysStreamTitleOnlyWhilePlaying() {
        Component title = message(RadioPlaybackState.PLAYING, null, "Station Title");
        Component fallback = message(RadioPlaybackState.PLAYING, null, null);

        assertEquals("Station Title", title.getString());
        assertTranslation("sound_source.etched.radio", fallback);
    }

    @Test
    void classifiesUserFacingFailures() {
        assertFailure(RadioFailure.Code.INVALID_URL, "invalid_url");
        assertFailure(RadioFailure.Code.BLOCKED_ADDRESS, "blocked_address");
        assertFailure(RadioFailure.Code.UNSUPPORTED_HLS, "unsupported_audio");
        assertFailure(RadioFailure.Code.PLAYLIST_TOO_LARGE, "resource_limit");
        assertFailure(RadioFailure.Code.RESOURCE_LIMIT, "resource_limit");
        assertFailure(RadioFailure.Code.DECODER_FAILURE, "playback");
    }

    private static void assertFailure(RadioFailure.Code code, String suffix) {
        assertTranslation("screen.etched.radio.error." + suffix,
                message(RadioPlaybackState.FAILED, RadioFailure.fatal(code, "internal detail", null), null));
    }

    private static Component message(RadioPlaybackState state, RadioFailure failure, String title) {
        return RadioStatusMessages.forSnapshot(new PlaybackSession.Snapshot(1L, "source", state, failure, title));
    }

    private static void assertTranslation(String key, Component component) {
        TranslatableContents contents = (TranslatableContents) component.getContents();
        assertEquals(key, contents.getKey());
    }
}
