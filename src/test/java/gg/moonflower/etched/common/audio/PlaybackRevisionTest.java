package gg.moonflower.etched.common.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybackRevisionTest {

    @Test
    void ordersSerialRevisionsAcrossLongWrap() {
        assertTrue(PlaybackRevision.isNewer(2L, 1L));
        assertFalse(PlaybackRevision.isNewer(1L, 1L));
        assertFalse(PlaybackRevision.isNewer(1L, 2L));
        assertTrue(PlaybackRevision.isNewer(Long.MIN_VALUE, Long.MAX_VALUE));
        assertEquals(Long.MIN_VALUE, PlaybackRevision.next(Long.MAX_VALUE));
    }

    @Test
    void rejectsAmbiguousHalfRange() {
        assertFalse(PlaybackRevision.isNewer(Long.MIN_VALUE, 0L));
        assertFalse(PlaybackRevision.isNewer(0L, Long.MIN_VALUE));
    }
}
