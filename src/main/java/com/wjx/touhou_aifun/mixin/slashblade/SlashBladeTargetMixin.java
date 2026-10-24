package com.wjx.touhou_aifun.mixin.slashblade;

import com.wjx.touhou_aifun.compat.slashblade.SlashBladeCombat;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.List;

@Pseudo
@Mixin(targets = "mods.flammpfeil.slashblade.util.TargetSelector", remap = false)
public abstract class SlashBladeTargetMixin {
    @Inject(method = "getTargettableEntitiesWithinAABB(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/phys/AABB;D)Ljava/util/List;",
            at = @At("RETURN"), cancellable = true)
    private static void touhouAIFun$filterMelee(Level world, LivingEntity attacker, AABB area, double reach,
                                               CallbackInfoReturnable<List<Entity>> cir) {
        cir.setReturnValue(SlashBladeCombat.filterTargets(attacker, cir.getReturnValue()));
    }

    @Inject(method = "getTargettableEntitiesWithinAABB(Lnet/minecraft/world/level/Level;DLnet/minecraft/world/entity/Entity;)Ljava/util/List;",
            at = @At("RETURN"), cancellable = true)
    private static void touhouAIFun$filterArea(Level world, double reach, Entity projectile,
                                              CallbackInfoReturnable<List<Entity>> cir) {
        cir.setReturnValue(SlashBladeCombat.filterTargets(projectile, cir.getReturnValue()));
    }
}
