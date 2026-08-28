package com.wjx.touhou_aifun.maid;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.wjx.touhou_aifun.TouhouAIFun;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Applies the friendly-fire setting to damage dealt by a maid as well as damage dealt to her. */
@Mod.EventBusSubscriber(modid = TouhouAIFun.MOD_ID)
public final class PublicMaidCombatEvents {
    private PublicMaidCombatEvents() {
    }

    @SubscribeEvent
    public static void onMaidAttacksAlly(LivingAttackEvent event) {
        if (event.getEntity() instanceof Player player
                && event.getSource().getEntity() instanceof EntityMaid maid
                && maid instanceof PublicMaidData data
                && !data.touhouAIFun$isFriendlyFireAllowed()
                && maid.isAlliedTo(player)) {
            event.setCanceled(true);
        }
    }
}
