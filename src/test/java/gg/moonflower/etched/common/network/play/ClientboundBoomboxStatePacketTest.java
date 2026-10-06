package gg.moonflower.etched.common.network.play;

import gg.moonflower.etched.common.audio.AudioProgram;
import gg.moonflower.etched.client.radio.MinecraftTestBootstrap;
import gg.moonflower.etched.common.audio.AudioTrack;
import gg.moonflower.etched.common.audio.PlaybackState;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ClientboundBoomboxStatePacketTest {

    static {
        MinecraftTestBootstrap.bootStrap();
    }

    private static final UUID OWNER = new UUID(1234, 5678);
    private static final AudioTrack TRACK = new AudioTrack(AudioTrack.SourceType.SOUND_EVENT, "minecraft:music_disc.cat", "", "Cat");
    private static final AudioProgram FINITE = new AudioProgram(AudioProgram.Kind.FINITE, List.of(TRACK));

    @Test
    void roundTripsExactIdentityFiniteStateAndStopsIncludingLongExtrema() {
        for (long revision : List.of(0L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            for (var state : List.of(new PlaybackState(revision, Optional.of(FINITE), true),
                    new PlaybackState(revision, Optional.empty(), false))) {
                var packet = new ClientboundBoomboxStatePacket(Level.NETHER, Integer.MAX_VALUE, OWNER, state);
                assertEquals(packet, decode(write(packet::writePacketData)));
            }
        }
    }

    @Test
    void acceptsExactDimensionAndTrackBoundaries() {
        var dimension = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("test:" + "x".repeat(251)));
        var maximum = new AudioProgram(AudioProgram.Kind.FINITE, Collections.nCopies(AudioProgram.MAX_TRACKS, TRACK));
        var packet = new ClientboundBoomboxStatePacket(dimension, 0, OWNER, new PlaybackState(1L, Optional.of(maximum), true));
        assertEquals(packet, decode(write(packet::writePacketData)));
    }

    @Test
    void rejectsLiveDisabledProgramsNegativeEntityIdsAndOversizeDimensions() {
        var live = new AudioProgram(AudioProgram.Kind.LIVE, List.of(new AudioTrack(AudioTrack.SourceType.REMOTE, "https://radio.example/live", "", "")));
        assertThrows(IllegalArgumentException.class, () -> packet(Level.OVERWORLD, 1, new PlaybackState(1L, Optional.of(live), true)));
        assertThrows(IllegalArgumentException.class, () -> packet(Level.OVERWORLD, 1, new PlaybackState(1L, Optional.of(FINITE), false)));
        assertThrows(IllegalArgumentException.class, () -> packet(Level.OVERWORLD, -1, new PlaybackState(1L, Optional.empty(), false)));
        assertThrows(IllegalArgumentException.class, () -> packet(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse("test:" + "x".repeat(256))), 1, new PlaybackState(1L, Optional.empty(), false)));
    }

    @Test
    void decoderRejectsBadIdentityUtfStateFlagsAndOversizePrograms() {
        rejected(buf -> buf.writeUtf("bad dimension"));
        rejected(buf -> buf.writeUtf("test:" + "x".repeat(256)));
        rejected(buf -> buf.writeVarInt(2).writeByte(0xC3).writeByte(0x28));
        rejected(buf -> { header(buf, -1); PlaybackStatePacketCodec.write(buf, new PlaybackState(1L, Optional.empty(), false)); });
        rejected(buf -> { header(buf, 1); buf.writeLong(1L).writeByte(4); });
        rejected(buf -> { header(buf, 1); buf.writeLong(1L).writeByte(3).writeByte(0); buf.writeVarInt(AudioProgram.MAX_TRACKS + 1); });
        rejected(buf -> { header(buf, 1); PlaybackStatePacketCodec.write(buf, new PlaybackState(1L, Optional.of(FINITE), false)); });
        rejected(buf -> {
            header(buf, 1);
            PlaybackStatePacketCodec.write(buf, new PlaybackState(1L, Optional.of(new AudioProgram(AudioProgram.Kind.LIVE,
                    List.of(new AudioTrack(AudioTrack.SourceType.REMOTE, "https://radio.example/live", "", "")))), true));
        });
    }

    @Test
    void everyTruncatedFiniteOrStopPayloadFails() {
        for (var state : List.of(new PlaybackState(1L, Optional.of(FINITE), true), new PlaybackState(2L, Optional.empty(), false))) {
            byte[] bytes = write(packet(Level.OVERWORLD, 1, state)::writePacketData);
            for (int length = 0; length < bytes.length; length++) {
                byte[] prefix = Arrays.copyOf(bytes, length);
                assertThrows(DecoderException.class, () -> decode(prefix), "prefix " + length);
            }
        }
    }

    private static ClientboundBoomboxStatePacket packet(ResourceKey<Level> dimension, int entityId, PlaybackState state) {
        return new ClientboundBoomboxStatePacket(dimension, entityId, OWNER, state);
    }

    private static void header(FriendlyByteBuf buf, int id) {
        buf.writeUtf("minecraft:overworld").writeVarInt(id).writeUUID(OWNER);
    }

    private static byte[] write(Consumer<FriendlyByteBuf> writer) {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try { writer.accept(buffer); return ByteBufUtil.getBytes(buffer); }
        finally { buffer.release(); }
    }

    private static ClientboundBoomboxStatePacket decode(byte[] bytes) {
        var buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        try {
            var result = new ClientboundBoomboxStatePacket(buffer);
            assertEquals(0, buffer.readableBytes());
            return result;
        } finally { buffer.release(); }
    }

    private static void rejected(Consumer<FriendlyByteBuf> writer) {
        assertThrows(DecoderException.class, () -> decode(write(writer)));
    }
}
