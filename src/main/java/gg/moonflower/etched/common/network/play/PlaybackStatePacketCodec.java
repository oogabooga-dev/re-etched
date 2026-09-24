package gg.moonflower.etched.common.network.play;

import gg.moonflower.etched.common.audio.AudioProgram;
import gg.moonflower.etched.common.audio.AudioTrack;
import gg.moonflower.etched.common.audio.PlaybackState;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.network.FriendlyByteBuf;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Bounded wire encoding for server-authoritative playback state. */
public final class PlaybackStatePacketCodec {

    private static final int PROGRAM_PRESENT_FLAG = 1;
    private static final int ENABLED_FLAG = 1 << 1;
    private static final int KNOWN_FLAGS = PROGRAM_PRESENT_FLAG | ENABLED_FLAG;

    private static final int FINITE_PROGRAM_ID = 0;
    private static final int LIVE_PROGRAM_ID = 1;
    private static final int SOUND_EVENT_SOURCE_ID = 0;
    private static final int REMOTE_SOURCE_ID = 1;
    private static final int MAX_UTF8_BYTES_PER_CHARACTER = 3;

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

        if (state.program().isEmpty()) {
            return;
        }
        AudioProgram program = state.program().orElseThrow();
        buffer.writeByte(switch (program.kind()) {
            case FINITE -> FINITE_PROGRAM_ID;
            case LIVE -> LIVE_PROGRAM_ID;
        });
        buffer.writeVarInt(program.tracks().size());
        for (AudioTrack track : program.tracks()) {
            buffer.writeByte(switch (track.sourceType()) {
                case SOUND_EVENT -> SOUND_EVENT_SOURCE_ID;
                case REMOTE -> REMOTE_SOURCE_ID;
            });
            int sourceLimit = track.sourceType() == AudioTrack.SourceType.SOUND_EVENT
                    ? AudioTrack.MAX_SOUND_EVENT_LENGTH : AudioTrack.MAX_REMOTE_SOURCE_LENGTH;
            writeUtf(buffer, track.source(), sourceLimit);
            writeUtf(buffer, track.artist(), AudioTrack.MAX_METADATA_LENGTH);
            writeUtf(buffer, track.title(), AudioTrack.MAX_METADATA_LENGTH);
        }
    }

    public static PlaybackState read(FriendlyByteBuf buffer) {
        Objects.requireNonNull(buffer, "buffer");
        try {
            return readPayload(buffer);
        } catch (DecoderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new DecoderException("Invalid playback state payload", exception);
        }
    }

    private static PlaybackState readPayload(FriendlyByteBuf buffer) {
        long revision = buffer.readLong();
        int flags = buffer.readUnsignedByte();
        if ((flags & ~KNOWN_FLAGS) != 0) {
            throw invalid("Unknown playback state flags: " + flags);
        }

        boolean hasProgram = (flags & PROGRAM_PRESENT_FLAG) != 0;
        boolean enabled = (flags & ENABLED_FLAG) != 0;
        if (!hasProgram) {
            if (enabled) {
                throw invalid("Enabled playback must contain an audio program");
            }
            return new PlaybackState(revision, Optional.empty(), false);
        }

        AudioProgram.Kind kind = switch (buffer.readUnsignedByte()) {
            case FINITE_PROGRAM_ID -> AudioProgram.Kind.FINITE;
            case LIVE_PROGRAM_ID -> AudioProgram.Kind.LIVE;
            default -> throw invalid("Unknown audio program kind");
        };
        int trackCount = buffer.readVarInt();
        if (trackCount < 1 || trackCount > AudioProgram.MAX_TRACKS) {
            throw invalid("Audio program must contain between 1 and "
                    + AudioProgram.MAX_TRACKS + " tracks");
        }

        List<AudioTrack> tracks = new ArrayList<>(trackCount);
        long totalTextLength = 0;
        for (int i = 0; i < trackCount; i++) {
            AudioTrack.SourceType sourceType = switch (buffer.readUnsignedByte()) {
                case SOUND_EVENT_SOURCE_ID -> AudioTrack.SourceType.SOUND_EVENT;
                case REMOTE_SOURCE_ID -> AudioTrack.SourceType.REMOTE;
                default -> throw invalid("Unknown audio source type for track " + i);
            };
            int sourceLimit = sourceType == AudioTrack.SourceType.SOUND_EVENT
                    ? AudioTrack.MAX_SOUND_EVENT_LENGTH : AudioTrack.MAX_REMOTE_SOURCE_LENGTH;
            String source = readUtf(buffer, sourceLimit);
            String artist = readUtf(buffer, AudioTrack.MAX_METADATA_LENGTH);
            String title = readUtf(buffer, AudioTrack.MAX_METADATA_LENGTH);
            AudioTrack track = new AudioTrack(sourceType, source, artist, title);
            totalTextLength += track.source().length() + track.artist().length() + track.title().length();
            if (totalTextLength > AudioProgram.MAX_TOTAL_TEXT_LENGTH) {
                throw invalid("Audio program text exceeds "
                        + AudioProgram.MAX_TOTAL_TEXT_LENGTH + " characters");
            }
            tracks.add(track);
        }

        AudioProgram program = new AudioProgram(kind, tracks);
        return new PlaybackState(revision, Optional.of(program), enabled);
    }

    private static void writeUtf(FriendlyByteBuf buffer, String value, int maximumLength) {
        if (value.length() > maximumLength) {
            throw new EncoderException("String exceeds " + maximumLength + " characters");
        }

        ByteBuffer encoded;
        try {
            encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
        } catch (CharacterCodingException exception) {
            throw new EncoderException("String is not well-formed UTF-16", exception);
        }
        int byteLength = encoded.remaining();
        if (byteLength > maximumLength * MAX_UTF8_BYTES_PER_CHARACTER) {
            throw new EncoderException("Encoded string exceeds " + maximumLength + " characters");
        }
        buffer.writeVarInt(byteLength);
        buffer.writeBytes(encoded);
    }

    private static String readUtf(FriendlyByteBuf buffer, int maximumLength) {
        int byteLength = buffer.readVarInt();
        if (byteLength < 0 || byteLength > maximumLength * MAX_UTF8_BYTES_PER_CHARACTER) {
            throw invalid("Invalid encoded string length: " + byteLength);
        }
        byte[] encoded = new byte[byteLength];
        buffer.readBytes(encoded);

        String value;
        try {
            value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(encoded))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new DecoderException("String is not well-formed UTF-8", exception);
        }
        if (value.length() > maximumLength) {
            throw invalid("String exceeds " + maximumLength + " characters");
        }
        return value;
    }

    private static DecoderException invalid(String message) {
        return new DecoderException(message);
    }
}
