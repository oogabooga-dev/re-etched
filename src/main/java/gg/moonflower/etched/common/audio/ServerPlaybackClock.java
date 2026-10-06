package gg.moonflower.etched.common.audio;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Server-thread revision allocator shared across dimensions and block/entity incarnations. */
public final class ServerPlaybackClock extends SavedData {

    private static final String DATA_NAME = "etched_playback_clock";
    private static final String REVISION_TAG = "Revision";

    private long revision;

    public static ServerPlaybackClock get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage()
                .computeIfAbsent(ServerPlaybackClock::load, ServerPlaybackClock::new, DATA_NAME);
    }

    static ServerPlaybackClock load(CompoundTag tag) {
        ServerPlaybackClock clock = new ServerPlaybackClock();
        clock.revision = tag.contains(REVISION_TAG, Tag.TAG_LONG) ? tag.getLong(REVISION_TAG) : 0L;
        return clock;
    }

    public long current() {
        return this.revision;
    }

    public long next() {
        this.revision = PlaybackRevision.next(this.revision);
        this.setDirty();
        return this.revision;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putLong(REVISION_TAG, this.revision);
        return tag;
    }
}
