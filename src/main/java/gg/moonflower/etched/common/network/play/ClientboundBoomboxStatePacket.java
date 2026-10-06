package gg.moonflower.etched.common.network.play;

import gg.moonflower.etched.common.audio.AudioProgram;
import gg.moonflower.etched.common.audio.PlaybackState;
import gg.moonflower.etched.common.network.play.handler.EtchedClientPlayPacketHandler;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkEvent;

import java.util.Objects;
import java.util.UUID;

/** Provisional bounded entity-owned finite state; never an entity sound/provider API packet. */
public record ClientboundBoomboxStatePacket(ResourceKey<Level> dimension, int entityId, UUID owner, PlaybackState state)
        implements EtchedPacket {

    public static final int MAX_DIMENSION_LENGTH = 256;

    public ClientboundBoomboxStatePacket {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(state, "state");
        if (dimension.location().toString().length() > MAX_DIMENSION_LENGTH || dimension.location().getPath().isEmpty()) {
            throw new IllegalArgumentException("Invalid boombox dimension");
        }
        if (entityId < 0) {
            throw new IllegalArgumentException("Invalid boombox entity ID");
        }
        if (state.program().isPresent() && (state.program().orElseThrow().kind() != AudioProgram.Kind.FINITE || !state.enabled())) {
            throw new IllegalArgumentException("Boombox state requires enabled finite content or an empty stop");
        }
    }

    public ClientboundBoomboxStatePacket(FriendlyByteBuf buffer) {
        this(decode(buffer));
    }

    private ClientboundBoomboxStatePacket(ClientboundBoomboxStatePacket decoded) {
        this(decoded.dimension, decoded.entityId, decoded.owner, decoded.state);
    }

    private static ClientboundBoomboxStatePacket decode(FriendlyByteBuf buffer) {
        try {
            var dimension = ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.parse(AudioProgramPacketCodec.readUtf(buffer, MAX_DIMENSION_LENGTH)));
            return new ClientboundBoomboxStatePacket(dimension, buffer.readVarInt(), buffer.readUUID(),
                    PlaybackStatePacketCodec.read(buffer));
        } catch (DecoderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new DecoderException("Invalid boombox state payload", exception);
        }
    }

    @Override
    public void writePacketData(FriendlyByteBuf buffer) {
        AudioProgramPacketCodec.writeUtf(buffer, this.dimension.location().toString(), MAX_DIMENSION_LENGTH);
        buffer.writeVarInt(this.entityId);
        buffer.writeUUID(this.owner);
        PlaybackStatePacketCodec.write(buffer, this.state);
    }

    @Override
    public void processPacket(NetworkEvent.Context ctx) {
        EtchedClientPlayPacketHandler.handleBoomboxState(this, ctx);
    }
}
