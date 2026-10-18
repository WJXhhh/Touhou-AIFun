package com.wjx.touhou_aifun.maid.combat;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidHurtEvent;
import com.github.tartaricacid.touhoulittlemaid.api.event.MaidHurtTarget;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.wjx.touhou_aifun.TouhouAIFun;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * Event-driven combat reflex. This is the instant path: damage is observed in the
 * same server tick and the target is written directly, without waiting for the
 * base mod's 20-tick target sensor.
 */
@Mod.EventBusSubscriber(modid = TouhouAIFun.MOD_ID)
public final class MaidCombatEvents {
    private MaidCombatEvents() {
    }

    @SubscribeEvent
    public static void onMaidHurt(MaidHurtEvent event) {
        try {
            if (event.isCanceled() || event.getAmount() <= 0) {
                return;
            }
            EntityMaid maid = event.getMaid();
            if (!(maid.level() instanceof ServerLevel)) {
                return;
            }
            LivingEntity attacker = MaidCombatPolicy.attacker(event.getSource());
            if (attacker == null || attacker == maid) {
                return;
            }
            MaidCombatReflex.trigger(maid, attacker, false, true);
        } catch (RuntimeException error) {
            TouhouAIFun.LOGGER.debug("[combat] maid hurt handler failed", error);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onOwnerHurt(LivingHurtEvent event) {
        try {
            if (event.isCanceled() || event.getAmount() <= 0) {
                return;
            }
            if (!(event.getEntity() instanceof Player player)
                    || !(player.level() instanceof ServerLevel level)) {
                return;
            }
            LivingEntity attacker = MaidCombatPolicy.attacker(event.getSource());
            if (attacker == null || attacker == player) {
                return;
            }
            int radius = ownerProtectRadius();
            if (radius <= 0) {
                return;
            }
            List<EntityMaid> maids = level.getEntitiesOfClass(EntityMaid.class,
                    player.getBoundingBox().inflate(radius),
                    maid -> maid.isAlive() && player.getUUID().equals(maid.getOwnerUUID()));
            for (EntityMaid maid : maids) {
                MaidCombatReflex.trigger(maid, attacker, true, false);
            }
        } catch (RuntimeException error) {
            TouhouAIFun.LOGGER.debug("[combat] owner hurt handler failed", error);
        }
    }

    /** A cleared target deals no melee damage until its suppression expires. */
    @SubscribeEvent
    public static void onMaidHurtTarget(MaidHurtTarget.Pre event) {
        try {
            EntityMaid maid = event.getMaid();
            if (maid != null && event.getTarget() != null
                    && MaidCombatReflex.isSuppressed(maid, event.getTarget())) {
                event.setCanceled(true);
            }
        } catch (RuntimeException error) {
            TouhouAIFun.LOGGER.debug("[combat] suppressed target handler failed", error);
        }
    }

    @SubscribeEvent
    public static void onMaidDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof EntityMaid maid) {
            MaidCombatReflex.clear(maid);
        }
    }

    @SubscribeEvent
    public static void onMaidLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof EntityMaid maid) {
            MaidCombatReflex.remove(maid.getUUID());
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        MaidCombatReflex.serverStopped();
    }

    private static int ownerProtectRadius() {
        return TouhouAIFunConfig.SPEC.isLoaded()
                ? TouhouAIFunConfig.COMBAT_OWNER_PROTECT_RADIUS.get() : 16;
    }
}
