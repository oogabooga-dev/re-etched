package gg.moonflower.etched.common.blockentity;

import gg.moonflower.etched.common.audio.AudioNbtCodec;
import gg.moonflower.etched.common.audio.AudioProgram;
import gg.moonflower.etched.common.audio.AudioTrack;
import gg.moonflower.etched.common.audio.PlaybackRevision;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Stores a radio station separately from whether manual playback is enabled. */
final class RadioControlState {

    private static final String STATION_TAG = "Station";
    private static final String ENABLED_TAG = "Enabled";
    private static final String REVISION_TAG = "PlaybackRevision";

    private AudioProgram station;
    private boolean enabled;
    private long revision;
    private boolean initialized;

    boolean load(CompoundTag nbt) {
        AudioProgram loadedStation = readStation(nbt).orElse(null);
        boolean loadedEnabled = loadedStation != null && nbt.contains(ENABLED_TAG, Tag.TAG_BYTE)
                && nbt.getBoolean(ENABLED_TAG);
        long loadedRevision = nbt.contains(REVISION_TAG, Tag.TAG_LONG) ? nbt.getLong(REVISION_TAG) : 0L;
        if (this.initialized && (loadedRevision == this.revision
                || !PlaybackRevision.isNewer(loadedRevision, this.revision))) {
            return false;
        }

        this.station = loadedStation;
        this.enabled = loadedEnabled;
        this.revision = loadedRevision;
        this.initialized = true;
        return true;
    }

    void save(CompoundTag nbt) {
        if (this.station != null) {
            nbt.put(STATION_TAG, AudioNbtCodec.write(this.station));
        }
        nbt.putBoolean(ENABLED_TAG, this.enabled);
        nbt.putLong(REVISION_TAG, this.revision);
    }

    boolean apply(String url) {
        String normalized = normalize(url);
        if (normalized == null) {
            if (!this.enabled) {
                return false;
            }
            this.enabled = false;
            this.advanceRevision();
            return true;
        }

        AudioProgram updatedStation = station(normalized);
        if (this.enabled && Objects.equals(this.station, updatedStation)) {
            return false;
        }
        this.station = updatedStation;
        this.enabled = true;
        this.advanceRevision();
        return true;
    }

    boolean clear() {
        if (this.station == null && !this.enabled) {
            return false;
        }
        this.station = null;
        this.enabled = false;
        this.advanceRevision();
        return true;
    }

    String storedUrl() {
        return this.station == null ? null : this.station.tracks().get(0).source();
    }

    Optional<AudioProgram> station() {
        return Optional.ofNullable(this.station);
    }

    boolean enabled() {
        return this.enabled;
    }

    long revision() {
        return this.revision;
    }

    void advanceRevision() {
        this.revision = PlaybackRevision.next(this.revision);
        this.initialized = true;
    }

    private static String normalize(String url) {
        return url == null || url.isBlank() ? null : url.trim();
    }

    private static AudioProgram station(String url) {
        return new AudioProgram(AudioProgram.Kind.LIVE, List.of(
                new AudioTrack(AudioTrack.SourceType.REMOTE, url, "", "")));
    }

    private static Optional<AudioProgram> readStation(CompoundTag nbt) {
        if (!(nbt.get(STATION_TAG) instanceof CompoundTag stationTag)) {
            return Optional.empty();
        }
        return AudioNbtCodec.readProgram(stationTag).result()
                .filter(program -> program.kind() == AudioProgram.Kind.LIVE);
    }
}
