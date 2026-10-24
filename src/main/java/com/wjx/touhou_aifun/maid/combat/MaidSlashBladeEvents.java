package com.wjx.touhou_aifun.maid.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.wjx.touhou_aifun.TouhouAIFun;
import com.wjx.touhou_aifun.compat.slashblade.SlashBladeCombat;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = TouhouAIFun.MOD_ID)
public final class MaidSlashBladeEvents {
    private MaidSlashBladeEvents() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void joined(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !MaidSlashBladeCombat.installed()) return;
        SlashBladeCombat.track(event.getEntity());
        if (event.getEntity().isRemoved()) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void impact(ProjectileImpactEvent event) {
        if (!MaidSlashBladeCombat.installed() || !(event.getRayTraceResult() instanceof EntityHitResult hit)) return;
        Entity projectile = event.getProjectile();
        if (projectile.level().isClientSide) return;
        if (SlashBladeCombat.isBladeSource(projectile) && !SlashBladeCombat.allowHit(projectile, hit.getEntity())) {
            event.setCanceled(true); // Before sword subclasses can toss, stun or ignite the victim.
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void damage(LivingAttackEvent event) {
        if (event.getEntity().level().isClientSide || !MaidSlashBladeCombat.installed()) return;
        Entity source = event.getSource().getDirectEntity();
        if (source == null) source = event.getSource().getEntity();
        if (source != null && SlashBladeCombat.isBladeSource(source)
                && !SlashBladeCombat.allowHit(source, event.getEntity())) event.setCanceled(true);
    }

    @SubscribeEvent public static void died(LivingDeathEvent event) {
        if (event.getEntity() instanceof EntityMaid maid) MaidSlashBladeCombat.stop(maid);
    }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void hit(LivingHurtEvent event) {
        if (event.getEntity().level().isClientSide || event.isCanceled() || event.getAmount() <= 0
                || !MaidSlashBladeCombat.installed()) return;
        Entity source = event.getSource().getDirectEntity();
        if (source == null || !SlashBladeCombat.isBladeSource(source)) return;
        EntityMaid maid = SlashBladeCombat.owner(source);
        // CR's raw non-player damage branch does not set this vanilla hit memory itself.
        if (maid != null && MaidSlashBladeCombat.controls(maid) && SlashBladeCombat.allowHit(source, event.getEntity())) {
            maid.setLastHurtMob(event.getEntity());
        }
    }
    @SubscribeEvent public static void left(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof EntityMaid maid
                && MaidSlashBladeCombat.installed()) SlashBladeCombat.unload(maid);
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) {
        if (MaidSlashBladeCombat.installed()) SlashBladeCombat.clear();
    }
}
