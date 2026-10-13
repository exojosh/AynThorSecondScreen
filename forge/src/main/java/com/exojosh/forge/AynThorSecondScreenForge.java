package com.exojosh.forge;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;

/**
 * Main Forge mod entry point for AYN Thor Second Screen.
 */
@Mod("aynthor_secondscreen")
public class AynThorSecondScreenForge {

    public static final String MOD_ID = "aynthor_secondscreen";

    public AynThorSecondScreenForge() {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            MinecraftForge.EVENT_BUS.register(ThorHudForgeClient.class);
            MinecraftForge.EVENT_BUS.register(GameHudVisibilityForge.class);
            MinecraftForge.EVENT_BUS.register(ChatRelayForge.class);
            ThorHudForgeClient.init();
        });
    }
}
