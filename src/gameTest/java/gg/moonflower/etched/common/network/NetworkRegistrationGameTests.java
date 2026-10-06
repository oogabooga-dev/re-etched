package gg.moonflower.etched.common.network;

import gg.moonflower.etched.common.audio.AudioProgram;
import gg.moonflower.etched.common.audio.AudioTrack;
import gg.moonflower.etched.common.audio.PlaybackState;
import gg.moonflower.etched.common.network.play.*;
import gg.moonflower.etched.core.Etched;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.network.NetworkDirection;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Checks the initialized channel in transformed Forge; literal payload vectors are fast EtchedWireContractTest checks. */
@GameTestHolder(Etched.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NetworkRegistrationGameTests {

    private NetworkRegistrationGameTests() {
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void actualChannelRegistersOnlyCandidateV5PacketsWithCorrectCodecsAndDirections(GameTestHelper helper)
            throws ReflectiveOperationException, IOException {
        // The mod entrypoint has already initialized this channel. Do not re-register or build a substitute channel.
        // Forge 47.4.10 exposes encoding but not its registered decoder/direction metadata.
        Object codec = field(EtchedMessages.PLAY, "indexedCodec");
        var ids = (Map<?, ?>) field(codec, "indicies"); // Forge's field spelling.
        var types = (Map<?, ?>) field(codec, "types");
        var samples = samples();
        helper.assertTrue(ids.keySet().equals(Set.of((short) 0, (short) 2, (short) 3, (short) 4, (short) 5, (short) 6, (short) 7)),
                "Actual channel changed candidate IDs or registered the retired entity ID");
        helper.assertTrue(types.keySet().equals(samples.stream().map(sample -> sample.packet().getClass()).collect(Collectors.toSet())),
                "Actual channel contains missing or additional packet classes");
        for (var sample : samples) {
            Object handler = ids.get((short) sample.id());
            helper.assertTrue(handler != null && handler == types.get(sample.packet().getClass()),
                    "ID and type registration disagree for " + sample.id());
            helper.assertTrue(field(handler, "index").equals(sample.id())
                            && field(handler, "messageType").equals(sample.packet().getClass())
                            && field(handler, "networkDirection").equals(Optional.of(sample.direction())),
                    "Registered identity/direction changed for " + sample.id());
            var encoded = new FriendlyByteBuf(Unpooled.buffer());
            var expected = new FriendlyByteBuf(Unpooled.buffer());
            try {
                EtchedMessages.PLAY.encodeMessage(sample.packet(), encoded);
                expected.writeByte(sample.id());
                sample.packet().writePacketData(expected);
                helper.assertTrue(Arrays.equals(ByteBufUtil.getBytes(expected), ByteBufUtil.getBytes(encoded)),
                        "Registered encoder does not match its packet codec for " + sample.id());
                helper.assertTrue(encoded.readUnsignedByte() == sample.id(), "Incorrect Forge frame discriminator");
                Object decoded = decoder(handler).apply(encoded);
                helper.assertTrue(sample.packet().equals(decoded) && encoded.readableBytes() == 0,
                        "Registered decoder changed the packet or left unread bytes for " + sample.id());
            } finally {
                encoded.release();
                expected.release();
            }
        }
        helper.succeed();
    }

    private static List<Sample> samples() {
        var finite = new AudioProgram(AudioProgram.Kind.FINITE, List.of(
                new AudioTrack(AudioTrack.SourceType.SOUND_EVENT, "minecraft:music_disc.cat", "Artist", "Title")));
        var client = NetworkDirection.PLAY_TO_CLIENT;
        var server = NetworkDirection.PLAY_TO_SERVER;
        return List.of(
                new Sample(0, client, new ClientboundEtchingUrlErrorPacket(17, "Invalid URL")),
                new Sample(2, client, new ClientboundPlayMusicPacket(Level.OVERWORLD, new BlockPos(-1, 64, 2),
                        300, Long.MIN_VALUE, Optional.of(finite))),
                new Sample(3, client, new ClientboundRadioMenuInitPacket(100, "https://radio.example/live")),
                new Sample(4, server, new ServerboundSetEtchingUrlPacket(99, "minecraft:music_disc.cat")),
                new Sample(5, server, new ServerboundEditMusicLabelPacket(40, "Я", "曲🎵")),
                new Sample(6, server, new ServerboundSetRadioUrlPacket(17, "")),
                new Sample(7, client, new ClientboundBoomboxStatePacket(Level.NETHER, 128, new UUID(1234, 5678),
                        new PlaybackState(Long.MAX_VALUE, Optional.of(finite), true))));
    }

    @SuppressWarnings("unchecked")
    private static Function<FriendlyByteBuf, ?> decoder(Object handler) throws ReflectiveOperationException {
        return ((Optional<Function<FriendlyByteBuf, ?>>) field(handler, "decoder")).orElseThrow();
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private record Sample(int id, NetworkDirection direction, EtchedPacket packet) {
    }
}
