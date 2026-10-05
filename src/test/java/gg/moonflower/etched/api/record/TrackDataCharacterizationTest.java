package gg.moonflower.etched.api.record;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackDataCharacterizationTest {

    @Test
    void preservesLegacyLocalAndHttpUrlRules() {
        assertTrue(TrackData.isValidURL("minecraft:music_disc.13"));
        assertTrue(TrackData.isValidURL("custom_sound"));
        assertTrue(TrackData.isValidURL("http://audio.example/track.mp3"));
        assertTrue(TrackData.isValidURL("https://audio.example/track.ogg"));
        assertFalse(TrackData.isValidURL("ftp://audio.example/track.mp3"));
        assertFalse(TrackData.isValidURL("not a resource location"));
        assertFalse(TrackData.isValidURL(null));
    }

}
