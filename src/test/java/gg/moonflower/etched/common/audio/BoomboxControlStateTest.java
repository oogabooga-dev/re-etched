package gg.moonflower.etched.common.audio;

import gg.moonflower.etched.client.radio.MinecraftTestBootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class BoomboxControlStateTest {

    static {
        MinecraftTestBootstrap.bootStrap();
    }

    @Test
    void unchangedEmptyInvalidAndCosmeticRecordsDoNotAllocateRevisions() {
        var clock = new AtomicLong(100L);
        var control = new BoomboxControlState();
        assertTrue(control.observe(ItemStack.EMPTY, clock::incrementAndGet).isEmpty());
        assertTrue(control.observe(new ItemStack(Items.PAPER), clock::incrementAndGet).isEmpty());
        var cat = new ItemStack(Items.MUSIC_DISC_CAT);
        assertEquals(101L, control.observe(cat, clock::incrementAndGet).orElseThrow().revision());
        assertTrue(control.observe(cat.copy(), clock::incrementAndGet).isEmpty());
        cat.getOrCreateTag().putString("Cosmetic", "changed");
        assertTrue(control.observe(cat, clock::incrementAndGet).isEmpty());
        assertEquals(101L, clock.get());
    }

    @Test
    void replacementStopAndResumeUseOnlySuppliedServerRevisions() {
        var clock = new AtomicLong(500L);
        var control = new BoomboxControlState();
        control.observe(new ItemStack(Items.MUSIC_DISC_CAT), clock::incrementAndGet);
        var replaced = control.observe(new ItemStack(Items.MUSIC_DISC_BLOCKS), clock::incrementAndGet).orElseThrow();
        assertEquals(502L, replaced.revision());
        assertEquals("minecraft:music_disc.blocks", replaced.program().orElseThrow().tracks().get(0).source());
        var stopped = control.observe(ItemStack.EMPTY, clock::incrementAndGet).orElseThrow();
        assertEquals(503L, stopped.revision());
        assertFalse(stopped.enabled());
        assertTrue(stopped.program().isEmpty());
        assertTrue(control.observe(ItemStack.EMPTY, clock::incrementAndGet).isEmpty());
        assertEquals(504L, control.observe(new ItemStack(Items.MUSIC_DISC_CAT), clock::incrementAndGet).orElseThrow().revision());
    }

    @Test
    void unsupportedReplacementStopsInsteadOfFallingBackAndDoesNotRepeatedlyAllocate() {
        var clock = new AtomicLong();
        var control = new BoomboxControlState();
        control.observe(new ItemStack(Items.MUSIC_DISC_CAT), clock::incrementAndGet);
        assertFalse(control.observe(new ItemStack(Items.PAPER), clock::incrementAndGet).orElseThrow().enabled());
        assertFalse(control.enabled());
        assertTrue(control.observe(new ItemStack(Items.STONE), clock::incrementAndGet).isEmpty());
        assertEquals(2L, clock.get());
    }

    @Test
    void snapshotsGetFreshServerPublicationsWithoutChangingTheObservedProgram() {
        var clock = new AtomicLong();
        var control = new BoomboxControlState();
        var disc = new ItemStack(Items.MUSIC_DISC_CAT);
        var started = control.observe(disc, clock::incrementAndGet).orElseThrow();
        var firstPeer = control.snapshot(clock::incrementAndGet);
        var secondPeer = control.snapshot(clock::incrementAndGet);
        assertEquals(started.program(), firstPeer.program());
        assertEquals(firstPeer.program(), secondPeer.program());
        assertEquals(2L, firstPeer.revision());
        assertEquals(3L, secondPeer.revision());
        assertTrue(control.observe(disc, clock::incrementAndGet).isEmpty());
        assertEquals(4L, control.observe(ItemStack.EMPTY, clock::incrementAndGet).orElseThrow().revision());
    }

    @Test
    void suppliedRevisionClockCanWrapWithoutLosingOrdering() {
        var clock = new AtomicLong(Long.MAX_VALUE - 1L);
        var control = new BoomboxControlState();
        var start = control.observe(new ItemStack(Items.MUSIC_DISC_CAT), clock::incrementAndGet).orElseThrow();
        var stop = control.observe(ItemStack.EMPTY, clock::incrementAndGet).orElseThrow();
        assertEquals(Long.MIN_VALUE, stop.revision());
        assertTrue(PlaybackRevision.isNewer(stop.revision(), start.revision()));
    }
}
