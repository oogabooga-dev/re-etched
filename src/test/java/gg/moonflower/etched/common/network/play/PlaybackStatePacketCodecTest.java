package gg.moonflower.etched.common.network.play;

import gg.moonflower.etched.common.audio.AudioProgram;
import gg.moonflower.etched.common.audio.AudioTrack;
import gg.moonflower.etched.common.audio.PlaybackState;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlaybackStatePacketCodecTest {

    @Test
    void roundTripsEmptyFiniteAndLiveStates() {
        AudioTrack local = new AudioTrack(AudioTrack.SourceType.SOUND_EVENT,
                "minecraft:music_disc.13", "C418", "13");
        AudioTrack remote = new AudioTrack(AudioTrack.SourceType.REMOTE,
                "https://audio.example/track.mp3", "Исполнитель", "曲");
        AudioProgram finite = new AudioProgram(AudioProgram.Kind.FINITE, List.of(local, remote));
        AudioProgram live = new AudioProgram(AudioProgram.Kind.LIVE, List.of(
                new AudioTrack(AudioTrack.SourceType.REMOTE,
                        "https://radio.example/live", "", "")));

        assertEquals(new PlaybackState(Long.MIN_VALUE, Optional.empty(), false),
                roundTrip(new PlaybackState(Long.MIN_VALUE, Optional.empty(), false)));
        assertEquals(new PlaybackState(12L, Optional.of(finite), false),
                roundTrip(new PlaybackState(12L, Optional.of(finite), false)));
        assertEquals(new PlaybackState(Long.MAX_VALUE, Optional.of(live), true),
                roundTrip(new PlaybackState(Long.MAX_VALUE, Optional.of(live), true)));
    }

    @Test
    void acceptsMaximumTrackCountAndAggregateText() {
        AudioProgram maximumCount = new AudioProgram(AudioProgram.Kind.FINITE,
                Collections.nCopies(AudioProgram.MAX_TRACKS, localTrack()));
        AudioTrack maximumRemote = new AudioTrack(AudioTrack.SourceType.REMOTE,
                remoteSource(AudioTrack.MAX_REMOTE_SOURCE_LENGTH), "", "");
        AudioProgram maximumText = new AudioProgram(AudioProgram.Kind.FINITE,
                Collections.nCopies(8, maximumRemote));
        AudioProgram maximumFields = new AudioProgram(AudioProgram.Kind.FINITE, List.of(
                new AudioTrack(AudioTrack.SourceType.SOUND_EVENT,
                        "etched:" + "a".repeat(AudioTrack.MAX_SOUND_EVENT_LENGTH - 7),
                        "a".repeat(AudioTrack.MAX_METADATA_LENGTH),
                        "t".repeat(AudioTrack.MAX_METADATA_LENGTH))));

        assertEquals(maximumCount, roundTrip(state(maximumCount)).program().orElseThrow());
        assertEquals(maximumText, roundTrip(state(maximumText)).program().orElseThrow());
        assertEquals(maximumFields, roundTrip(state(maximumFields)).program().orElseThrow());
    }

    @Test
    void pinsPlaybackStateWireLayout() {
        assertWire("000000000000000100",
                new PlaybackState(1L, Optional.empty(), false));
        assertWire("0000000000000002010001000e6d696e6563726166743a746573740000",
                new PlaybackState(2L, Optional.of(new AudioProgram(AudioProgram.Kind.FINITE, List.of(
                        new AudioTrack(AudioTrack.SourceType.SOUND_EVENT,
                                "minecraft:test", "", "")))), false));
        assertWire("ffffffffffffffff030101010c68747470733a2f2f612e636f0000",
                new PlaybackState(-1L, Optional.of(new AudioProgram(AudioProgram.Kind.LIVE, List.of(
                        new AudioTrack(AudioTrack.SourceType.REMOTE, "https://a.co", "", "")))), true));
    }

    @Test
    void rejectsUnknownFlagsAndDiscriminators() {
        assertRejected(buffer -> header(buffer, 4));
        assertRejected(buffer -> header(buffer, 2));
        assertRejected(buffer -> {
            header(buffer, 1);
            buffer.writeByte(2);
        });
        assertRejected(buffer -> {
            programHeader(buffer, 0, 1);
            buffer.writeByte(2);
        });
    }

    @Test
    void rejectsInvalidAndMalformedTrackCounts() {
        assertRejected(buffer -> programHeader(buffer, 0, 0));
        assertRejected(buffer -> programHeader(buffer, 0, -1));
        assertRejected(buffer -> programHeader(buffer, 0, AudioProgram.MAX_TRACKS + 1));
        assertRejected(buffer -> {
            header(buffer, 1);
            buffer.writeByte(0);
            for (int i = 0; i < 5; i++) {
                buffer.writeByte(0x80);
            }
        });
    }

    @Test
    void rejectsOversizedFieldsAndAggregateText() {
        assertRejected(buffer -> writeSingleTrack(buffer, 1,
                remoteSource(AudioTrack.MAX_REMOTE_SOURCE_LENGTH + 1), "", ""));
        assertRejected(buffer -> writeSingleTrack(buffer, 0,
                "etched:" + "a".repeat(AudioTrack.MAX_SOUND_EVENT_LENGTH), "", ""));
        assertRejected(buffer -> writeSingleTrack(buffer, 1,
                "https://audio.example/track", "a".repeat(AudioTrack.MAX_METADATA_LENGTH + 1), ""));
        assertRejected(buffer -> {
            programHeader(buffer, 0, 9);
            for (int i = 0; i < 9; i++) {
                writeTrack(buffer, 1, remoteSource(AudioTrack.MAX_REMOTE_SOURCE_LENGTH), "", "");
            }
        });
    }

    @Test
    void rejectsInvalidModelInvariantsAndTruncatedPayloads() {
        assertRejected(buffer -> writeSingleTrack(buffer, 1, "ftp://audio.example/track", "", ""));
        assertRejected(buffer -> writeSingleTrack(buffer, 0, "Invalid Sound", "", ""));
        assertRejected(buffer -> writeSingleTrack(buffer, 0, "minecraft:music_disc.13", "", "", 1));
        assertRejected(buffer -> {
            programHeader(buffer, 1, 2);
            writeTrack(buffer, 1, "https://audio.example/one", "", "");
            writeTrack(buffer, 1, "https://audio.example/two", "", "");
        });
        assertRejected(buffer -> header(buffer, 1));
        assertRejected(buffer -> {
        });
    }

    @Test
    void rejectsMalformedUnicodeOnWriteAndRead() {
        AudioProgram malformedMetadata = new AudioProgram(AudioProgram.Kind.FINITE, List.of(
                new AudioTrack(AudioTrack.SourceType.SOUND_EVENT,
                        "minecraft:music_disc.13", "\uD800", "")));
        assertThrows(EncoderException.class,
                () -> write(buffer -> PlaybackStatePacketCodec.write(buffer, state(malformedMetadata))));

        assertRejected(buffer -> {
            programHeader(buffer, 0, 1);
            buffer.writeByte(0);
            buffer.writeVarInt(1);
            buffer.writeByte(0xFF);
        });
    }

    private static PlaybackState state(AudioProgram program) {
        return new PlaybackState(7L, Optional.of(program), true);
    }

    private static PlaybackState roundTrip(PlaybackState state) {
        return decode(write(buffer -> PlaybackStatePacketCodec.write(buffer, state)));
    }

    private static void assertWire(String hex, PlaybackState state) {
        byte[] expected = ByteBufUtil.decodeHexDump(hex);
        assertArrayEquals(expected, write(buffer -> PlaybackStatePacketCodec.write(buffer, state)));
        assertEquals(state, decode(expected));
    }

    private static PlaybackState decode(byte[] encoded) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(encoded));
        try {
            PlaybackState decoded = PlaybackStatePacketCodec.read(buffer);
            assertEquals(0, buffer.readableBytes());
            return decoded;
        } finally {
            buffer.release();
        }
    }

    private static void assertRejected(Consumer<FriendlyByteBuf> writer) {
        byte[] encoded = write(writer);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(encoded));
        try {
            assertThrows(DecoderException.class, () -> PlaybackStatePacketCodec.read(buffer));
        } finally {
            buffer.release();
        }
    }

    private static byte[] write(Consumer<FriendlyByteBuf> writer) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            writer.accept(buffer);
            return ByteBufUtil.getBytes(buffer);
        } finally {
            buffer.release();
        }
    }

    private static void header(FriendlyByteBuf buffer, int flags) {
        buffer.writeLong(0L);
        buffer.writeByte(flags);
    }

    private static void programHeader(FriendlyByteBuf buffer, int kind, int trackCount) {
        header(buffer, 1);
        buffer.writeByte(kind);
        buffer.writeVarInt(trackCount);
    }

    private static void writeSingleTrack(FriendlyByteBuf buffer, int sourceType,
                                         String source, String artist, String title) {
        writeSingleTrack(buffer, sourceType, source, artist, title, 0);
    }

    private static void writeSingleTrack(FriendlyByteBuf buffer, int sourceType,
                                         String source, String artist, String title, int kind) {
        programHeader(buffer, kind, 1);
        writeTrack(buffer, sourceType, source, artist, title);
    }

    private static void writeTrack(FriendlyByteBuf buffer, int sourceType,
                                   String source, String artist, String title) {
        buffer.writeByte(sourceType);
        buffer.writeUtf(source);
        buffer.writeUtf(artist);
        buffer.writeUtf(title);
    }

    private static AudioTrack localTrack() {
        return new AudioTrack(AudioTrack.SourceType.SOUND_EVENT,
                "minecraft:music_disc.13", "", "");
    }

    private static String remoteSource(int length) {
        String prefix = "https://audio.example/";
        return prefix + "a".repeat(length - prefix.length());
    }
}
