package gg.moonflower.etched.common.network;

import gg.moonflower.etched.client.radio.MinecraftTestBootstrap;
import gg.moonflower.etched.common.menu.RadioMenu;
import gg.moonflower.etched.common.network.play.*;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.network.NetworkDirection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EtchedMessagesCompatibilityTest {

    private static final String RADIO_URL = "https://radio.example/live";

    static {
        MinecraftTestBootstrap.bootStrap();
    }

    @Test
    void preservesEtched304ProtocolAndPacketIds() {
        assertEquals("3", EtchedLegacyProtocol.VERSION);
        assertContract(EtchedLegacyProtocol.CLIENTBOUND_INVALID_ETCH_URL, 0,
                ClientboundInvalidEtchUrlPacket.class, NetworkDirection.PLAY_TO_CLIENT);
        assertContract(EtchedLegacyProtocol.CLIENTBOUND_PLAY_ENTITY_MUSIC, 1,
                ClientboundPlayEntityMusicPacket.class, NetworkDirection.PLAY_TO_CLIENT);
        assertContract(EtchedLegacyProtocol.CLIENTBOUND_PLAY_MUSIC, 2,
                ClientboundPlayMusicPacket.class, NetworkDirection.PLAY_TO_CLIENT);
        assertContract(EtchedLegacyProtocol.CLIENTBOUND_SET_URL, 3,
                ClientboundSetUrlPacket.class, NetworkDirection.PLAY_TO_CLIENT);
        assertContract(EtchedLegacyProtocol.SERVERBOUND_SET_URL, 4,
                ServerboundSetUrlPacket.class, NetworkDirection.PLAY_TO_SERVER);
        assertContract(EtchedLegacyProtocol.SERVERBOUND_EDIT_MUSIC_LABEL, 5,
                ServerboundEditMusicLabelPacket.class, NetworkDirection.PLAY_TO_SERVER);
        assertContract(EtchedLegacyProtocol.SET_ALBUM_JUKEBOX_TRACK, 6,
                SetAlbumJukeboxTrackPacket.class, null);
    }

    @Test
    void preservesClientboundRadioUrlEncoding() throws Exception {
        assertLegacyUrlEncoding(new ClientboundSetUrlPacket(RADIO_URL));
    }

    @Test
    void preservesServerboundRadioUrlEncoding() throws Exception {
        assertLegacyUrlEncoding(new ServerboundSetUrlPacket(RADIO_URL));
    }

    @Test
    void preservesLegacyRadioMenuSetUrlDescriptor() throws Exception {
        assertEquals(void.class, RadioMenu.class.getMethod("setUrl", String.class).getReturnType());
    }

    @Test
    void decodesLegacyRadioUrlBytes() {
        byte[] encoded = expectedLegacyUrlBytes();
        FriendlyByteBuf clientboundBuffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(encoded));
        FriendlyByteBuf serverboundBuffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(encoded));

        try {
            ClientboundSetUrlPacket clientbound = new ClientboundSetUrlPacket(clientboundBuffer);
            ServerboundSetUrlPacket serverbound = new ServerboundSetUrlPacket(serverboundBuffer);

            assertEquals(RADIO_URL, clientbound.url());
            assertEquals(RADIO_URL, serverbound.url());
        } finally {
            clientboundBuffer.release();
            serverboundBuffer.release();
        }
    }

    @Test
    void preservesInvalidEtchUrlCodec() {
        ClientboundInvalidEtchUrlPacket packet = new ClientboundInvalidEtchUrlPacket("Invalid URL");
        byte[] encoded = encode(packet);

        assertArrayEquals(expected(buffer -> buffer.writeUtf("Invalid URL")), encoded);
        assertEquals("Invalid URL", decode(encoded, ClientboundInvalidEtchUrlPacket::new).exception());
    }

    @Test
    void preservesMusicLabelEditCodecAndLimits() {
        ServerboundEditMusicLabelPacket packet = new ServerboundEditMusicLabelPacket(37, "Artist", "Title");
        byte[] encoded = encode(packet);

        assertArrayEquals(expected(buffer -> {
            buffer.writeVarInt(37);
            buffer.writeUtf("Artist", 128);
            buffer.writeUtf("Title", 128);
        }), encoded);
        assertEquals(packet, decode(encoded, ServerboundEditMusicLabelPacket::new));
    }

    @Test
    void preservesAlbumJukeboxTrackCodec() {
        SetAlbumJukeboxTrackPacket packet = new SetAlbumJukeboxTrackPacket(8, 127);
        byte[] encoded = encode(packet);

        assertArrayEquals(expected(buffer -> {
            buffer.writeVarInt(8);
            buffer.writeVarInt(127);
        }), encoded);
        assertEquals(packet, decode(encoded, SetAlbumJukeboxTrackPacket::new));
    }

    @Test
    void preservesBlockMusicCodecIncludingRecordNbt() {
        ItemStack record = new ItemStack(Items.PAPER);
        record.getOrCreateTag().putString("Music", "legacy-payload");
        BlockPos pos = new BlockPos(-12, 64, 345);
        ClientboundPlayMusicPacket packet = new ClientboundPlayMusicPacket(record, pos);
        byte[] encoded = encode(packet);

        assertArrayEquals(expected(buffer -> {
            buffer.writeItem(record);
            buffer.writeBlockPos(pos);
        }), encoded);
        ClientboundPlayMusicPacket decoded = decode(encoded, ClientboundPlayMusicPacket::new);
        assertTrue(ItemStack.matches(record, decoded.record()));
        assertEquals(pos, decoded.pos());
    }

    @Test
    void preservesEntityMusicStartRestartAndStopCodecs() {
        ItemStack record = new ItemStack(Items.PAPER);
        record.getOrCreateTag().putString("Music", "legacy-payload");

        assertEntityMusicCodec(ClientboundPlayEntityMusicPacket.Action.START, record, 42);
        assertEntityMusicCodec(ClientboundPlayEntityMusicPacket.Action.RESTART, record, 300);
        assertEntityMusicCodec(ClientboundPlayEntityMusicPacket.Action.STOP, ItemStack.EMPTY, 7);
    }

    private static void assertLegacyUrlEncoding(EtchedPacket packet) throws Exception {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            packet.writePacketData(buffer);
            assertArrayEquals(expectedLegacyUrlBytes(), ByteBufUtil.getBytes(buffer));
        } finally {
            buffer.release();
        }
    }

    private static void assertContract(EtchedLegacyProtocol.PacketContract<?> contract, int id,
                                       Class<? extends EtchedPacket> type, NetworkDirection direction) {
        assertEquals(id, contract.id());
        assertEquals(type, contract.type());
        assertEquals(direction, contract.direction());
    }

    private static byte[] expectedLegacyUrlBytes() {
        byte[] url = RADIO_URL.getBytes(StandardCharsets.UTF_8);
        byte[] encoded = new byte[url.length + 1];
        encoded[0] = (byte) url.length;
        System.arraycopy(url, 0, encoded, 1, url.length);
        return encoded;
    }

    private static void assertEntityMusicCodec(ClientboundPlayEntityMusicPacket.Action action,
                                               ItemStack record, int entityId) {
        byte[] encoded = expected(buffer -> {
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

    private static byte[] encode(EtchedPacket packet) {
        return expected(buffer -> {
            try {
                packet.writePacketData(buffer);
            } catch (IOException exception) {
                throw new AssertionError(exception);
            }
        });
    }

    private static byte[] expected(Consumer<FriendlyByteBuf> writer) {
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
