package gg.moonflower.etched.common.item;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/** Keeps common boombox item code from loading client playback classes on a server. */
public final class BoomboxClientBridge {

    private static volatile Listener listener = Listener.NOOP;

    private BoomboxClientBridge() {
    }

    public static void install(Listener listener) {
        BoomboxClientBridge.listener = Objects.requireNonNull(listener, "listener");
    }

    public static void update(Entity entity, ItemStack record) {
        if (entity.level().isClientSide()) {
            listener.update(entity, record);
        }
    }

    public static boolean isPlaying(Entity entity) {
        return entity.level().isClientSide() && listener.isPlaying(entity);
    }

    public interface Listener {

        Listener NOOP = new Listener() {
            @Override
            public void update(Entity entity, ItemStack record) {
            }

            @Override
            public boolean isPlaying(Entity entity) {
                return false;
            }
        };

        void update(Entity entity, ItemStack record);

        boolean isPlaying(Entity entity);
    }
}
