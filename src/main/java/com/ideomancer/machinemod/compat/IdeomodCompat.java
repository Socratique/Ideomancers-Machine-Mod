package com.ideomancer.machinemod.compat;

import com.ideomancer.machinemod.Ideomod;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.IForgeRegistry;
import net.minecraftforge.registries.MissingMappingsEvent;
import net.minecraftforge.registries.RegisterEvent;

import java.nio.file.Path;

/**
 * Living alongside Ideomod.
 *
 * The Machine Mod is a cut of Ideomod: everything in it, Ideomod has too, under the same names in
 * Ideomod's namespace (machinemod:oddstone is ideomod:oddstone). So:
 *
 * - Both installed (IdeomodPresence.STEP_ASIDE): this mod steps aside. It registers nothing,
 *   applies no mixins (see MixinGate) and adds no data, and Ideomod provides everything. Ideomod
 *   translates machinemod: names in a world to its own, so nothing built with this mod is lost.
 * - This mod alone: Ideomod's names translate to ours, so an Ideomod world opens here with its
 *   Oddstone and machines intact (blocks only Ideomod has are still dropped).
 *
 * All of this mod's data (recipes, loot, tags, worldgen) lives in a built-in data pack,
 * machinemod_data/, added only when this mod is active: the worldgen and tag files name this mod's
 * blocks, and would stop a world loading if those blocks were not registered.
 */
public final class IdeomodCompat {
    private static final String IDEOMOD_ID = IdeomodPresence.IDEOMOD_ID;
    private static final String DATA_PACK_FOLDER = "machinemod_data";

    private IdeomodCompat() {}

    /** Called from the mod constructor when this mod is active. */
    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(EventPriority.LOWEST, IdeomodCompat::addBlockEntityAliases);
        modEventBus.addListener(IdeomodCompat::addDataPack);
        MinecraftForge.EVENT_BUS.addListener(IdeomodCompat::onMissingMappings);
    }

    // ---- Ideomod's names -> ours ----

    /**
     * Block entity types are not saved with the world, so Forge raises no missing-mappings event for
     * them: an alias is the only way a chunk's "ideomod:machine_block" entity finds our type (and
     * keeps the machine's contents).
     */
    private static void addBlockEntityAliases(RegisterEvent event) {
        if (event.getRegistryKey().equals(ForgeRegistries.Keys.BLOCK_ENTITY_TYPES)) {
            addAliases(ForgeRegistries.BLOCK_ENTITY_TYPES);
        }
    }

    private static <T> void addAliases(IForgeRegistry<T> registry) {
        ForgeRegistry<T> forgeRegistry = (ForgeRegistry<T>) registry;
        for (ResourceLocation key : registry.getKeys()) {
            if (key.getNamespace().equals(Ideomod.MOD_ID)) {
                forgeRegistry.addAlias(new ResourceLocation(IDEOMOD_ID, key.getPath()), key);
            }
        }
    }

    /** Blocks and items are saved with the world; Forge asks about the ones it cannot find. */
    private static void onMissingMappings(MissingMappingsEvent event) {
        remap(event, ForgeRegistries.Keys.BLOCKS, ForgeRegistries.BLOCKS);
        remap(event, ForgeRegistries.Keys.ITEMS, ForgeRegistries.ITEMS);
    }

    private static <T> void remap(MissingMappingsEvent event, ResourceKey<? extends Registry<T>> key,
                                  IForgeRegistry<T> registry) {
        for (MissingMappingsEvent.Mapping<T> mapping : event.getMappings(key, IDEOMOD_ID)) {
            ResourceLocation ours = new ResourceLocation(Ideomod.MOD_ID, mapping.getKey().getPath());
            if (registry.containsKey(ours)) {
                mapping.remap(registry.getValue(ours));
            }
        }
    }

    // ---- the data pack ----

    private static void addDataPack(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.SERVER_DATA) return;
        Path root = ModList.get().getModFileById(Ideomod.MOD_ID).getFile().findResource(DATA_PACK_FOLDER);
        event.addRepositorySource(consumer -> {
            Pack pack = Pack.readMetaAndCreate(DATA_PACK_FOLDER, Component.literal("Ideomancer's Machine Mod"),
                    true, id -> new PathPackResources(id, root, true),
                    PackType.SERVER_DATA, Pack.Position.TOP, PackSource.BUILT_IN);
            if (pack != null) consumer.accept(pack);
        });
    }
}
