package dev.modnet.forge;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;

/** Entry point for the Forge side of ModNet. */
@Mod(ModNetForge.MOD_ID)
public final class ModNetForge {
    public static final String MOD_ID = "modnet";

    public ModNetForge() {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, ModNetForgeClient::init);
        DistExecutor.unsafeRunWhenOn(Dist.DEDICATED_SERVER, ModNetForgeServer::init);
    }
}
