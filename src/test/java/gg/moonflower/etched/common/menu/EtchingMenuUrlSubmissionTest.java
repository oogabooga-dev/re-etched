package gg.moonflower.etched.common.menu;

import gg.moonflower.etched.common.network.play.ServerboundSetEtchingUrlPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EtchingMenuUrlSubmissionTest {

    @Test
    void acceptsSupportedUrlFormsAndEmptyInvalidation() {
        assertTrue(EtchingMenu.isValidUrlSubmission(""));
        assertTrue(EtchingMenu.isValidUrlSubmission("minecraft:music_disc.13"));
        assertTrue(EtchingMenu.isValidUrlSubmission("custom_sound"));
        assertTrue(EtchingMenu.isValidUrlSubmission("https://audio.example/track.ogg"));
    }

    @Test
    void rejectsMalformedAndOversizedSubmissions() {
        assertFalse(EtchingMenu.isValidUrlSubmission(null));
        assertFalse(EtchingMenu.isValidUrlSubmission("ftp://audio.example/track.mp3"));
        assertFalse(EtchingMenu.isValidUrlSubmission("not a resource location"));
        assertFalse(EtchingMenu.isValidUrlSubmission(
                "u".repeat(ServerboundSetEtchingUrlPacket.MAX_URL_LENGTH + 1)));
    }
}
