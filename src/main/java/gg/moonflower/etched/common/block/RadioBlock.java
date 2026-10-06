package gg.moonflower.etched.common.block;

import gg.moonflower.etched.common.blockentity.RadioBlockEntity;
import gg.moonflower.etched.common.menu.RadioMenu;
import gg.moonflower.etched.common.network.EtchedMessages;
import gg.moonflower.etched.common.network.play.ClientboundRadioMenuInitPacket;
import gg.moonflower.etched.common.radio.RadioClientBridge;
import gg.moonflower.etched.core.Etched;
import gg.moonflower.etched.core.registry.EtchedBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

@SuppressWarnings("deprecation") // Minecraft deprecates direct calls to the block hooks that subclasses must override.
public class RadioBlock extends BaseEntityBlock {

    public static final IntegerProperty ROTATION = BlockStateProperties.ROTATION_16;
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;
    private static final VoxelShape[] SHAPES = createShapes();
    private static final Component CONTAINER_TITLE = Component.translatable("container." + Etched.MOD_ID + ".radio");

    public RadioBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(ROTATION, 0).setValue(POWERED, false));
    }

    private static VoxelShape[] createShapes() {
        // Authored cuboids from assets-src/radio/manual/radio.json, not new geometry.
        double[][] cuboids = {{2, 0, 5, 14, 6, 9}, {7, 1, 4, 13, 5, 5},
                {3, 1, 4, 7, 5, 5}, {1, 3, 6, 2, 5, 8}};
        VoxelShape[] shapes = new VoxelShape[16];
        for (int rotation = 0; rotation < shapes.length; rotation++) {
            double angle = Math.toRadians(180 + rotation * 22.5);
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            double minX = Double.POSITIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double minZ = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            double maxZ = Double.NEGATIVE_INFINITY;
            for (double[] cuboid : cuboids) {
                minY = Math.min(minY, cuboid[1]);
                maxY = Math.max(maxY, cuboid[4]);
                for (double x : new double[]{cuboid[0] - 8, cuboid[3] - 8}) {
                    for (double z : new double[]{cuboid[2] - 8, cuboid[5] - 8}) {
                        double rotatedX = 8 + x * cos - z * sin;
                        double rotatedZ = 8 + x * sin + z * cos;
                        minX = Math.min(minX, rotatedX);
                        maxX = Math.max(maxX, rotatedX);
                        minZ = Math.min(minZ, rotatedZ);
                        maxZ = Math.max(maxZ, rotatedZ);
                    }
                }
            }
            // Conservative axis-aligned envelope, like the original small block shapes.
            shapes[rotation] = Block.box(minX, minY, minZ, maxX, maxY, maxZ);
        }
        return shapes;
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand interactionHand, BlockHitResult blockHitResult) {
        if (level.isClientSide()) {
            RadioClientBridge.openMenu(level, pos);
            return InteractionResult.SUCCESS;
        }
        player.openMenu(state.getMenuProvider(level, pos)).ifPresent(containerId -> {
            String url = level.getBlockEntity(pos) instanceof RadioBlockEntity be ? be.getUrl() : "";
            EtchedMessages.PLAY.send(PacketDistributor.PLAYER.with(() -> (ServerPlayer) player),
                    new ClientboundRadioMenuInitPacket(containerId, url));
        });
        return InteractionResult.CONSUME;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState()
                .setValue(ROTATION, Mth.floor((double) ((180.0F + context.getRotation()) * 16.0F / 360.0F) + 0.5) & 15)
                .setValue(POWERED, context.getLevel().hasNeighborSignal(context.getClickedPos()));
    }

    @Override
    public void neighborChanged(BlockState blockState, Level level, BlockPos pos, Block block, BlockPos blockPos2, boolean bl) {
        if (!level.isClientSide()) {
            boolean bl2 = blockState.getValue(POWERED);
            if (bl2 != level.hasNeighborSignal(pos)) {
                level.setBlock(pos, blockState.cycle(POWERED), 2);
                level.sendBlockUpdated(pos, blockState, level.getBlockState(pos), 3);
            }
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moving) {
        if (!state.is(newState.getBlock())) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof RadioBlockEntity) {
                if (((RadioBlockEntity) blockEntity).isConfiguredAndEnabled()) {
                    level.levelEvent(1011, pos, 0);
                }
                Clearable.tryClear(blockEntity);
            }

            super.onRemove(state, level, pos, newState, moving);
        }
    }

    @Override
    public MenuProvider getMenuProvider(BlockState blockState, Level level, BlockPos blockPos) {
        BlockEntity blockEntity = level.getBlockEntity(blockPos);
        return new SimpleMenuProvider((menuId, playerInventory, player) -> new RadioMenu(menuId, playerInventory, ContainerLevelAccess.create(level, blockPos), blockEntity instanceof RadioBlockEntity ? ((RadioBlockEntity) blockEntity)::setUrl : url -> {
        }), CONTAINER_TITLE);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext collisionContext) {
        return SHAPES[state.getValue(ROTATION)];
    }

    @Override
    public boolean useShapeForLightOcclusion(BlockState blockState) {
        return true;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(ROTATION, rotation.rotate(state.getValue(ROTATION), 16));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.setValue(ROTATION, mirror.mirror(state.getValue(ROTATION), 16));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RadioBlockEntity(pos, state);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ROTATION, POWERED);
    }

    @Override
    public boolean isPathfindable(BlockState blockState, BlockGetter blockGetter, BlockPos blockPos, PathComputationType pathComputationType) {
        return false;
    }

    @Override
    public ItemStack getCloneItemStack(BlockGetter level, BlockPos pos, BlockState state) {
        return new ItemStack(EtchedBlocks.RADIO.get());
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (!Etched.CLIENT_CONFIG.showNotes.get() || !level.getBlockState(pos.above()).isAir()) {
            return;
        }

        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof RadioBlockEntity radio)) {
            return;
        }

        if (radio.getUrl() == null) {
            return;
        }

        if (RadioClientBridge.isPlaying(level, pos)) {
            level.addParticle(ParticleTypes.NOTE, pos.getX() + 0.5D, pos.getY() + 0.7D, pos.getZ() + 0.5D, random.nextInt(25) / 24D, 0, 0);
        }
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState blockState, BlockEntityType<T> blockEntityType) {
        return createTickerHelper(blockEntityType, EtchedBlocks.RADIO_BE.get(), RadioBlockEntity::tick);
    }
}
