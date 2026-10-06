package gg.moonflower.etched.common.network.play;

import gg.moonflower.etched.common.network.play.handler.EtchedClientPlayPacketHandler;
import gg.moonflower.etched.common.audio.AudioProgram;
import gg.moonflower.etched.common.item.RecordContentResolver;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;
import java.util.Optional;

/** Bounded custom jukebox start payload. Still provisional: server owner revisions are a separate migration. */
@ApiStatus.Internal
public record ClientboundPlayMusicPacket(ResourceKey<Level> dimension, BlockPos pos, int itemId,
                                         Optional<AudioProgram> program) implements EtchedPacket {

    public static final int MAX_DIMENSION_LENGTH = 256;

    public ClientboundPlayMusicPacket {
        Objects.requireNonNull(dimension, "dimension");
        pos = Objects.requireNonNull(pos, "pos").immutable();
        program = Objects.requireNonNull(program, "program");
        if (dimension.location().toString().length() > MAX_DIMENSION_LENGTH || dimension.location().getPath().isEmpty()) {
            throw new IllegalArgumentException("Invalid jukebox dimension");
        }
        if (itemId < 0) {
            throw new IllegalArgumentException("Invalid jukebox item discriminator");
        }
        if (program.isPresent() && program.orElseThrow().kind() != AudioProgram.Kind.FINITE) {
            throw new IllegalArgumentException("Jukebox playback requires a finite program");
        }
    }

    /** Resolves immutable playback content once; inventory/cosmetic/cover NBT never crosses this packet boundary. */
    public static ClientboundPlayMusicPacket fromRecord(ResourceKey<Level> dimension, BlockPos pos, ItemStack record) {
        return new ClientboundPlayMusicPacket(dimension, pos, Item.getId(record.getItem()),
                RecordContentResolver.resolve(record).map(content -> content.program()));
    }

    public ClientboundPlayMusicPacket(FriendlyByteBuf buf) {
        this(decode(buf));
    }

    private ClientboundPlayMusicPacket(ClientboundPlayMusicPacket decoded) {
        this(decoded.dimension(), decoded.pos(), decoded.itemId(), decoded.program());
    }

    private static ClientboundPlayMusicPacket decode(FriendlyByteBuf buf) {
        try {
            var dimension = ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.parse(AudioProgramPacketCodec.readUtf(buf, MAX_DIMENSION_LENGTH)));
            BlockPos pos = buf.readBlockPos();
            int itemId = buf.readVarInt();
            int present = buf.readUnsignedByte();
            if (present > 1) {
                throw new DecoderException("Unknown jukebox program flag");
            }
            Optional<AudioProgram> program = present == 1 ? Optional.of(AudioProgramPacketCodec.read(buf)) : Optional.empty();
            return new ClientboundPlayMusicPacket(dimension, pos, itemId, program);
        } catch (DecoderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new DecoderException("Invalid jukebox playback payload", exception);
        }
    }

    @Override
    public void writePacketData(FriendlyByteBuf buf) {
        AudioProgramPacketCodec.writeUtf(buf, this.dimension.location().toString(), MAX_DIMENSION_LENGTH);
        buf.writeBlockPos(this.pos);
        buf.writeVarInt(this.itemId);
        buf.writeByte(this.program.isPresent() ? 1 : 0);
        this.program.ifPresent(program -> AudioProgramPacketCodec.write(buf, program));
    }

    @Override
    public void processPacket(NetworkEvent.Context ctx) {
        EtchedClientPlayPacketHandler.handlePlayMusicPacket(this, ctx);
    }
}
