package gg.moonflower.etched.client.radio.sound;

import gg.moonflower.etched.client.radio.PlaybackOwnerKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.function.Supplier;

/** Resolves an entity once; never follows a reused numeric id or a changed client world. */
final class EntitySoundPosition {

    private EntitySoundPosition() {
    }

    static Supplier<Vec3> find(PlaybackOwnerKey.EntityOwner key) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !level.dimension().equals(key.dimension())) {
            throw new IllegalStateException("Entity playback world is unavailable");
        }
        for (Entity entity : level.entitiesForRendering()) {
            if (entity.getUUID().equals(key.uuid()) && entity.isAlive() && !entity.isRemoved()) {
                return () -> position(level, entity);
            }
        }
        throw new IllegalStateException("Entity playback owner is unavailable");
    }

    private static Vec3 position(ClientLevel level, Entity entity) {
        return Minecraft.getInstance().level == level && entity.isAlive() && !entity.isRemoved()
                && level.getEntity(entity.getId()) == entity ? entity.position() : null;
    }
}
