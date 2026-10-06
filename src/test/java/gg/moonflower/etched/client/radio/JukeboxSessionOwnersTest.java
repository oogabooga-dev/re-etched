package gg.moonflower.etched.client.radio;

import gg.moonflower.etched.common.audio.AudioProgram;
import gg.moonflower.etched.common.audio.AudioTrack;
import gg.moonflower.etched.common.audio.PlaybackState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class JukeboxSessionOwnersTest {

    static {
        MinecraftTestBootstrap.bootStrap();
    }

    private static final PlaybackOwnerKey.BlockOwner KEY = PlaybackOwnerKey.block(Level.OVERWORLD, BlockPos.ZERO);
    private static final AudioProgram PROGRAM = new AudioProgram(AudioProgram.Kind.FINITE, List.of(
            new AudioTrack(AudioTrack.SourceType.SOUND_EVENT, "minecraft:music_disc.cat", "", "")));

    @Test
    void invalidOrUnloadedOwnersReleaseTheirExactManagedSession() {
        var manager = new AudioPlaybackManager(AudioPlaybackManager.PlaybackDriver.NOOP);
        var owners = new JukeboxSessionOwners();
        var state = new PlaybackState(1L, Optional.of(PROGRAM), true);
        manager.update(KEY, state);
        owners.remember(KEY, state);
        owners.prune(manager, key -> true);
        assertSame(state, manager.getPlaybackState(KEY).orElseThrow());
        owners.prune(manager, key -> false);
        assertTrue(manager.getPlaybackState(KEY).isEmpty());
        var restored = new PlaybackState(2L, Optional.of(PROGRAM), true);
        manager.update(KEY, restored);
        owners.remember(KEY, restored);
        owners.prune(manager, key -> true);
        assertSame(restored, manager.getPlaybackState(KEY).orElseThrow());
    }

    @Test
    void cleanupNeverClosesAnotherAdapterEvenIfItHasEqualContentAndRevision() {
        var manager = new AudioPlaybackManager(AudioPlaybackManager.PlaybackDriver.NOOP);
        var owners = new JukeboxSessionOwners();
        var state = new PlaybackState(1L, Optional.of(PROGRAM), true);
        manager.update(KEY, state);
        owners.remember(KEY, state);
        manager.remove(KEY);
        var other = new PlaybackState(1L, Optional.of(PROGRAM), true);
        manager.update(KEY, other);
        assertEquals(state, other);
        owners.prune(manager, key -> false);
        assertSame(other, manager.getPlaybackState(KEY).orElseThrow());
    }

    @Test
    void pruningIsOwnerSelectiveAndRetiredOrClearedEntriesCannotCloseFutureSessions() {
        var manager = new AudioPlaybackManager(AudioPlaybackManager.PlaybackDriver.NOOP);
        var owners = new JukeboxSessionOwners();
        var other = PlaybackOwnerKey.block(Level.NETHER, BlockPos.ZERO);
        for (var key : List.of(KEY, other)) {
            var state = new PlaybackState(1L, Optional.of(PROGRAM), true);
            manager.update(key, state);
            owners.remember(key, state);
        }
        owners.prune(manager, key -> key.dimension().equals(Level.NETHER));
        assertTrue(manager.getPlaybackState(KEY).isEmpty());
        assertTrue(manager.getPlaybackState(other).isPresent());
        owners.forget(other);
        owners.prune(manager, key -> false);
        assertTrue(manager.getPlaybackState(other).isPresent());
        owners.remember(other, manager.getPlaybackState(other).orElseThrow());
        owners.clearAll();
        owners.prune(manager, key -> false);
        assertTrue(manager.getPlaybackState(other).isPresent());
    }

    @Test
    void everyRetiredSessionCompactsItsWatermarkWithoutClosingOtherAdapters() {
        var manager = new AudioPlaybackManager(AudioPlaybackManager.PlaybackDriver.NOOP);
        var owners = new JukeboxSessionOwners();
        var revisions = new JukeboxRevisionGate();
        var state = new PlaybackState(10L, Optional.of(PROGRAM), true);
        assertTrue(revisions.accept(KEY, 10L));
        manager.update(KEY, state);
        owners.remember(KEY, state);
        owners.prune(manager, key -> false, revisions::release);
        assertFalse(revisions.accept(KEY, 10L));
        assertTrue(revisions.accept(KEY, 11L));
        manager.update(KEY, new PlaybackState(11L, Optional.of(PROGRAM), true));
        owners.remember(KEY, manager.getPlaybackState(KEY).orElseThrow());
        manager.remove(KEY);
        var other = new PlaybackState(12L, Optional.of(PROGRAM), true);
        manager.update(KEY, other);
        owners.prune(manager, key -> true, revisions::release);
        assertFalse(revisions.accept(KEY, 11L));
        assertSame(other, manager.getPlaybackState(KEY).orElseThrow());
        assertTrue(revisions.accept(KEY, 12L));
    }
}
