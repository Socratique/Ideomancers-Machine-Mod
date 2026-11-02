package com.ideomancer.machinemod.block;

import com.ideomancer.machinemod.registry.ModBlocks;
import com.ideomancer.machinemod.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * Hollowstone — a decorative block created when lava meets Oddstone.
 *
 * Features:
 * - FILLED property: when false, displays dark opaque centers; when true,
 *   transparent centers reveal an inner decorative block rendered by BESR.
 * - Right-click with a block item to fill (cosmetic only, no behavior delegation).
 * - Mining drops 5-7 Hollowstone Shards (Fortune compatible, Silk Touch drops block).
 * - Breaking a filled Hollowstone leaves the inner block in-world.
 *
 * Written by Claude, directed and guided by The Ideomancer.
 */
public class HollowstoneBlock extends Block implements EntityBlock {

    public static final BooleanProperty FILLED = BooleanProperty.create("filled");

    public HollowstoneBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FILLED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FILLED);
    }

    // ========================================================================
    // BLOCK ENTITY
    // ========================================================================

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new HollowstoneBlockEntity(pos, state);
    }

    // ========================================================================
    // PLACING A FILLED HOLLOWSTONE ITEM, AND MIDDLE-CLICK
    // ========================================================================

    /**
     * A Hollowstone item can carry an inner block (Silk Touch keeps it, as framed blocks do). The
     * block entity doesn't exist yet here, so FILLED is read from the item; vanilla then loads the
     * item's BlockEntityTag into the new block entity.
     */
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FILLED, innerFromStack(context.getItemInHand()) != null);
    }

    @Override
    public ItemStack getCloneItemStack(BlockGetter level, BlockPos pos, BlockState state) {
        ItemStack stack = super.getCloneItemStack(level, pos, state);
        if (level.getBlockEntity(pos) instanceof HollowstoneBlockEntity hollowBE && hollowBE.getInnerBlockId() != null) {
            HollowstoneBlockEntity.setInnerBlockIdOnStack(stack, hollowBE.getInnerBlockId());
        }
        return stack;
    }

    /** The item's inner block, or null if it has none (or names a block that doesn't exist). */
    @Nullable
    static Block innerFromStack(ItemStack stack) {
        ResourceLocation id = HollowstoneBlockEntity.getInnerBlockIdFromStack(stack);
        if (id == null) return null;
        Block inner = BuiltInRegistries.BLOCK.get(id);
        return inner == Blocks.AIR ? null : inner;
    }

    // ========================================================================
    // RIGHT-CLICK TO FILL
    // ========================================================================

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos,
                                  Player player, InteractionHand hand, BlockHitResult hit) {
        if (state.getValue(FILLED)) {
            return InteractionResult.PASS;
        }

        ItemStack held = player.getItemInHand(hand);
        if (held.isEmpty() || !(held.getItem() instanceof BlockItem blockItem)) {
            return InteractionResult.PASS;
        }

        Block innerBlock = blockItem.getBlock();

        // Don't allow block entity blocks (keep it simple like Framed Blocks)
        if (innerBlock.defaultBlockState().hasBlockEntity()) {
            return InteractionResult.PASS;
        }

        // Don't allow air
        if (innerBlock.defaultBlockState().isAir()) {
            return InteractionResult.PASS;
        }

        // Full cube blocks only. Buttons, levers, torches, slabs
        // and the like could be put inside, where they float in the middle.
        if (!innerBlock.defaultBlockState().isCollisionShapeFullBlock(level, pos)) {
            return InteractionResult.PASS;
        }

        if (!level.isClientSide()) {
            // Store inner block in block entity
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof HollowstoneBlockEntity hollowBE) {
                ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(innerBlock);
                hollowBE.setInnerBlock(blockId);
            }

            // Set blockstate to FILLED=true
            level.setBlock(pos, state.setValue(FILLED, true), Block.UPDATE_ALL);

            // Consume item (not in creative)
            if (!player.isCreative()) {
                held.shrink(1);
            }

            // Play placement sound
            level.playSound(null, pos, SoundEvents.STONE_PLACE, SoundSource.BLOCKS,
                    1.0f, 1.0f);
        }

        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    // ========================================================================
    // MINING BEHAVIOR — FILLED BLOCK LEAVES INNER BLOCK IN-WORLD
    // ========================================================================

    /**
     * When a filled Hollowstone is broken, place the inner block at the same position.
     * Shard drops are handled by the loot table.
     */
    @Override
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide() && state.getValue(FILLED)) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof HollowstoneBlockEntity hollowBE && hollowBE.hasInnerBlock()) {
                // If using Silk Touch, don't place inner block (block drops with NBT)
                ItemStack tool = player.getMainHandItem();
                if (!tool.isEmpty() && tool.getEnchantmentLevel(
                        net.minecraft.world.item.enchantment.Enchantments.SILK_TOUCH) > 0) {
                    // Silk Touch: block drops as-is, inner block data preserved in NBT
                    // Don't place inner block in world
                } else {
                    // Normal mining: place inner block in world
                    Block innerBlock = hollowBE.getInnerBlock();
                    if (innerBlock != null) {
                        // Schedule inner block placement for after the Hollowstone is removed
                        BlockState innerState = innerBlock.defaultBlockState();
                        // Use level.setBlock with UPDATE_ALL after super removes the block
                        // We store the state to place it in onRemove instead
                        hollowBE.markForInnerBlockPlacement();
                    }
                }
            }
        }
        super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos,
                          BlockState newState, boolean movedByPiston) {
        if (!level.isClientSide() && !state.is(newState.getBlock())) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof HollowstoneBlockEntity hollowBE && hollowBE.shouldPlaceInnerBlock()) {
                Block innerBlock = hollowBE.getInnerBlock();
                if (innerBlock != null) {
                    // Store reference before super.onRemove removes the BE
                    BlockState innerState = innerBlock.defaultBlockState();
                    super.onRemove(state, level, pos, newState, movedByPiston);
                    // Place inner block after Hollowstone is removed
                    level.setBlock(pos, innerState, Block.UPDATE_ALL);
                    return;
                }
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
