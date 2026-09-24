package gg.moonflower.etched.client.radio;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlaybackOwnerKeyTest {

    static {
        MinecraftTestBootstrap.bootStrap();
    }

    @Test
    void blockOwnerDefensivelyCopiesMutablePosition() {
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos(1, 2, 3);

        PlaybackOwnerKey.BlockOwner owner = PlaybackOwnerKey.block(Level.OVERWORLD, mutable);
        mutable.set(9, 8, 7);

        assertEquals(new BlockPos(1, 2, 3), owner.pos());
        assertNotSame(mutable, owner.pos());
    }

    @Test
    void equalBlockOwnersHaveValueEquality() {
        assertEquals(
                PlaybackOwnerKey.block(Level.OVERWORLD, new BlockPos(1, 2, 3)),
                PlaybackOwnerKey.block(Level.OVERWORLD, new BlockPos(1, 2, 3)));
    }

    @Test
    void dimensionSeparatesOwners() {
        BlockPos pos = new BlockPos(1, 2, 3);

        assertNotEquals(
                PlaybackOwnerKey.block(Level.OVERWORLD, pos),
                PlaybackOwnerKey.block(Level.NETHER, pos));
    }

    @Test
    void entityOwnerUsesUuidIdentity() {
        UUID first = UUID.fromString("12345678-1234-5678-1234-567812345678");
        UUID second = UUID.fromString("87654321-4321-8765-4321-876543218765");

        assertEquals(
                PlaybackOwnerKey.entity(Level.OVERWORLD, first),
                PlaybackOwnerKey.entity(Level.OVERWORLD, first));
        assertNotEquals(
                PlaybackOwnerKey.entity(Level.OVERWORLD, first),
                PlaybackOwnerKey.entity(Level.OVERWORLD, second));
        assertNotEquals(
                PlaybackOwnerKey.entity(Level.OVERWORLD, first),
                PlaybackOwnerKey.entity(Level.NETHER, first));
    }

    @Test
    void ownerKindIsPartOfEquality() {
        PlaybackOwnerKey block = PlaybackOwnerKey.block(Level.OVERWORLD, BlockPos.ZERO);
        PlaybackOwnerKey entity = PlaybackOwnerKey.entity(Level.OVERWORLD, new UUID(0L, 0L));

        assertNotEquals(block, entity);
    }

    @Test
    void rejectsNullComponents() {
        ResourceKey<Level> dimension = Level.OVERWORLD;

        assertThrows(NullPointerException.class, () -> PlaybackOwnerKey.block(null, BlockPos.ZERO));
        assertThrows(NullPointerException.class, () -> PlaybackOwnerKey.block(dimension, null));
        assertThrows(NullPointerException.class, () -> PlaybackOwnerKey.entity(null, new UUID(0L, 0L)));
        assertThrows(NullPointerException.class, () -> PlaybackOwnerKey.entity(dimension, null));
    }
}
