package com.ideomancer.machinemod.compat;

import net.minecraftforge.fml.loading.LoadingModList;

/**
 * Whether Ideomod is installed. Kept apart from IdeomodCompat, with no game classes in it,
 * because MixinGate reads it while mixins are still being set up.
 */
public final class IdeomodPresence {
    public static final String IDEOMOD_ID = "ideomod";

    /** True when Ideomod is installed: this mod then steps aside (see IdeomodCompat). */
    public static final boolean STEP_ASIDE =
            LoadingModList.get() != null && LoadingModList.get().getModFileById(IDEOMOD_ID) != null;

    private IdeomodPresence() {}
}
