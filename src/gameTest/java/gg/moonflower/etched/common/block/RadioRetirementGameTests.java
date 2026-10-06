package gg.moonflower.etched.common.block;

import gg.moonflower.etched.common.menu.RadioMenu;
import gg.moonflower.etched.core.Etched;
import gg.moonflower.etched.core.registry.EtchedBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityTravelToDimensionEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

@GameTestHolder(Etched.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RadioRetirementGameTests {

    private RadioRetirementGameTests() {
    }

    @GameTest(template = "empty")
    @SuppressWarnings("deprecation") // Exercise the same vanilla pick-block hook overridden by RadioBlock.
    public static void onlyOrdinaryRadioIsRegisteredClonedAndDropped(GameTestHelper helper) {
        helper.assertFalse(ForgeRegistries.ITEMS.containsKey(ResourceLocation.fromNamespaceAndPath(Etched.MOD_ID, "portal_radio")),
                "Retired item remains registered");
        var block = EtchedBlocks.RADIO.get();
        helper.assertTrue(block.getStateDefinition().getProperties().size() == 2
                        && block.getStateDefinition().getProperty("rotation") == RadioBlock.ROTATION
                        && block.getStateDefinition().getProperty("powered") == RadioBlock.POWERED,
                "Radio must expose only rotation and powered");
        helper.assertTrue(block.getStateDefinition().getPossibleStates().size() == 32,
                "Radio retained a third state property");
        var relative = new BlockPos(1, 1, 1);
        var absolute = helper.absolutePos(relative);
        helper.setBlock(relative, block.defaultBlockState());
        var level = helper.getLevel();
        for (var state : block.getStateDefinition().getPossibleStates()) {
            helper.assertTrue(block.getCloneItemStack(level, absolute, state).is(block.asItem()),
                    "Pick-block returned a different item");
            var drops = Block.getDrops(state, level, absolute, level.getBlockEntity(absolute), null, ItemStack.EMPTY);
            helper.assertTrue(drops.size() == 1 && drops.get(0).is(block.asItem()) && drops.get(0).getCount() == 1,
                    "Radio loot no longer returns exactly one ordinary radio");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    @SuppressWarnings("deprecation") // Exercise RadioBlock's interaction hook directly with the mock survival player.
    public static void cakeInteractionPreservesRadioAndDoesNotConsumeCake(GameTestHelper helper) {
        var block = EtchedBlocks.RADIO.get();
        var relative = new BlockPos(1, 1, 1);
        var absolute = helper.absolutePos(relative);
        var state = block.defaultBlockState();
        helper.setBlock(relative, state);
        var player = helper.makeMockSurvivalPlayer();
        var cake = new ItemStack(Items.CAKE, 3);
        player.setItemInHand(InteractionHand.MAIN_HAND, cake);
        var result = block.use(state, helper.getLevel(), absolute, player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(absolute), Direction.UP, absolute, false));
        helper.assertTrue(result == InteractionResult.CONSUME, "Cake did not follow ordinary radio menu interaction");
        helper.assertTrue(cake.getCount() == 3, "Radio still consumes cake");
        helper.assertTrue(helper.getLevel().getBlockState(absolute).equals(state), "Cake still changes the radio state");
        var provider = state.getMenuProvider(helper.getLevel(), absolute);
        helper.assertTrue(provider != null && provider.createMenu(1, player.getInventory(), player) instanceof RadioMenu,
                "Ordinary radio menu was removed with the easter egg");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void netherTravelEventPreservesDroppedRadioStackAndTag(GameTestHelper helper) {
        var stack = new ItemStack(EtchedBlocks.RADIO.get(), 3);
        stack.getOrCreateTag().putString("Fixture", "keep this tag");
        var before = stack.save(new net.minecraft.nbt.CompoundTag());
        var entity = new ItemEntity(helper.getLevel(), 0, 0, 0, stack);
        MinecraftForge.EVENT_BUS.post(new EntityTravelToDimensionEvent(entity, Level.NETHER));
        helper.assertTrue(entity.getItem() == stack, "A dimension listener replaced the radio stack");
        helper.assertTrue(entity.getItem().save(new net.minecraft.nbt.CompoundTag()).equals(before),
                "A dimension listener changed radio count or NBT");
        helper.succeed();
    }

    @GameTest(template = "empty")
    @SuppressWarnings("deprecation") // Exercise transformed selection and collision hooks on the registered block.
    public static void manualRadioSelectionAndCollisionPreserveAllPoweredStates(GameTestHelper helper) {
        var block = EtchedBlocks.RADIO.get();
        var absolute = helper.absolutePos(new BlockPos(1, 1, 1));
        for (int rotation = 0; rotation < 16; rotation++) {
            var state = block.defaultBlockState().setValue(RadioBlock.ROTATION, rotation);
            var bounds = state.getShape(helper.getLevel(), absolute, CollisionContext.empty()).bounds();
            helper.assertTrue(bounds.minX >= 0 && bounds.minZ >= 0 && bounds.maxX <= 1 && bounds.maxZ <= 1
                            && bounds.minY == 0 && bounds.maxY == 6.0 / 16,
                    "Manual radio selection envelope exceeds its block or has the old height");
            for (boolean powered : new boolean[]{false, true}) {
                var poweredState = state.setValue(RadioBlock.POWERED, powered);
                helper.assertTrue(poweredState.getShape(helper.getLevel(), absolute).bounds().equals(bounds),
                        "Power state changes the manual radio selection envelope");
                helper.assertTrue(poweredState.getCollisionShape(helper.getLevel(), absolute).bounds().equals(bounds),
                        "Manual radio collision differs from selection");
            }
        }
        var south = block.defaultBlockState().setValue(RadioBlock.ROTATION, 0)
                .getShape(helper.getLevel(), absolute).bounds();
        var north = block.defaultBlockState().setValue(RadioBlock.ROTATION, 8)
                .getShape(helper.getLevel(), absolute).bounds();
        helper.assertTrue(Math.abs(south.maxX - 15.0 / 16) < 0.000001
                        && Math.abs(north.minX - 1.0 / 16) < 0.000001,
                "Selection envelope no longer includes the authored side knob at opposite headings");
        helper.succeed();
    }
}
