package gg.moonflower.etched.core.mixin;

import gg.moonflower.etched.common.item.JukeboxRecordSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(JukeboxBlock.class)
public abstract class JukeboxBlockMixin extends BaseEntityBlock {

    protected JukeboxBlockMixin(Properties properties) {
        super(properties);
    }

    @Inject(method = "getAnalogOutputSignal", at = @At("TAIL"), cancellable = true)
    public void getAnalogOutputSignal(BlockState state, Level level, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (level.getBlockEntity(pos) instanceof JukeboxBlockEntity be) {
            ItemStack record = be.getFirstItem();
            if (JukeboxRecordSupport.isCustomRecord(record)) {
                cir.setReturnValue(15);
            }
        }
    }
}
