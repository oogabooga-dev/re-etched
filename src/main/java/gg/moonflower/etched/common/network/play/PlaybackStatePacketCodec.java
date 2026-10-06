package gg.moonflower.etched.common.network.play;

import gg.moonflower.etched.common.audio.PlaybackState;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;

import java.util.Objects;
import java.util.Optional;

/** Bounded wire encoding for server-authoritative playback state. */
public final class PlaybackStatePacketCodec {

    private static final int PROGRAM_PRESENT_FLAG = 1;
    private static final int ENABLED_FLAG = 1 << 1;
    private static final int KNOWN_FLAGS = PROGRAM_PRESENT_FLAG | ENABLED_FLAG;

    private PlaybackStatePacketCodec() {
    }

    public static void write(FriendlyByteBuf buffer, PlaybackState state) {
        Objects.requireNonNull(buffer, "buffer");
        Objects.requireNonNull(state, "state");
        buffer.writeLong(state.revision());
        int flags = state.program().isPresent() ? PROGRAM_PRESENT_FLAG : 0;
        if (state.enabled()) {
            flags |= ENABLED_FLAG;
        }
        buffer.writeByte(flags);
        state.program().ifPresent(program -> AudioProgramPacketCodec.write(buffer, program));
    }

    public static PlaybackState read(FriendlyByteBuf buffer) {
        Objects.requireNonNull(buffer, "buffer");
        try {
            long revision = buffer.readLong();
            int flags = buffer.readUnsignedByte();
            if ((flags & ~KNOWN_FLAGS) != 0) {
                throw new DecoderException("Unknown playback state flags: " + flags);
            }
            boolean enabled = (flags & ENABLED_FLAG) != 0;
            if ((flags & PROGRAM_PRESENT_FLAG) == 0) {
                if (enabled) {
                    throw new DecoderException("Enabled playback must contain an audio program");
                }
                return new PlaybackState(revision, Optional.empty(), false);
            }
            return new PlaybackState(revision, Optional.of(AudioProgramPacketCodec.read(buffer)), enabled);
        } catch (DecoderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new DecoderException("Invalid playback state payload", exception);
        }
    }
}
