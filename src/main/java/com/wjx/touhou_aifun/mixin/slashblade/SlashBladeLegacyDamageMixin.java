package com.wjx.touhou_aifun.mixin.slashblade;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import mods.flammpfeil.slashblade.util.AttackManager;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** CR's non-player melee branch omits the supplied combo ratio. Scope the repair to maids. */
@Pseudo
@Mixin(targets = "mods.flammpfeil.slashblade.util.AttackManager", remap = false)
public abstract class SlashBladeLegacyDamageMixin {
    @Redirect(method = "doMeleeAttack(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/Entity;ZZF)V",
            at = @At(value = "INVOKE", target = "Lmods/flammpfeil/slashblade/util/AttackManager;doAttackWith(Lnet/minecraft/world/damagesource/DamageSource;FLnet/minecraft/world/entity/Entity;ZZ)V"))
    private static void touhouAIFun$maidComboRatio(DamageSource source, float damage, Entity victim,
                                                  boolean force, boolean reset, LivingEntity attacker,
                                                  Entity target, boolean forceHit, boolean resetHit, float ratio) {
        AttackManager.doAttackWith(source, attacker instanceof EntityMaid ? damage * ratio : damage, victim, force, reset);
    }
}
