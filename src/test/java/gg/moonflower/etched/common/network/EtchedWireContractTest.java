package gg.moonflower.etched.common.network;

import gg.moonflower.etched.client.radio.MinecraftTestBootstrap;
import gg.moonflower.etched.common.audio.AudioProgram;
import gg.moonflower.etched.common.audio.AudioTrack;
import gg.moonflower.etched.common.audio.PlaybackState;
import gg.moonflower.etched.common.network.play.*;
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
import net.minecraftforge.network.NetworkDirection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Candidate v5 wire regressions, not a freeze claim: owner reopen runtime verification is still pending. */
class EtchedWireContractTest {

    @BeforeAll
    static void bootstrap() {
        MinecraftTestBootstrap.bootStrap();
    }

    @Test
    void literalFixturesCoverEveryCurrentDeclaredIdTypeAndDirection() {
        assertEquals(Set.of(0, 2, 3, 4, 5, 6, 7), fixtures().stream().map(Fixture::id).collect(Collectors.toSet()));
        for (var fixture : fixtures()) {
            var contract = contract(fixture.id());
            assertEquals(fixture.id(), contract.id());
            assertEquals(fixture.packet().getClass(), contract.type());
            assertEquals(fixture.direction(), contract.direction());
        }
    }

    @Test
    void packetEncodersAndDecodersMatchIndependentLiteralFrames() {
        for (var fixture : fixtures()) {
            byte[] expected = ByteBufUtil.decodeHexDump(fixture.frame());
            assertArrayEquals(expected, encode(fixture), fixture.name());
            assertEquals(fixture.id(), Byte.toUnsignedInt(expected[0]), fixture.name());
            assertEquals(fixture.packet(), decode(fixture.id(), Arrays.copyOfRange(expected, 1, expected.length)), fixture.name());
        }
    }

    @Test
    void everyTruncatedCandidatePayloadIsRejectedByItsPacketDecoder() {
        for (var fixture : fixtures()) {
            byte[] frame = ByteBufUtil.decodeHexDump(fixture.frame());
            Function<FriendlyByteBuf, ?> decoder = decoder(fixture.id());
            for (int length = 0; length < frame.length - 1; length++) {
                byte[] prefix = Arrays.copyOfRange(frame, 1, length + 1);
                var buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(prefix));
                try {
                    // Menu constructors also use vanilla buffer/model exceptions; all must reject, not return a partial packet.
                    assertThrows(RuntimeException.class, () -> decoder.apply(buffer), fixture.name() + " prefix " + length);
                } finally {
                    buffer.release();
                }
            }
        }
    }

    @Test
    void candidateFieldLimitsAreLiteralRatherThanFollowingChangedProductionConstants() {
        assertEquals(100, EtchedProtocol.MAX_MENU_CONTAINER_ID);
        assertEquals(8_192, EtchedProtocol.MAX_URL_LENGTH);
        assertEquals(8_192, ClientboundRadioMenuInitPacket.MAX_URL_LENGTH);
        assertEquals(8_192, ServerboundSetEtchingUrlPacket.MAX_URL_LENGTH);
        assertEquals(8_192, ServerboundSetRadioUrlPacket.MAX_URL_LENGTH);
        assertEquals(1_024, ClientboundEtchingUrlErrorPacket.MAX_MESSAGE_LENGTH);
        assertEquals(128, ServerboundEditMusicLabelPacket.MAX_TEXT_LENGTH);
        assertEquals(256, ClientboundPlayMusicPacket.MAX_DIMENSION_LENGTH);
        assertEquals(256, ClientboundBoomboxStatePacket.MAX_DIMENSION_LENGTH);
        assertEquals(100, AudioProgram.MAX_TRACKS);
        assertEquals(65_536, AudioProgram.MAX_TOTAL_TEXT_LENGTH);
        assertEquals(256, AudioTrack.MAX_SOUND_EVENT_LENGTH);
        assertEquals(8_192, AudioTrack.MAX_REMOTE_SOURCE_LENGTH);
        assertEquals(128, AudioTrack.MAX_METADATA_LENGTH);
    }

    @Test
    void menuDecodersEnforceContainerAndLabelSlotBoundaries() {
        for (int id : List.of(0, 3, 4, 6)) {
            for (int container : List.of(0, 100)) {
                decode(id, write(buffer -> buffer.writeVarInt(container).writeUtf("")));
            }
            for (int invalid : List.of(-1, 101, Integer.MAX_VALUE)) {
                reject(id, buffer -> buffer.writeVarInt(invalid).writeUtf(""), IllegalArgumentException.class);
            }
        }
        for (int slot = 0; slot <= 40; slot++) {
            int index = slot;
            byte[] bytes = write(buffer -> buffer.writeVarInt(index).writeUtf("Artist").writeUtf("Title"));
            if (slot < 9 || slot == 40) {
                assertEquals(new ServerboundEditMusicLabelPacket(slot, "Artist", "Title"), decode(5, bytes));
            } else {
                reject(5, buffer -> buffer.writeBytes(bytes), IllegalArgumentException.class);
            }
        }
        reject(5, buffer -> buffer.writeVarInt(-1).writeUtf("").writeUtf(""), IllegalArgumentException.class);
        reject(5, buffer -> buffer.writeVarInt(41).writeUtf("").writeUtf(""), IllegalArgumentException.class);
    }

    @Test
    void decodersRejectFieldsOneCharacterOverTheCandidateLimits() {
        reject(0, buffer -> buffer.writeVarInt(0).writeUtf("e".repeat(1_025)), DecoderException.class);
        for (int id : List.of(3, 4, 6)) {
            reject(id, buffer -> buffer.writeVarInt(0).writeUtf("u".repeat(8_193)), DecoderException.class);
        }
        reject(5, buffer -> buffer.writeVarInt(0).writeUtf("a".repeat(129)).writeUtf(""), DecoderException.class);
        reject(5, buffer -> buffer.writeVarInt(0).writeUtf("").writeUtf("t".repeat(129)), DecoderException.class);
    }

    @Test
    void retiredItemNbtJukeboxAndEntityActionLayoutsCannotDecodeAsCurrentOwnerState() {
        var legacy = new ItemStack(Items.PAPER);
        legacy.getOrCreateTag().putString("Music", "https://audio.example/legacy.mp3");
        for (var stack : List.of(ItemStack.EMPTY, legacy)) {
            reject(2, buffer -> buffer.writeItem(stack).writeBlockPos(BlockPos.ZERO), DecoderException.class);
        }
        // The removed entity packet used START=0/STOP=1/RESTART=2, optional ItemStack and VarInt entity ID.
        for (int action : List.of(0, 2)) {
            reject(7, buffer -> {
                buffer.writeVarInt(action);
                buffer.writeItem(legacy);
                buffer.writeVarInt(128);
            }, DecoderException.class);
        }
        reject(7, buffer -> buffer.writeVarInt(1).writeVarInt(128), DecoderException.class);
    }

    private static List<Fixture> fixtures() {
        var finite = new AudioProgram(AudioProgram.Kind.FINITE, List.of(
                new AudioTrack(AudioTrack.SourceType.SOUND_EVENT, "minecraft:test", "Я", "曲"),
                new AudioTrack(AudioTrack.SourceType.REMOTE, "https://a.co/t", "", "T")));
        var pos = new BlockPos(-1, 64, 2);
        var owner = UUID.fromString("12345678-9abc-def0-1234-56789abcdef0");
        var moon = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("test:moon"));
        var client = NetworkDirection.PLAY_TO_CLIENT;
        var server = NetworkDirection.PLAY_TO_SERVER;
        // Literal frames include the declared one-byte message discriminator, followed by the Etched payload.
        // Actual Forge registration wiring is checked separately by NetworkRegistrationGameTests.
        // They are not generated by a production codec, enum ordinal, Item registry ID or another round trip.
        return List.of(
                new Fixture("etching error", 0, client, new ClientboundEtchingUrlErrorPacket(17, "Bad URL"),
                        "0011074261642055524c"),
                new Fixture("jukebox start", 2, client, new ClientboundPlayMusicPacket(Level.OVERWORLD, pos, 300,
                        0x0102030405060708L, Optional.of(finite)),
                        "02136d696e6563726166743a6f766572776f726c64ffffffc000002040ac02010203040506070801"
                                + "0002000e6d696e6563726166743a7465737402d0af03e69bb2010e68747470733a2f2f612e636f2f74000154"),
                new Fixture("jukebox stop", 2, client, ClientboundPlayMusicPacket.stopped(moon, BlockPos.ZERO, Long.MIN_VALUE),
                        "0209746573743a6d6f6f6e000000000000000000800000000000000000"),
                new Fixture("unsupported jukebox replacement", 2, client, new ClientboundPlayMusicPacket(Level.OVERWORLD, pos,
                        300, 0x0102030405060708L, Optional.empty()),
                        "02136d696e6563726166743a6f766572776f726c64ffffffc000002040ac02010203040506070800"),
                new Fixture("radio menu init", 3, client, new ClientboundRadioMenuInitPacket(100, "https://a.co/live"),
                        "03641168747470733a2f2f612e636f2f6c697665"),
                new Fixture("etching URL", 4, server, new ServerboundSetEtchingUrlPacket(99, "minecraft:music_disc.cat"),
                        "0463186d696e6563726166743a6d757369635f646973632e636174"),
                new Fixture("Unicode offhand label", 5, server, new ServerboundEditMusicLabelPacket(40, "Я", "曲🎵"),
                        "052802d0af07e69bb2f09f8eb5"),
                new Fixture("radio stop command", 6, server, new ServerboundSetRadioUrlPacket(17, ""), "061100"),
                new Fixture("boombox start", 7, client, new ClientboundBoomboxStatePacket(Level.NETHER, 128, owner,
                        new PlaybackState(0xfedcba9876543210L, Optional.of(finite), true)),
                        "07146d696e6563726166743a7468655f6e65746865728001123456789abcdef0123456789abcdef0fedcba987654321003"
                                + "0002000e6d696e6563726166743a7465737402d0af03e69bb2010e68747470733a2f2f612e636f2f74000154"),
                new Fixture("boombox stop", 7, client, new ClientboundBoomboxStatePacket(Level.OVERWORLD, 0, owner,
                        new PlaybackState(Long.MIN_VALUE, Optional.empty(), false)),
                        "07136d696e6563726166743a6f766572776f726c6400123456789abcdef0123456789abcdef0800000000000000000"));
    }

    private static EtchedProtocol.PacketContract<?> contract(int id) {
        return switch (id) {
            case 0 -> EtchedProtocol.CLIENTBOUND_ETCHING_URL_ERROR;
            case 2 -> EtchedProtocol.CLIENTBOUND_PLAY_MUSIC;
            case 3 -> EtchedProtocol.CLIENTBOUND_RADIO_MENU_INIT;
            case 4 -> EtchedProtocol.SERVERBOUND_SET_ETCHING_URL;
            case 5 -> EtchedProtocol.SERVERBOUND_EDIT_MUSIC_LABEL;
            case 6 -> EtchedProtocol.SERVERBOUND_SET_RADIO_URL;
            case 7 -> EtchedProtocol.CLIENTBOUND_BOOMBOX_STATE;
            default -> throw new AssertionError("No candidate packet " + id);
        };
    }

    private static Function<FriendlyByteBuf, ?> decoder(int id) {
        return switch (id) {
            case 0 -> ClientboundEtchingUrlErrorPacket::new;
            case 2 -> ClientboundPlayMusicPacket::new;
            case 3 -> ClientboundRadioMenuInitPacket::new;
            case 4 -> ServerboundSetEtchingUrlPacket::new;
            case 5 -> ServerboundEditMusicLabelPacket::new;
            case 6 -> ServerboundSetRadioUrlPacket::new;
            case 7 -> ClientboundBoomboxStatePacket::new;
            default -> throw new AssertionError("No candidate decoder " + id);
        };
    }

    private static Object decode(int id, byte[] payload) {
        var buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(payload));
        try {
            Object result = decoder(id).apply(buffer);
            assertEquals(0, buffer.readableBytes(), "Decoder did not consume its complete literal payload");
            return result;
        } finally {
            buffer.release();
        }
    }

    private static <T extends RuntimeException> void reject(int id, Consumer<FriendlyByteBuf> writer, Class<T> failure) {
        var buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(write(writer)));
        try {
            Function<FriendlyByteBuf, ?> decoder = decoder(id);
            assertThrows(failure, () -> decoder.apply(buffer), "Packet ID " + id);
        } finally {
            buffer.release();
        }
    }

    private static byte[] encode(Fixture fixture) {
        return write(buffer -> {
            buffer.writeByte(contract(fixture.id()).id());
            try {
                fixture.packet().writePacketData(buffer);
            } catch (IOException exception) {
                throw new AssertionError(exception);
            }
        });
    }

    private static byte[] write(Consumer<FriendlyByteBuf> writer) {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            writer.accept(buffer);
            return ByteBufUtil.getBytes(buffer);
        } finally {
            buffer.release();
        }
    }

    private record Fixture(String name, int id, NetworkDirection direction, EtchedPacket packet, String frame) {
    }
}
