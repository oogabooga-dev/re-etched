package gg.moonflower.etched.client.radio;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Objects;
import java.util.UUID;

/** Identifies the client-side owner of a playback session. */
public sealed interface PlaybackOwnerKey {

    ResourceKey<Level> dimension();

    static BlockOwner block(ResourceKey<Level> dimension, BlockPos pos) {
        return new BlockOwner(dimension, pos);
    }

    static EntityOwner entity(ResourceKey<Level> dimension, UUID uuid) {
        return new EntityOwner(dimension, uuid);
    }

    record BlockOwner(ResourceKey<Level> dimension, BlockPos pos) implements PlaybackOwnerKey {

        public BlockOwner {
            Objects.requireNonNull(dimension, "dimension");
            pos = Objects.requireNonNull(pos, "pos").immutable();
        }
    }

    record EntityOwner(ResourceKey<Level> dimension, UUID uuid) implements PlaybackOwnerKey {

        public EntityOwner {
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(uuid, "uuid");
        }
    }
}
