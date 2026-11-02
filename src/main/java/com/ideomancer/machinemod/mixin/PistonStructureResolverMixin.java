package com.ideomancer.machinemod.mixin;

import com.ideomancer.machinemod.block.frame.FramedBlock;
import com.ideomancer.machinemod.block.frame.FramedBlockEntity;
import com.ideomancer.machinemod.block.frame.IFrameBlock;
import com.ideomancer.machinemod.block.machine.MachineBraceBlock;
import com.ideomancer.machinemod.block.machine.MachineFrameBlock;
import com.ideomancer.machinemod.block.piston.RangedPistonFlags;
import com.ideomancer.machinemod.block.movement.MovementScanHelper;
import com.ideomancer.machinemod.block.movement.StickinessRules;
import com.ideomancer.machinemod.config.IdeomodConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Mixin into PistonStructureResolver for Ideomod features.
 *
 * See original file for full feature documentation.
 *
 * Updated by Claude, directed and guided by The Ideomancer.
 */
@Mixin(PistonStructureResolver.class)
public abstract class PistonStructureResolverMixin {

    // ====================================================================
    // SHADOWED FIELDS AND METHODS
    // ====================================================================

    @Shadow @Final private Level level;
    @Shadow @Final private List<BlockPos> toPush;
    @Shadow @Final private Direction pushDirection;
    @Shadow @Final @Mutable private BlockPos startPos;
    @Shadow protected abstract boolean addBlockLine(BlockPos pos, Direction dir);

    @Unique
    private static final ThreadLocal<Boolean> machinemod$inBranching = ThreadLocal.withInitial(() -> false);

    @Unique
    private static final ThreadLocal<Integer> machinemod$branchDepth = ThreadLocal.withInitial(() -> 0);

    @Unique
    private static final ThreadLocal<Boolean> machinemod$firstCanStickToCall = ThreadLocal.withInitial(() -> true);

    // ====================================================================
    // FEATURE 4: RANGED PISTON START POSITION REDIRECT
    // ====================================================================

    @Inject(method = "resolve", at = @At("HEAD"))
    private void machinemod$redirectStartPos(CallbackInfoReturnable<Boolean> cir) {
        // Every resolve starts outside branching (an exception thrown mid-resolve can't leak state).
        machinemod$branchDepth.set(0);
        machinemod$inBranching.set(false);

        // Flush any pending frame activation changes BEFORE the resolver
        // reads any blockstate. Without this, frames activated by markDirty()
        // during the current tick would still show ACTIVE=false during the
        // resolver's addBlockLine and addBranchingBlocks phases, causing
        // vanilla to skip them for stickiness checks. The second push would
        // work because end-of-tick processing catches up. (Bug fix)
        com.ideomancer.machinemod.block.frame.FrameActivation.flushIfDirty(this.level);

        BlockPos target = RangedPistonFlags.getTarget();
        if (target != null) {
            this.startPos = target;
        }
    }

    // ====================================================================
    // FEATURE 8: MACHINE BRACE DYNAMIC PUSH LIMIT
    // ====================================================================

    /**
     * Replaces the hardcoded push limit constant (12) in addBlockLine() with
     * a dynamic value based on how many Machine Brace blocks are already in
     * the toPush list.
     *
     * The cascading behavior is automatic: as the resolver gathers blocks and
     * encounters Braces, the limit increases, allowing it to gather more blocks
     * which may include more Braces. Each Brace adds +6 to the limit, up to
     * a hard cap of 60 (8 Braces). A Brace only counts if it was discovered
     * within the limit that existed when it was found -- this is inherent in
     * how the resolver works, since blocks beyond the current limit are never
     * reached.
     *
     * The constant 12 appears in addBlockLine in two comparison points:
     * - Forward walk: if (this.toPush.size() >= 12) return false;
     * - Backward walk: the same size check
     * @ModifyConstant targets ALL occurrences of the int constant 12 in the
     * method. The replacement reads the current toPush list to count Braces.
     */
    @ModifyConstant(method = "addBlockLine", constant = @Constant(intValue = 12))
    private int machinemod$dynamicPushLimit(int originalLimit) {
        return machinemod$calculatePushLimit();
    }

    /**
     * Calculates the current push limit based on Machine Braces in toPush.
     * Base limit is 12. Each Brace adds 6. Cap at 60 (8 Braces max).
     *
     * This method is called every time vanilla checks the push limit during
     * addBlockLine. It's lightweight: just iterates toPush (which is small,
     * max 60 entries) and counts MachineBraceBlock instances.
     */
    @Unique
    private int machinemod$calculatePushLimit() {
        int braceCount = 0;
        for (BlockPos pos : this.toPush) {
            if (this.level.getBlockState(pos).getBlock() instanceof MachineBraceBlock) {
                braceCount++;
                if (braceCount >= MachineBraceBlock.getMaxBraces()) break;
            }
        }

        int limit = IdeomodConfig.getBasePushLimit() + (braceCount * MachineBraceBlock.getPushLimitPerBrace());
        return Math.min(limit, MachineBraceBlock.getMaxPushLimit());
    }

    // ====================================================================
    // PHASE DETECTION
    // ====================================================================

    // addBranchingBlocks can nest (a grabbed line that collides with blocks already in toPush
    // branches them again), and it has an early "return false". So "in branching" is a depth, not a
    // flag: counted up on entry and down on EVERY return. With a flag cleared at TAIL, an early
    // return left it set (logged as a "defensive reset" on the next run), and a nested call cleared
    // it while the outer call was still checking neighbours, skipping the grab-immunity rules there.
    @Inject(method = "addBranchingBlocks", at = @At("HEAD"))
    private void machinemod$enterBranching(CallbackInfoReturnable<Boolean> cir) {
        machinemod$branchDepth.set(machinemod$branchDepth.get() + 1);
        machinemod$inBranching.set(true);
        // Defensive reset for call-pair toggle too
        machinemod$firstCanStickToCall.set(true);
    }

    @Inject(method = "addBranchingBlocks", at = @At("RETURN"))
    private void machinemod$exitBranching(CallbackInfoReturnable<Boolean> cir) {
        int depth = Math.max(0, machinemod$branchDepth.get() - 1);
        machinemod$branchDepth.set(depth);
        machinemod$inBranching.set(depth > 0);
    }

    // ====================================================================
    // FEATURE 1A + 2A: ONE-WAY & DIRECTIONAL STICKINESS -- SIDEWAYS GRABS
    // ====================================================================

    @Inject(method = "addBlockLine", at = @At("HEAD"), cancellable = true)
    private void machinemod$blockOneWayGrabs(BlockPos pos, Direction dir,
                                           CallbackInfoReturnable<Boolean> cir) {

        // --- Feature 5: Framed block immovable inner check (ALL phases) ---
        {
            BlockState targetState = this.level.getBlockState(pos);
            boolean isFramedImmovable = !targetState.isAir() && machinemod$isFramedBlockWithImmovableInner(targetState, pos);
            if (isFramedImmovable) {
                cir.setReturnValue(false);
                return;
            }
        }

        // --- One-way stickiness checks only apply during branching ---
        if (!machinemod$inBranching.get()) return;

        BlockPos grabberPos = pos.relative(dir.getOpposite());
        if (!this.toPush.contains(grabberPos)) return;

        BlockState grabberState = this.level.getBlockState(grabberPos);
        BlockState neighborState = this.level.getBlockState(pos);

        if (neighborState.isAir()) return;

        if (grabberState.isStickyBlock()) {
            // Standard directional check (covers real Odd Slime via registered rule)
            if (StickinessRules.isDirectionalGrabBlocked(grabberState, this.pushDirection)) {
                if (!machinemod$wouldFrameStickinessAllow(grabberState, neighborState)) {
                    cir.setReturnValue(true);
                    return;
                }
            }

            // Framed Odd Slime directional check (not registered as a rule)
            if (machinemod$isFramedOddSlimeDirectionallyBlocked(grabberState)) {
                if (!machinemod$wouldFrameStickinessAllow(grabberState, neighborState)) {
                    cir.setReturnValue(true);
                    return;
                }
            }

            // Standard grab immunity check
            if (StickinessRules.isGrabBlocked(grabberState, neighborState)) {
                cir.setReturnValue(true);
                return;
            }
        }
    }

    // ====================================================================
    // FEATURE 1B + 2B: STICKINESS RULES -- CHAIN COHESION (BACKWARD WALK)
    // ====================================================================

    @Redirect(method = "addBlockLine",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/world/level/block/state/BlockState;canStickTo(Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean machinemod$checkCanStickToWithRules(BlockState self, BlockState other) {
        boolean isFirstCall = machinemod$firstCanStickToCall.get();
        machinemod$firstCanStickToCall.set(!isFirstCall);

        // First: respect the original canStickTo result
        if (!self.canStickTo(other)) {
            if (isFirstCall) {
                machinemod$firstCanStickToCall.set(true);
            }
            return false;
        }

        // --- Directional checks (apply on BOTH calls) ---
        if (StickinessRules.isDirectionalGrabBlocked(self, this.pushDirection)) {
            if (!machinemod$wouldFrameStickinessAllow(self, other)) {
                if (isFirstCall) machinemod$firstCanStickToCall.set(true);
                return false;
            }
        }
        if (machinemod$isFramedOddSlimeDirectionallyBlocked(self)) {
            if (!machinemod$wouldFrameStickinessAllow(self, other)) {
                if (isFirstCall) machinemod$firstCanStickToCall.set(true);
                return false;
            }
        }
        if (StickinessRules.isDirectionalGrabBlocked(other, this.pushDirection)) {
            if (!machinemod$wouldFrameStickinessAllow(other, self)) {
                if (isFirstCall) machinemod$firstCanStickToCall.set(true);
                return false;
            }
        }
        if (machinemod$isFramedOddSlimeDirectionallyBlocked(other)) {
            if (!machinemod$wouldFrameStickinessAllow(other, self)) {
                if (isFirstCall) machinemod$firstCanStickToCall.set(true);
                return false;
            }
        }

        // --- Grab immunity (FIRST CALL ONLY) ---
        if (isFirstCall) {
            if (StickinessRules.isGrabBlocked(self, other)) {
                machinemod$firstCanStickToCall.set(true);
                return false;
            }
        }

        // Reset toggle
        if (!isFirstCall) {
            machinemod$firstCanStickToCall.set(true);
        }

        return true;
    }

    // ====================================================================
    // FRAMED ODD SLIME HELPERS
    // ====================================================================

    @Unique
    private boolean machinemod$isFramedOddSlimeDirectionallyBlocked(BlockState state) {
        if (!(state.getBlock() instanceof FramedBlock)) return false;
        if (state.getValue(FramedBlock.INNER_STICKY) != FramedBlock.STICKY_ODD_SLIME) return false;
        Direction facing = state.getValue(FramedBlock.FACING);
        return !this.pushDirection.equals(facing);
    }

    @Unique
    private boolean machinemod$wouldFrameStickinessAllow(BlockState grabberState, BlockState neighborState) {
        if (!(grabberState.getBlock() instanceof FramedBlock framedBlock)) return false;
        if (!grabberState.getValue(FramedBlock.ACTIVE)) return false;

        if (neighborState.getBlock() instanceof IFrameBlock otherFrame) {
            // Same frame material only — no cross-material mediation
            return otherFrame.getFrameMaterial() == framedBlock.getFrameMaterial();
        }

        if (MachineFrameBlock.isMachineFamily(neighborState.getBlock())) return true;

        return false;
    }

    // ====================================================================
    // FEATURE 3: ODDSTONE SYMPATHETIC MOVEMENT
    // ====================================================================

    @Inject(method = "addBranchingBlocks", at = @At("TAIL"), cancellable = true)
    private void machinemod$sympatheticSearch(CallbackInfoReturnable<Boolean> cir) {
        // NOTE: flushIfDirty is called at resolve() HEAD, not here.
        // It must run BEFORE vanilla's addBranchingBlocks checks isStickyBlock()
        // and canStickTo(), not after. See machinemod$redirectStartPos.

        // --- Feature 3: Sympathetic movement (channel-aware) ---
        // Each sympathetic channel (oddstone, cobbled_oddstone, oddstone_bricks,
        // oddstone_wall, etc.) is scanned independently. Blocks only link with
        // others on the same channel.
        int radius = IdeomodConfig.getSympatheticRadius();

        java.util.Map<String, java.util.Set<BlockPos>> channelResults =
                MovementScanHelper.scanAllChannels(level, new java.util.HashSet<>(this.toPush), radius);

        boolean addedAny = false;

        for (java.util.Map.Entry<String, java.util.Set<BlockPos>> entry : channelResults.entrySet()) {
            String channel = entry.getKey();
            java.util.Set<BlockPos> channelFound = entry.getValue();

            for (BlockPos checkPos : channelFound) {
                if (!this.toPush.contains(checkPos)) {
                    // Find a source block on the same channel in toPush to derive direction
                    List<BlockPos> snapshot = new ArrayList<>(this.toPush);
                    boolean foundSource = false;
                    for (BlockPos sourcePos : snapshot) {
                        String sourceChannel = MovementScanHelper.getSympatheticChannel(level, sourcePos);
                        if (!channel.equals(sourceChannel)) continue;

                        // Derive the axis-aligned direction from sourcePos to checkPos.
                        // Sympathetic links are always axis-aligned (the scan walks
                        // along cardinal directions), so exactly one axis will differ.
                        Direction linkDir = machinemod$getAxisDirection(sourcePos, checkPos);
                        if (linkDir != null) {
                            // Push limit: a linked block that would itself
                            // take the move over the limit is left behind, and the rest of the
                            // move goes ahead. But once it joins, whatever it pushes or pulls
                            // must fit: if vanilla refuses its line (over the limit, or blocked)
                            // the whole move fails, as a vanilla piston would. (The result used
                            // to be ignored, so such a block was dropped and the move went on.)
                            if (this.toPush.size() >= machinemod$calculatePushLimit()) {
                                foundSource = true;   // left behind; don't try other sources
                                break;
                            }
                            boolean result = this.addBlockLine(checkPos, linkDir);
                            if (!result) {
                                cir.setReturnValue(false);
                                return;
                            }
                            if (this.toPush.contains(checkPos)) {
                                addedAny = true;
                                foundSource = true;
                                break;
                            }
                        }
                    }
                }
            }
        }

        if (addedAny) {
            cir.setReturnValue(true);
        }

        // --- Feature 7: Immovable framed block anchor check ---
        // After all blocks have been gathered (branching + metals + sympathetic),
        // check if any block in toPush is adjacent to a framed block with an
        // immovable inner (obsidian, crying obsidian, reinforced deepslate, etc.)
        // that would be frame-sticky with it. If so, that immovable block anchors
        // the entire group — the move must fail.
        //
        // This catches the case that vanilla's backward walk misses: when an
        // immovable block is adjacent to a group member, vanilla's isPushable
        // check silently stops the walk (returns true = "fine, stop here").
        // But for frame-sticky immovable blocks, we need to treat them as
        // anchors that prevent the entire group from moving.
        if (machinemod$hasImmovableFrameAnchor()) {
            cir.setReturnValue(false);
        }
    }

    // ====================================================================
    // SYMPATHETIC LINK DIRECTION HELPER
    // ====================================================================

    /**
     * Derives the cardinal Direction from 'from' to 'to', IF the two positions
     * are axis-aligned (differ on exactly one axis). Returns null if not
     * axis-aligned. Used by sympathetic movement to determine which direction
     * to pass to addBlockLine when adding a found oddstone.
     */
    @Unique
    private static Direction machinemod$getAxisDirection(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dy = to.getY() - from.getY();
        int dz = to.getZ() - from.getZ();

        // Exactly one axis must be nonzero for axis-aligned link
        int nonZero = (dx != 0 ? 1 : 0) + (dy != 0 ? 1 : 0) + (dz != 0 ? 1 : 0);
        if (nonZero != 1) return null;

        if (dx > 0) return Direction.EAST;
        if (dx < 0) return Direction.WEST;
        if (dy > 0) return Direction.UP;
        if (dy < 0) return Direction.DOWN;
        if (dz > 0) return Direction.SOUTH;
        return Direction.NORTH;
    }

    // ====================================================================
    // FEATURE 7: IMMOVABLE FRAME ANCHOR DETECTION
    // ====================================================================

    /**
     * Checks if any block in toPush is adjacent to a framed block with an
     * immovable inner that is frame-sticky with it. If so, the immovable
     * block acts as an anchor and the entire move must fail.
     *
     * Only checks blocks NOT already in toPush (blocks in toPush were already
     * handled by Feature 5's addBlockLine HEAD check).
     */
    @Unique
    private boolean machinemod$hasImmovableFrameAnchor() {
        for (BlockPos pushedPos : this.toPush) {
            BlockState pushedState = this.level.getBlockState(pushedPos);

            for (Direction dir : Direction.values()) {
                BlockPos neighborPos = pushedPos.relative(dir);
                if (this.toPush.contains(neighborPos)) continue;

                BlockState neighborState = this.level.getBlockState(neighborPos);
                if (!machinemod$isFramedBlockWithImmovableInner(neighborState, neighborPos)) continue;

                // Immovable framed block must be active to form a frame stickiness
                // connection. Inactive framed obsidian is just inert — not connected
                // to the group, so it doesn't anchor anything.
                if (neighborState.getBlock() instanceof FramedBlock
                    && !neighborState.getValue(FramedBlock.ACTIVE)) continue;

                // Check frame stickiness in both directions
                if (machinemod$wouldFrameStickinessAllow(pushedState, neighborState)
                    || machinemod$wouldFrameStickinessAllow(neighborState, pushedState)) {
                    return true;
                }

                // Also check if the pushed block is a Machine Family member
                // adjacent to an active immovable framed block (machine blocks
                // stick to all active frame family members)
                if (MachineFrameBlock.isMachineFamily(pushedState.getBlock())
                    && neighborState.getBlock() instanceof IFrameBlock
                    && IFrameBlock.isActive(neighborState)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ====================================================================
    // FEATURE 5: FRAMED BLOCK IMMOVABLE INNER CHECK
    // ====================================================================

    @Unique
    private boolean machinemod$isFramedBlockWithImmovableInner(BlockState state, BlockPos pos) {
        if (!(state.getBlock() instanceof FramedBlock)) return false;

        BlockEntity be = this.level.getBlockEntity(pos);
        if (!(be instanceof FramedBlockEntity fbe) || !fbe.hasInnerBlock()) return false;

        BlockState innerState = fbe.getInnerDefaultState();
        if (innerState.is(net.minecraft.world.level.block.Blocks.OBSIDIAN)) return true;
        if (innerState.is(net.minecraft.world.level.block.Blocks.CRYING_OBSIDIAN)) return true;
        if (innerState.is(net.minecraft.world.level.block.Blocks.REINFORCED_DEEPSLATE)) return true;
        if (innerState.getDestroySpeed(this.level, pos) == -1.0f) return true;

        return false;
    }
}
