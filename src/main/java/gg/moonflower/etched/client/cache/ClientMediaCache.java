package gg.moonflower.etched.client.cache;

import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.UncheckedIOException;

/** Client-only v5 cache root; legacy cache directories are never opened or migrated. */
public final class ClientMediaCache {

    private ClientMediaCache() {
    }

    public static BoundedMediaCache get() {
        return Holder.INSTANCE;
    }

    private static final class Holder {
        private static final BoundedMediaCache INSTANCE = create();

        private static BoundedMediaCache create() {
            try {
                return new BoundedMediaCache(Minecraft.getInstance().gameDirectory.toPath()
                        .resolve("etched-media-cache-v5"));
            } catch (IOException exception) {
                throw new UncheckedIOException("Could not open the media cache", exception);
            }
        }
    }
}
