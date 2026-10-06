package gg.moonflower.etched.common.network.play;

import gg.moonflower.etched.client.radio.MinecraftTestBootstrap;
import gg.moonflower.etched.common.audio.AudioProgram;
import gg.moonflower.etched.common.audio.AudioTrack;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ClientboundPlayMusicPacketTest {

    static {
        MinecraftTestBootstrap.bootStrap();
    }

    @Test
    void roundTripsDimensionPositionDiscriminatorAndOrderedFiniteContent() {
        var program = new AudioProgram(AudioProgram.Kind.FINITE, List.of(local(), remote("https://audio.example/a.mp3")));
        var packet = new ClientboundPlayMusicPacket(Level.NETHER, new BlockPos(-12, 64, 345), 42, Optional.of(program));
        assertEquals(packet, decode(encode(packet)));
        assertEquals(new ClientboundPlayMusicPacket(Level.OVERWORLD, BlockPos.ZERO, 0, Optional.empty()),
                decode(encode(new ClientboundPlayMusicPacket(Level.OVERWORLD, BlockPos.ZERO, 0, Optional.empty()))));
    }

    @Test
    void payloadIgnoresInventoryCosmeticAndArbitraryNbtAndSnapshotsMutablePositions() {
        ItemStack disc = new ItemStack(Items.MUSIC_DISC_CAT);
        var pos = new BlockPos.MutableBlockPos(1, 2, 3);
        var plain = ClientboundPlayMusicPacket.fromRecord(Level.OVERWORLD, pos, disc);
        disc.getOrCreateTag().putString("Unrelated", "x".repeat(100_000));
        var decorated = ClientboundPlayMusicPacket.fromRecord(Level.OVERWORLD, pos, disc);
        assertArrayEquals(encode(plain), encode(decorated));
        pos.set(4, 5, 6);
        assertEquals(new BlockPos(1, 2, 3), plain.pos());
        assertTrue(encode(decorated).length < 256);
        assertTrue(ClientboundPlayMusicPacket.fromRecord(Level.OVERWORLD, BlockPos.ZERO, new ItemStack(Items.PAPER)).program().isEmpty());
    }

    @Test
    void modelRejectsLiveProgramsNegativeDiscriminatorsAndOversizeDimensions() {
        var live = new AudioProgram(AudioProgram.Kind.LIVE, List.of(remote("https://radio.example/live")));
        assertThrows(IllegalArgumentException.class, () -> new ClientboundPlayMusicPacket(Level.OVERWORLD, BlockPos.ZERO, 1, Optional.of(live)));
        assertThrows(IllegalArgumentException.class, () -> new ClientboundPlayMusicPacket(Level.OVERWORLD, BlockPos.ZERO, -1, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ClientboundPlayMusicPacket(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse("test:" + "x".repeat(256))), BlockPos.ZERO, 1, Optional.empty()));
    }

    @Test
    void acceptsExactDimensionTrackCountAndTotalTextBoundaries() {
        var dimension = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("test:" + "x".repeat(251)));
        var maximumCount = new AudioProgram(AudioProgram.Kind.FINITE, Collections.nCopies(AudioProgram.MAX_TRACKS, local()));
        var maximumText = new AudioProgram(AudioProgram.Kind.FINITE, Collections.nCopies(8,
                remote("https://audio.example/" + "x".repeat(AudioTrack.MAX_REMOTE_SOURCE_LENGTH - 22))));
        for (AudioProgram program : List.of(maximumCount, maximumText)) {
            var packet = new ClientboundPlayMusicPacket(dimension, BlockPos.ZERO, Integer.MAX_VALUE, Optional.of(program));
            assertEquals(packet, decode(encode(packet)));
        }
    }

    @Test
    void decoderRejectsBadDimensionDiscriminatorFlagsAndNonFiniteOrInvalidProgram() {
        assertRejected(buf -> { buf.writeUtf("bad dimension"); });
        assertRejected(buf -> { buf.writeUtf("test:" + "x".repeat(256)); });
        assertRejected(buf -> header(buf, -1, 0));
        assertRejected(buf -> header(buf, 1, 2));
        assertRejected(buf -> {
            header(buf, 1, 1);
            AudioProgramPacketCodec.write(buf, new AudioProgram(AudioProgram.Kind.LIVE, List.of(remote("https://radio.example/live"))));
        });
        assertRejected(buf -> {
            header(buf, 1, 1);
            buf.writeByte(0);
            buf.writeVarInt(1);
            buf.writeByte(1);
            buf.writeUtf("file:///private").writeUtf("").writeUtf("");
        });
        assertRejected(buf -> {
            header(buf, 1, 1);
            buf.writeByte(0);
            buf.writeVarInt(AudioProgram.MAX_TRACKS + 1);
        });
    }

    @Test
    void decoderRejectsOversizeFieldsTotalTextAndMalformedUtfInsteadOfPartialPlayback() {
        assertRejected(buf -> {
            header(buf, 1, 1);
            buf.writeByte(0);
            buf.writeVarInt(1);
            buf.writeByte(1);
            buf.writeUtf("https://audio.example/track").writeUtf("x".repeat(129)).writeUtf("");
        });
        assertRejected(buf -> {
            header(buf, 1, 1);
            buf.writeByte(0);
            buf.writeVarInt(9);
            for (int i = 0; i < 9; i++) {
                buf.writeByte(1);
                buf.writeUtf("https://audio.example/" + "x".repeat(8170)).writeUtf("").writeUtf("");
            }
        });
        assertRejected(buf -> { buf.writeVarInt(2).writeByte(0xC3).writeByte(0x28); });
    }

    @Test
    void everyTruncatedPayloadFailsAndLegacyItemNbtLayoutIsNotAccepted() {
        var packet = new ClientboundPlayMusicPacket(Level.OVERWORLD, BlockPos.ZERO, 1,
                Optional.of(new AudioProgram(AudioProgram.Kind.FINITE, List.of(local()))));
        byte[] encoded = encode(packet);
        for (int length = 0; length < encoded.length; length++) {
            byte[] truncated = Arrays.copyOf(encoded, length);
            assertThrows(DecoderException.class, () -> decode(truncated), "length " + length);
        }
        assertRejected(buf -> { buf.writeItem(new ItemStack(Items.PAPER)); buf.writeBlockPos(BlockPos.ZERO); });
    }

    private static AudioTrack local() {
        return new AudioTrack(AudioTrack.SourceType.SOUND_EVENT, "minecraft:music_disc.cat", "Artist", "Title");
    }

    private static AudioTrack remote(String source) {
        return new AudioTrack(AudioTrack.SourceType.REMOTE, source, "", "");
    }

    private static void header(FriendlyByteBuf buf, int itemId, int present) {
        buf.writeUtf("minecraft:overworld").writeBlockPos(BlockPos.ZERO).writeVarInt(itemId).writeByte(present);
    }

    private static byte[] encode(ClientboundPlayMusicPacket packet) {
        return write(packet::writePacketData);
    }

    private static byte[] write(Consumer<FriendlyByteBuf> writer) {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            writer.accept(buf);
            return ByteBufUtil.getBytes(buf);
        } finally {
            buf.release();
        }
    }

    private static ClientboundPlayMusicPacket decode(byte[] encoded) {
        var buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(encoded));
        try {
            var packet = new ClientboundPlayMusicPacket(buf);
            assertEquals(0, buf.readableBytes());
            return packet;
        } finally {
            buf.release();
        }
    }

    private static void assertRejected(Consumer<FriendlyByteBuf> writer) {
        assertThrows(DecoderException.class, () -> decode(write(writer)));
    }
}
