package gg.moonflower.etched.common.blockentity;

import gg.moonflower.etched.common.block.RadioBlock;
import gg.moonflower.etched.common.radio.RadioClientBridge;
import gg.moonflower.etched.common.radio.RadioConfiguration;
import gg.moonflower.etched.core.registry.EtchedBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.Clearable;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * @author Ocelot
 */
public class RadioBlockEntity extends BlockEntity implements Clearable {

    private final RadioControlState controlState = new RadioControlState();

    public RadioBlockEntity(BlockPos pos, BlockState state) {
        super(EtchedBlocks.RADIO_BE.get(), pos, state);
    }

    public static void tick(Level level, BlockPos pos, BlockState state, RadioBlockEntity blockEntity) {
        RadioClientBridge.tick(level, pos, blockEntity.getConfiguration(state));
    }

    @Override
    public void load(CompoundTag nbt) {
        super.load(nbt);
        this.controlState.load(nbt);
        this.publishUpdate();
    }

    @Override
    public void saveAdditional(CompoundTag nbt) {
        this.controlState.save(nbt);
    }

    @Override
    public CompoundTag getUpdateTag() {
        return this.saveWithoutMetadata();
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void clearContent() {
        if (this.controlState.clear()) {
            this.stateChanged();
        }
    }

    public String getUrl() {
        return this.controlState.storedUrl();
    }

    public void setUrl(String url) {
        if (this.controlState.apply(url)) {
            this.stateChanged();
        }
    }

    public boolean isConfiguredAndEnabled() {
        return this.getConfiguration(this.getBlockState()).isEnabled();
    }

    public boolean isManuallyEnabled() {
        return this.controlState.enabled();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        this.publishUpdate();
    }

    @Override
    public void onChunkUnloaded() {
        this.publishRemove();
        super.onChunkUnloaded();
    }

    @Override
    public void setRemoved() {
        this.publishRemove();
        super.setRemoved();
    }

    @SuppressWarnings("deprecation") // Required override for reacting to the block state retained by Minecraft 1.20.1.
    @Override
    public void setBlockState(BlockState state) {
        boolean powered = this.getConfiguration(this.getBlockState()).powered();
        super.setBlockState(state);
        if (powered != this.getConfiguration(state).powered()) {
            this.publishUpdate();
        }
    }

    private RadioConfiguration getConfiguration(BlockState state) {
        boolean powered = state.hasProperty(RadioBlock.POWERED) && state.getValue(RadioBlock.POWERED);
        return new RadioConfiguration(this.controlState.activeUrl(), powered);
    }

    private void stateChanged() {
        this.setChanged();
        if (this.level != null) {
            this.level.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(), 3);
        }
        this.publishUpdate();
    }

    private void publishUpdate() {
        if (this.level != null) {
            RadioClientBridge.update(this.level, this.worldPosition, this.getConfiguration(this.getBlockState()));
        }
    }

    private void publishRemove() {
        if (this.level != null) {
            RadioClientBridge.remove(this.level, this.worldPosition);
        }
    }
}
