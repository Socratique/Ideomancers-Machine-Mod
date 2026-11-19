package com.ideomancer.machinemod.mixin;

import com.ideomancer.machinemod.Ideomod;
import com.ideomancer.machinemod.block.movement.IPistonMovable;
import com.ideomancer.machinemod.block.movement.MovingBlockEntityData;
import com.ideomancer.machinemod.block.movement.MovingDataHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into PistonMovingBlockEntity to restore IPistonMovable block entity
 * data after piston animation completes.
 *
 * This is the RESTORE phase of the piston movement data pipeline:
 *   SAVE:    PistonBaseBlockMixin (vanilla pistons) or BlockMoveHelper (our systems)
 *   TRANSIT: MovingBlockEntityData holds the data during animation
 *   RESTORE: This mixin (runs for ALL piston completions)
 *
 * TWO RESTORE PATHS:
 *   1. tick() TAIL — normal 2-tick piston animation. Block transitions from
 *      MOVING_PISTON to real block on the final tick.
 *   2. finalTick() TAIL — 1-tick (0-tick) piston pulses. Vanilla calls
 *      finalTick() instead of the normal tick() cycle, which immediately
 *      places the real block. Without hooking this, data is lost on 1-tick
 *      pulses because tick() never sees the "real block placed" condition.
 *
 * CRITICAL: Do NOT add competing retrieval logic elsewhere (e.g. Block.onPlace).
 * MovingBlockEntityData.retrieve() CONSUMES the data. If onPlace() calls retrieve()
 * before this mixin runs, the data is gone. Worse, onPlace() fires BEFORE the
 * block entity is created, so even if it retrieves data, there's no block entity
 * to load it into. This was the cause of the original "item deleted on move" bug.
 *
 * SAVED COPY (2026-11-15): the moving block also writes the waiting data into its own NBT
 * (saveAdditional) and reads it back (load). MovingBlockEntityData lives only in memory, so
 * without this a world saved and closed mid-move, or a chunk unloaded mid-move, landed the
 * block with an empty block entity (items, fuel and settings lost). The in-memory copy is
 * still used first; the saved copy is the fallback.
 *
 * Written by Claude, directed and guided by The Ideomancer.
 */
@Mixin(PistonMovingBlockEntity.class)
public abstract class PistonMovingBlockEntityMixin implements MovingDataHolder {

    @Unique
    private static final String MACHINEMOD$TAG = "machinemod:MovingData";

    /** The machine data this moving block was loaded with from the save, if any. */
    @Unique
    private CompoundTag machinemod$savedMoveData;

    @Override
    public CompoundTag machinemod$getSavedMoveData() {
        return this.machinemod$savedMoveData;
    }

    @Override
    public void machinemod$clearSavedMoveData() {
        this.machinemod$savedMoveData = null;
    }

    /** Writes the waiting machine data (or the copy this block was loaded with) into the save. */
    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void machinemod$saveMoveData(CompoundTag tag, CallbackInfo ci) {
        PistonMovingBlockEntity self = (PistonMovingBlockEntity) (Object) this;
        Level level = self.getLevel();
        CompoundTag data = null;
        if (level != null && !level.isClientSide()) {
            data = MovingBlockEntityData.peek(level.dimension().location().toString(), self.getBlockPos());
        }
        if (data == null) data = this.machinemod$savedMoveData;
        if (data != null) tag.put(MACHINEMOD$TAG, data.copy());
    }

    @Inject(method = "load", at = @At("TAIL"))
    private void machinemod$loadMoveData(CompoundTag tag, CallbackInfo ci) {
        this.machinemod$savedMoveData = tag.contains(MACHINEMOD$TAG, Tag.TAG_COMPOUND)
                ? tag.getCompound(MACHINEMOD$TAG).copy() : null;
    }

    /**
     * Normal restore path: 2-tick piston animation completion.
     * Fires at TAIL of tick() — checks if the real block has been placed
     * (no longer MOVING_PISTON) and restores data if found.
     */
    /** Marks the destination while the moving block ticks: it may be put down in this tick (see isLanding). */
    @Inject(method = "tick", at = @At("HEAD"))
    private static void machinemod$beginLandingTick(Level level, BlockPos pos, BlockState state,
                                                    PistonMovingBlockEntity blockEntity, CallbackInfo ci) {
        if (!level.isClientSide()) MovingBlockEntityData.beginLanding(pos);
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private static void machinemod$endLandingTick(Level level, BlockPos pos, BlockState state,
                                                  PistonMovingBlockEntity blockEntity, CallbackInfo ci) {
        if (!level.isClientSide()) MovingBlockEntityData.endLanding(pos);
    }

    @Inject(method = "finalTick", at = @At("HEAD"))
    private void machinemod$beginLandingFinal(CallbackInfo ci) {
        PistonMovingBlockEntity self = (PistonMovingBlockEntity) (Object) this;
        if (self.getLevel() != null && !self.getLevel().isClientSide()) MovingBlockEntityData.beginLanding(self.getBlockPos());
    }

    @Inject(method = "finalTick", at = @At("RETURN"))
    private void machinemod$endLandingFinal(CallbackInfo ci) {
        PistonMovingBlockEntity self = (PistonMovingBlockEntity) (Object) this;
        if (self.getLevel() != null && !self.getLevel().isClientSide()) MovingBlockEntityData.endLanding(self.getBlockPos());
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private static void machinemod$restoreMovableBlockEntityData(
            Level level, BlockPos pos, BlockState state,
            PistonMovingBlockEntity blockEntity, CallbackInfo ci) {

        if (level.isClientSide()) return;

        BlockState currentState = level.getBlockState(pos);
        if (currentState.is(Blocks.MOVING_PISTON)) return;

        machinemod$tryRestore(level, pos, (MovingDataHolder) (Object) blockEntity);
    }

    /**
     * Fast restore path: 1-tick piston pulse completion.
     * finalTick() immediately places the real block without going through
     * the normal tick() animation cycle. We hook TAIL to restore data
     * after the block has been placed.
     *
     * NOTE: finalTick() is a non-static instance method (unlike tick() which
     * is static). The inject callback receives 'this' implicitly via the mixin.
     */
    @Inject(method = "finalTick", at = @At("TAIL"))
    private void machinemod$restoreMovableBlockEntityDataFinalTick(CallbackInfo ci) {
        PistonMovingBlockEntity self = (PistonMovingBlockEntity)(Object)this;
        Level level = self.getLevel();
        BlockPos pos = self.getBlockPos();

        if (level == null || level.isClientSide()) return;

        machinemod$tryRestore(level, pos, this);
    }

    /**
     * Shared restore logic for both tick() and finalTick() paths: the in-memory copy first,
     * then the copy this moving block was loaded with from the save.
     */
    @Unique
    private static void machinemod$tryRestore(Level level, BlockPos pos, MovingDataHolder holder) {
        String dimensionKey = level.dimension().location().toString();
        CompoundTag savedData = MovingBlockEntityData.retrieve(dimensionKey, pos);
        if (savedData == null && holder != null) savedData = holder.machinemod$getSavedMoveData();
        if (holder != null) holder.machinemod$clearSavedMoveData();

        if (savedData != null) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof IPistonMovable movable) {
                movable.loadMovementData(savedData);
                movable.onMovementRestored(level, pos);
            } else if (be != null) {
                Ideomod.LOGGER.warn("[RESTORE] Data found at {} but BE is {} (expected IPistonMovable)",
                        pos, be.getClass().getSimpleName());
            }
        }
    }
}
