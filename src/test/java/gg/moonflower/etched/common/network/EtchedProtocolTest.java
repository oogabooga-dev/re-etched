package gg.moonflower.etched.common.network;

import gg.moonflower.etched.client.radio.MinecraftTestBootstrap;
import gg.moonflower.etched.common.network.play.*;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EtchedProtocolTest {

    private static final String RADIO_URL = "https://radio.example/live";

    static {
        MinecraftTestBootstrap.bootStrap();
    }

    @Test
    void definesStrictV5Boundary() {
        assertEquals(ResourceLocation.fromNamespaceAndPath("etched", "play"), EtchedProtocol.CHANNEL_NAME);
        assertEquals("5", EtchedProtocol.VERSION);
        assertTrue(EtchedProtocol.accepts("5"));
        assertFalse(EtchedProtocol.accepts("3"));
        assertFalse(EtchedProtocol.accepts("4.1.0"));
        assertFalse(EtchedProtocol.accepts("5.0.0-alpha.1"));
        assertFalse(EtchedProtocol.accepts(NetworkRegistry.ABSENT.version()));
        assertFalse(EtchedProtocol.accepts(NetworkRegistry.ACCEPTVANILLA));
        assertFalse(EtchedProtocol.accepts(null));
    }

    @Test
    void displayTestUsesTheNetworkProtocolEpoch() {
        IExtensionPoint.DisplayTest displayTest = EtchedProtocol.displayTest();

        assertEquals("5", displayTest.suppliedVersion().get());
        assertTrue(displayTest.remoteVersionTest().test("5", true));
        assertTrue(displayTest.remoteVersionTest().test("5", false));
        assertFalse(displayTest.remoteVersionTest().test("3.0.4", true));
        assertFalse(displayTest.remoteVersionTest().test("4.1.0", false));
        assertFalse(displayTest.remoteVersionTest().test(null, true));
    }

    @Test
    void assignsDistinctIdsAndExplicitDirectionsToCurrentPackets() {
        assertContract(EtchedProtocol.CLIENTBOUND_INVALID_ETCH_URL, 0,
                ClientboundInvalidEtchUrlPacket.class, NetworkDirection.PLAY_TO_CLIENT);
        assertContract(EtchedProtocol.CLIENTBOUND_PLAY_ENTITY_MUSIC, 1,
                ClientboundPlayEntityMusicPacket.class, NetworkDirection.PLAY_TO_CLIENT);
        assertContract(EtchedProtocol.CLIENTBOUND_PLAY_MUSIC, 2,
                ClientboundPlayMusicPacket.class, NetworkDirection.PLAY_TO_CLIENT);
        assertContract(EtchedProtocol.CLIENTBOUND_SET_URL, 3,
                ClientboundSetUrlPacket.class, NetworkDirection.PLAY_TO_CLIENT);
        assertContract(EtchedProtocol.SERVERBOUND_SET_URL, 4,
                ServerboundSetUrlPacket.class, NetworkDirection.PLAY_TO_SERVER);
        assertContract(EtchedProtocol.SERVERBOUND_EDIT_MUSIC_LABEL, 5,
                ServerboundEditMusicLabelPacket.class, NetworkDirection.PLAY_TO_SERVER);

        Set<Integer> ids = new HashSet<>();
        ids.add(EtchedProtocol.CLIENTBOUND_INVALID_ETCH_URL.id());
        ids.add(EtchedProtocol.CLIENTBOUND_PLAY_ENTITY_MUSIC.id());
        ids.add(EtchedProtocol.CLIENTBOUND_PLAY_MUSIC.id());
        ids.add(EtchedProtocol.CLIENTBOUND_SET_URL.id());
        ids.add(EtchedProtocol.SERVERBOUND_SET_URL.id());
        ids.add(EtchedProtocol.SERVERBOUND_EDIT_MUSIC_LABEL.id());
        assertEquals(Set.of(0, 1, 2, 3, 4, 5), ids);
    }

    @Test
    void roundTripsCurrentMenuPacketCodecs() {
        assertEquals(RADIO_URL,
                roundTrip(new ClientboundSetUrlPacket(RADIO_URL), ClientboundSetUrlPacket::new).url());
        assertEquals(RADIO_URL,
                roundTrip(new ServerboundSetUrlPacket(RADIO_URL), ServerboundSetUrlPacket::new).url());

        ClientboundInvalidEtchUrlPacket invalidUrl = new ClientboundInvalidEtchUrlPacket("Invalid URL");
        assertEquals(invalidUrl.exception(),
                roundTrip(invalidUrl, ClientboundInvalidEtchUrlPacket::new).exception());

        ServerboundEditMusicLabelPacket label = new ServerboundEditMusicLabelPacket(37, "Artist", "Title");
        assertEquals(label, roundTrip(label, ServerboundEditMusicLabelPacket::new));
    }

    @Test
    void roundTripsCurrentBlockMusicCodec() {
        ItemStack record = recordWithLegacyPayload();
        BlockPos pos = new BlockPos(-12, 64, 345);
        ClientboundPlayMusicPacket decoded = roundTrip(
                new ClientboundPlayMusicPacket(record, pos), ClientboundPlayMusicPacket::new);

        assertTrue(ItemStack.matches(record, decoded.record()));
        assertEquals(pos, decoded.pos());
    }

    @Test
    void roundTripsCurrentEntityMusicCodecs() {
        ItemStack record = recordWithLegacyPayload();

        assertEntityMusicCodec(ClientboundPlayEntityMusicPacket.Action.START, record, 42);
        assertEntityMusicCodec(ClientboundPlayEntityMusicPacket.Action.RESTART, record, 300);
        assertEntityMusicCodec(ClientboundPlayEntityMusicPacket.Action.STOP, ItemStack.EMPTY, 7);
    }

    private static ItemStack recordWithLegacyPayload() {
        ItemStack record = new ItemStack(Items.PAPER);
        record.getOrCreateTag().putString("Music", "legacy-payload");
        return record;
    }

    private static void assertContract(EtchedProtocol.PacketContract<?> contract, int id,
                                       Class<? extends EtchedPacket> type, NetworkDirection direction) {
        assertEquals(id, contract.id());
        assertEquals(type, contract.type());
        assertEquals(direction, contract.direction());
    }

    private static void assertEntityMusicCodec(ClientboundPlayEntityMusicPacket.Action action,
                                               ItemStack record, int entityId) {
        byte[] encoded = write(buffer -> {
            buffer.writeEnum(action);
            if (action != ClientboundPlayEntityMusicPacket.Action.STOP) {
                buffer.writeItem(record);
            }
            buffer.writeVarInt(entityId);
        });
        ClientboundPlayEntityMusicPacket decoded = decode(encoded, ClientboundPlayEntityMusicPacket::new);

        assertEquals(action, decoded.getAction());
        assertTrue(ItemStack.matches(record, decoded.getRecord()));
        assertEquals(entityId, decoded.getEntityId());
        assertArrayEquals(encoded, encode(decoded));
    }

    private static <T extends EtchedPacket> T roundTrip(T packet, Function<FriendlyByteBuf, T> decoder) {
        return decode(encode(packet), decoder);
    }

    private static byte[] encode(EtchedPacket packet) {
        return write(buffer -> {
            try {
                packet.writePacketData(buffer);
            } catch (IOException exception) {
                throw new AssertionError(exception);
            }
        });
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

    private static <T> T decode(byte[] encoded, Function<FriendlyByteBuf, T> decoder) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(encoded));
        try {
            return decoder.apply(buffer);
        } finally {
            buffer.release();
        }
    }
}
