package com.wjx.touhou_aifun.maid.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.wjx.touhou_aifun.compat.slashblade.SlashBladeCombat;
import com.wjx.touhou_aifun.compat.slashblade.SlashBladeMixinPlugin;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import net.minecraftforge.fml.ModList;

/** The only entry from mandatory classes into the optional integration. */
public final class MaidSlashBladeCombat {
    private MaidSlashBladeCombat() {}

    public static boolean installed() {
        return ModList.get().isLoaded("slashblade") && SlashBladeMixinPlugin.supported();
    }

    public static boolean enabled() {
        return installed() && (!TouhouAIFunConfig.SPEC.isLoaded() || TouhouAIFunConfig.SLASHBLADE_ENABLED.get());
    }

    public static boolean controls(EntityMaid maid) {
        return enabled() && SlashBladeCombat.canControl(maid);
    }

    public static void tick(EntityMaid maid) {
        if (installed() && !maid.level().isClientSide) SlashBladeCombat.tick(maid);
    }

    public static void stop(EntityMaid maid) {
        if (!maid.level().isClientSide && installed()) SlashBladeCombat.stop(maid);
    }
}
