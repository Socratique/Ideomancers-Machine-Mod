package com.ideomancer.machinemod.registry;

import com.ideomancer.machinemod.compat.IdeomodPresence;
import com.ideomancer.machinemod.config.IdeomodConfig;
import com.ideomancer.machinemod.Ideomod;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Registers all custom commands for the mod.
 * 
 * Commands are registered on the FORGE event bus (not MOD bus)
 * using RegisterCommandsEvent, which fires when the server starts.
 * 
 * Current commands:
 *                Requires op level 2 (same as /give, /tp, etc.)
 *   /machinemod reload - Reloads the machinemod.json config from disk
 *                      Requires op level 2
 */
@Mod.EventBusSubscriber(modid = Ideomod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ModCommands {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        if (IdeomodPresence.STEP_ASIDE) return;
        event.getDispatcher().register(
            Commands.literal("machinemod")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("reload")
                    .executes(context -> {
                        IdeomodConfig.reloadConfig();
                        context.getSource().sendSuccess(
                                () -> Component.literal("Ideomod config reloaded."), true);
                        Ideomod.LOGGER.info("Config reloaded by command");
                        return 1;
                    })
                )
        );
    }
}
