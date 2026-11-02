package com.ideomancer.machinemod;

import com.ideomancer.machinemod.compat.IdeomodCompat;
import com.ideomancer.machinemod.compat.IdeomodPresence;
import com.ideomancer.machinemod.config.IdeomodConfig;
import com.ideomancer.machinemod.item.RedstoneRodSignalManager;
import com.ideomancer.machinemod.block.frame.FrameActivation;
import com.ideomancer.machinemod.block.frame.FramedBlock;
import com.ideomancer.machinemod.block.frame.FrameMaterial;
import com.ideomancer.machinemod.block.frame.IFrameBlock;
import com.ideomancer.machinemod.block.machine.MachineFrameBlock;
import com.ideomancer.machinemod.block.machine.IMachineFamily;
import com.ideomancer.machinemod.block.machine.MachineGrabberBlock;
import com.ideomancer.machinemod.block.machine.MachineSwapHelper;
import com.ideomancer.machinemod.network.ModNetworking;
import com.ideomancer.machinemod.registry.ModRecipeSerializers;
import com.ideomancer.machinemod.block.OddSlimeBlock;
import com.ideomancer.machinemod.block.OddstoneBlock;
import com.ideomancer.machinemod.block.movement.StickinessRules;
import com.ideomancer.machinemod.block.movement.StickyType;
import com.ideomancer.machinemod.block.portal.EnderPearlPortalHandler;
import com.ideomancer.machinemod.worldgen.ModFeatures;
import com.ideomancer.machinemod.registry.ModBlockEntities;
import com.ideomancer.machinemod.registry.ModBlocks;
import com.ideomancer.machinemod.registry.ModCreativeTabs;
import com.ideomancer.machinemod.registry.ModItems;
import com.ideomancer.machinemod.registry.ModMenuTypes;
import com.ideomancer.machinemod.registry.ModParticles;
import com.mojang.logging.LogUtils;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HoneyBlock;
import net.minecraft.world.level.block.SlimeBlock;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;


/**
 * Main mod class for Ideomod
 * A vanilla+ style overhaul mod by Ideomancer
 *
 * Updated by Claude, directed and guided by The Ideomancer.
 */
@Mod(Ideomod.MOD_ID)
public class Ideomod {
    public static final String MOD_ID = "machinemod";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Ideomod() {
        if (IdeomodPresence.STEP_ASIDE) {
            // Ideomod is installed and provides everything this mod does (see IdeomodCompat)
            LOGGER.info("Ideomod is installed: Ideomancer's Machine Mod steps aside and adds nothing");
            return;
        }
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        IdeomodCompat.register(modEventBus);

        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModCreativeTabs.CREATIVE_TABS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModMenuTypes.MENUS.register(modEventBus);
        ModParticles.PARTICLES.register(modEventBus);
        ModRecipeSerializers.RECIPE_SERIALIZERS.register(modEventBus);
        ModFeatures.FEATURES.register(modEventBus);
        ModFeatures.CHUNK_GENERATORS.register(modEventBus);

        // Register specialization mappings (item -> block) for machine swap system.
        MachineSwapHelper.registerMappings();

        modEventBus.addListener(this::commonSetup);

        MinecraftForge.EVENT_BUS.register(this);

        // Clear Redstone Rod phantom signals on server shutdown
        MinecraftForge.EVENT_BUS.addListener((ServerStoppingEvent e) ->
                RedstoneRodSignalManager.clearAll());

        LOGGER.info("Ideomod initializing!");
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        // Load config early so all systems have values ready
        IdeomodConfig.loadConfig();

        // Register framed block items for each material before stickiness rules
        FrameMaterial.registerFramedBlockItem(FrameMaterial.IRON, ModItems.IRON_FRAMED_BLOCK::get);
        FrameMaterial.registerFramedBlockItem(FrameMaterial.COPPER, ModItems.COPPER_FRAMED_BLOCK::get);

        // Register network packets (must be done in commonSetup, not constructor)
        ModNetworking.registerPackets();

        registerStickinessRules();
        registerPortalEventHandlers();
        registerFrameActivationTickHandler();
        LOGGER.info("Ideomod common setup complete!");
    }

    /**
     * Registers all stickiness rules for the mod.
     */
    private void registerStickinessRules() {
        // ================================================================
        // GRAB IMMUNITY RULES (type-based)
        // ================================================================

        // --- Rule 1: Non-Oddstone can't grab Oddstone (real, variants, or framed) ---
        StickinessRules.registerGrabImmunity(
                (grabber, target) -> {
                    boolean targetIsOddstone = target.getBlock() instanceof OddstoneBlock
                            || target.getBlock() instanceof com.ideomancer.machinemod.block.CobbledOddstoneBlock
                            || target.getBlock() instanceof com.ideomancer.machinemod.block.OddstoneBricksBlock;
                    boolean targetIsFramedOddstone = StickinessRules.isFramedOddstone(target);
                    if (!targetIsOddstone && !targetIsFramedOddstone) return false;

                    if (StickinessRules.isOddstoneType(grabber)) return false;

                    if (targetIsFramedOddstone && target.getValue(FramedBlock.ACTIVE)) {
                        if (MachineFrameBlock.isMachineFamily(grabber.getBlock())) return false;
                        if (StickinessRules.isActiveFrameConnection(grabber, target)) return false;
                    }

                    return true;
                }
        );

        // --- Rule 2: Machine Family can't grab sticky blocks ---
        // Exception: Powered Machine Grabbers can grab their matching sticky type.
        // Slime Grabber (powered) can grab SlimeBlock, Honey Grabber can grab HoneyBlock.
        StickinessRules.registerGrabImmunity(
                (grabber, target) -> {
                    if (!MachineFrameBlock.isMachineFamily(grabber.getBlock())) return false;
                    Block targetBlock = target.getBlock();
                    boolean targetIsSticky = targetBlock instanceof SlimeBlock
                        || targetBlock instanceof HoneyBlock
                        || targetBlock instanceof OddSlimeBlock;
                    if (!targetIsSticky) return false;

                    // Carve-out: powered grabber matching its sticky type
                    if (grabber.getBlock() instanceof MachineGrabberBlock grabberBlock
                            && grabber.getValue(MachineGrabberBlock.POWERED)) {
                        if (grabberBlock.getGrabberType() == MachineGrabberBlock.GrabberType.SLIME
                                && targetBlock instanceof SlimeBlock) {
                            return false; // Allow slime grabber to grab slime
                        }
                        if (grabberBlock.getGrabberType() == MachineGrabberBlock.GrabberType.HONEY
                                && targetBlock instanceof HoneyBlock) {
                            return false; // Allow honey grabber to grab honey
                        }
                    }

                    return true; // Block all other Machine Family from grabbing sticky blocks
                }
        );

        // --- Rule 3: Slime/honey can't grab Odd Slime (mutual exclusion) ---
        StickinessRules.registerGrabImmunity(
                (grabber, target) -> {
                    if (!(target.getBlock() instanceof OddSlimeBlock)) return false;
                    Block grabberBlock = grabber.getBlock();
                    return grabberBlock instanceof SlimeBlock
                        || grabberBlock instanceof HoneyBlock;
                }
        );

        // --- Rule 4: Frame Family can't grab sticky blocks ---
        StickinessRules.registerGrabImmunity(
                (grabber, target) -> {
                    if (!StickinessRules.isFrameFamily(grabber.getBlock())) return false;
                    Block targetBlock = target.getBlock();
                    StickyType targetType = StickyType.of(target);

                    if (targetBlock instanceof SlimeBlock || targetBlock instanceof HoneyBlock
                            || targetBlock instanceof OddSlimeBlock) {
                        if (StickinessRules.hasMatchingInnerSticky(grabber, targetType)) return false;
                        return true;
                    }

                    if (StickinessRules.isOddstoneType(target)) {
                        if (StickinessRules.hasMatchingInnerSticky(grabber, StickyType.ODDSTONE)) return false;
                        if (StickinessRules.isFramedOddstone(target) && target.getValue(FramedBlock.ACTIVE)) {
                            if (StickinessRules.isActiveFrameConnection(grabber, target)) return false;
                        }
                        return true;
                    }

                    return false;
                }
        );

        // --- Rule 5: A switched-on Grabber is grabbed like slime: only by what would grab slime ---
        // Machine blocks accept a switched-on Grabber's grip (IMachineFamily), so the Grabber can
        // hold them as slime does. That agreement must stay one-way: a moving machine block does not
        // drag a switched-on Grabber along, just as it doesn't drag slime (Rule 2). It still comes
        // along with machine blocks that stick to Grabbers by type (Machine Bases, its own kind), and
        // a switched-on Grabber that moves still grabs everything beside it.
        StickinessRules.registerGrabImmunity(
                (grabber, target) -> {
                    if (!(target.getBlock() instanceof MachineGrabberBlock targetGrabber)
                            || !target.getValue(MachineGrabberBlock.POWERED)) return false;
                    if (!(grabber.getBlock() instanceof IMachineFamily grabberMachine)) return false;
                    if (grabber.getBlock() instanceof MachineGrabberBlock
                            && grabber.getValue(MachineGrabberBlock.POWERED)) return false;
                    return !grabberMachine.getMachineType().canStickToMachineType(targetGrabber.getMachineType());
                }
        );

        // ================================================================
        // DIRECTIONAL RULES
        // ================================================================

        StickinessRules.registerDirectionalRule(
                (state, pushDirection) -> {
                    if (!(state.getBlock() instanceof OddSlimeBlock)) return false;
                    return pushDirection != state.getValue(OddSlimeBlock.FACING);
                }
        );

        LOGGER.info("Registered stickiness rules: 4 grab immunity + 1 directional");
    }

    private void registerFrameActivationTickHandler() {
        MinecraftForge.EVENT_BUS.register(new FrameActivation.TickHandler());
        LOGGER.info("Registered frame activation tick handler");
    }

    private void registerPortalEventHandlers() {
        MinecraftForge.EVENT_BUS.register(new EnderPearlPortalHandler());
        LOGGER.info("Registered ender pearl portal activation handler");
    }
}
