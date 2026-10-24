package com.wjx.touhou_aifun.compat.slashblade;

import com.github.tartaricacid.touhoulittlemaid.api.task.IAttackTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.wjx.touhou_aifun.TouhouAIFun;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import com.wjx.touhou_aifun.maid.combat.*;
import mods.flammpfeil.slashblade.SlashBlade;
import mods.flammpfeil.slashblade.SlashBladeConfig;
import mods.flammpfeil.slashblade.ability.Untouchable;
import mods.flammpfeil.slashblade.capability.concentrationrank.CapabilityConcentrationRank;
import mods.flammpfeil.slashblade.capability.inputstate.CapabilityInputState;
import mods.flammpfeil.slashblade.capability.inputstate.IInputState;
import mods.flammpfeil.slashblade.capability.mobeffect.CapabilityMobEffect;
import mods.flammpfeil.slashblade.capability.slashblade.ISlashBladeState;
import mods.flammpfeil.slashblade.entity.*;
import mods.flammpfeil.slashblade.item.ItemSlashBlade;
import mods.flammpfeil.slashblade.item.SwordType;
import mods.flammpfeil.slashblade.registry.ComboStateRegistry;
import mods.flammpfeil.slashblade.registry.combo.ComboState;
import mods.flammpfeil.slashblade.slasharts.SlashArts;
import mods.flammpfeil.slashblade.util.InputCommand;
import mods.flammpfeil.slashblade.util.TargetSelector;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.entity.PartEntity;

import javax.annotation.Nullable;
import java.util.*;

/** Server-thread-only native combo driver. No player input events or client-only types. */
public final class SlashBladeCombat {
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final Set<UUID> FAILED = new HashSet<>();
    private static final String TOKEN = "TouhouAIFunBladeTurn";
    private static final String OWNER = "TouhouAIFunBladeOwner";
    private static final int MAX_PROJECTILES = 40;

    private SlashBladeCombat() {}

    /** Re-read all capabilities on every tick AND immediately before a release. */
    public record Blade(ItemStack stack, ISlashBladeState state, int power, ResourceLocation art,
                        ResourceLocation root, boolean bewitched, boolean enchanted, boolean broken, boolean sealed) {
        public String artId() { return art == null ? "" : art.toString(); }
        public boolean sameWeapon(Blade other) {
            return other != null && stack == other.stack && state == other.state && power == other.power
                    && Objects.equals(art, other.art) && Objects.equals(root, other.root)
                    && bewitched == other.bewitched && enchanted == other.enchanted
                    && broken == other.broken && sealed == other.sealed;
        }
        public boolean canShoot(int cost) {
            return SlashBladeTactics.phantomAllowed(bewitched, broken, sealed, power,
                    state.getProudSoulCount(), cost);
        }
        public boolean canArt() {
            return enchanted && !broken && !sealed && SlashBladeTactics.knownArt(artId());
        }
    }

    @Nullable public static Blade inspect(EntityMaid maid) {
        ItemStack stack = maid.getMainHandItem();
        if (!(stack.getItem() instanceof ItemSlashBlade)) return null;
        ISlashBladeState state = stack.getCapability(ItemSlashBlade.BLADESTATE).orElse(null);
        if (state == null) return null;
        var types = SwordType.from(stack);
        ResourceLocation art = state.getSlashArtsKey();
        // A missing key is the native default SA; explicit NONE/unknown keys stay disabled.
        if (art == null) art = SlashArts.getRegistryKey(state.getSlashArts());
        return new Blade(stack, state, stack.getEnchantmentLevel(Enchantments.POWER_ARROWS),
                art, state.getComboRoot(), types.contains(SwordType.BEWITCHED),
                types.contains(SwordType.ENCHANTED), state.isBroken(), state.isSealed());
    }

    @Nullable private static LivingEntity target(EntityMaid maid) {
        LivingEntity target = MaidCombatReflex.currentTarget(maid);
        if (target == null) target = maid.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null);
        if (target == null || MaidCombatReflex.isSuppressed(maid, target)) return null;
        boolean explicit = MaidCombatReflex.isCommandTarget(maid, target);
        return MaidCombatPolicy.engagement(maid, explicit) == MaidCombatRules.Engagement.MELEE
                && MaidCombatPolicy.decision(maid, target, false, explicit).allowed() ? target : null;
    }

    public static boolean canControl(EntityMaid maid) {
        if (!maid.isAlive() || maid.isRemoved() || maid.isNoAi()) return false;
        if (!(maid.getMainHandItem().getItem() instanceof ItemSlashBlade)) return false;
        if (maid.level().isClientSide) return true;
        if (FAILED.contains(maid.getUUID())) return false;
        return maid.canBrainMoving() && maid.getCapability(CapabilityInputState.INPUT_STATE).isPresent()
                && inspect(maid) != null && target(maid) != null;
    }

    public static void tick(EntityMaid maid) {
        try {
            if (!MaidSlashBladeCombat.enabled() || !canControl(maid)) {
                stop(maid);
                return;
            }
            long now = maid.level().getGameTime();
            Blade blade = inspect(maid);
            LivingEntity target = target(maid);
            Session s = SESSIONS.get(maid.getUUID());
            // A Power/SA change on the SAME stack must invalidate charged and airborne attacks too.
            if (s != null && (!s.blade.sameWeapon(blade) || !s.target.equals(target.getUUID())
                    || s.task != maid.getTask() || s.level != maid.level())) {
                stop(maid);
                s = null;
            }
            if (s == null) {
                s = new Session(maid, blade, target);
                SESSIONS.put(maid.getUUID(), s);
                resetCombo(maid, blade);
            }
            if (s.lastTick == now) return;
            s.lastTick = now;
            s.projectiles.removeIf(Entity::isRemoved);
            face(maid, target);
            blade.state.setTargetEntityId(target);
            // Prevent base WORK melee from setting its own attack cadence.
            maid.getBrain().setMemoryWithExpiry(MemoryModuleType.ATTACK_COOLING_DOWN, true, 2);

            // Maids have no Player inventory tick. This is the single driver for the held blade.
            blade.stack.inventoryTick(maid.level(), maid, 0, true);
            IInputState input = maid.getCapability(CapabilityInputState.INPUT_STATE).orElseThrow(IllegalStateException::new);
            ResourceLocation current = blade.state.resolvCurrentComboState(maid);
            String combo = current.getPath();
            if (s.holding && !combo.equals("rapid_slash") && !combo.equals("upperslash") && !s.charging) {
                maid.stopUsingItem();
                s.holding = false;
                commands(maid, input, EnumSet.noneOf(InputCommand.class));
            }
            if (s.charging) {
                if (!defend(maid, target, s, input, now)) charge(maid, target, s, input, now);
            } else if (now < s.guardUntil) {
                maid.getNavigation().stop();
            } else {
                if (s.guardUntil != 0) {
                    commands(maid, input, EnumSet.noneOf(InputCommand.class));
                    s.guardUntil = 0;
                }
                if (!defend(maid, target, s, input, now)) act(maid, target, s, input, combo, now);
            }
            s.animationSync.publish(maid, blade.stack);
        } catch (RuntimeException | LinkageError error) {
            stop(maid);
            if (FAILED.add(maid.getUUID())) TouhouAIFun.LOGGER.error("[slashblade] Disabled maid integration after incompatible native action, maid={}", maid.getUUID(), error);
        }
    }

    private static void act(EntityMaid maid, LivingEntity target, Session s, IInputState input,
                            String combo, long now) {
        Blade blade = inspect(maid);
        if (!s.blade.sameWeapon(blade)) { stop(maid); return; }
        double distance = maid.distanceTo(target);
        double reach = Math.max(1.5, Math.min(6, TargetSelector.getResolvedReach(maid)));
        boolean visible = maid.hasLineOfSight(target);
        boolean lowHealth = maid.getHealth() < maid.getMaxHealth() * 0.3;
        if (now >= s.sceneAt) {
            s.enemies = (int) maid.level().getEntitiesOfClass(LivingEntity.class,
                    maid.getBoundingBox().inflate(6), e -> canDamage(maid, e)).stream().limit(5).count();
            s.sceneAt = now + 10;
        }
        boolean idle = combo.equals("none") || combo.equals("standby");
        boolean recovering = combo.contains("end") || combo.contains("sheath");
        // Movement is stopped for actual attacks; vanilla pathing must not drag a charged slash past its target.
        if (!visible || distance > reach + 0.5) {
            if (now >= s.pathAt) {
                BehaviorUtils.setWalkAndLookTargetMemories(maid, target, 0.75f, (int) Math.max(1, reach - 1));
                s.pathAt = now + 5;
            }
        } else {
            maid.getNavigation().stop();
            maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        }
        if (!visible) return;
        if (idle && now >= s.artAt && blade.canArt()
                && SlashBladeTactics.wantsArt(blade.artId(), distance, reach, s.enemies, lowHealth)
                && canPayArt(maid, target, blade) && safeArt(maid, blade)) {
            startCharge(maid, s, input, now);
            return;
        }
        if (now >= s.swordAt && distance <= 14 && s.projectiles.size() < MAX_PROJECTILES - 19
                && (!idle || distance > reach)) {
            fireSwords(maid, target, s, now);
        }
        if (idle && distance > 6 && distance <= 12 && now >= s.trickAt
                && maid.getLastHurtMob() == target && maid.tickCount - maid.getLastHurtMobTimestamp() < 100
                && teleportNear(maid, target)) {
            s.trickAt = now + 100;
            s.defenseAt = now + 24;
            resetCombo(maid, blade);
            return;
        }
        if (now < s.inputAt || s.holding) return;
        EnumSet<InputCommand> cmd = EnumSet.of(InputCommand.R_CLICK);
        boolean hold = false;
        if (idle) {
            s.cycle++;
            if (maid.onGround() && distance > reach && distance <= 6 && safeDash(maid, 3)) {
                cmd.addAll(EnumSet.of(InputCommand.SNEAK, InputCommand.FORWARD, InputCommand.R_DOWN));
                hold = s.enemies == 1 && !lowHealth;
            } else if (distance <= reach && maid.onGround() && s.enemies == 1 && !lowHealth
                    && target.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE) < 0.8
                    && s.cycle % 3 == 0 && headroom(maid)) {
                cmd.addAll(EnumSet.of(InputCommand.SNEAK, InputCommand.BACK, InputCommand.R_DOWN));
                hold = true;
            } else if (!maid.onGround() && distance <= reach && (s.airHits >= 2 || target.onGround())
                    && safeLanding(maid, maid.position())) {
                cmd.addAll(EnumSet.of(InputCommand.SNEAK, InputCommand.BACK));
            } else if (distance > reach + 0.5) return;
        } else {
            if (distance > reach + 1) return;
            // Wait into the native recovery node to choose C or B; do not jump registry nodes.
            if (combo.equals("combo_a2") && (lowHealth || s.enemies > 1 || s.cycle % 3 == 1)) return;
            if (combo.equals("combo_a3") && s.enemies == 1 && !lowHealth && s.cycle % 3 == 2) return;
            if (combo.startsWith("combo_b") && (lowHealth || s.enemies > 2)) return;
            if (!maid.onGround() && (s.airHits >= 2 || target.onGround()) && recovering) {
                cmd.addAll(EnumSet.of(InputCommand.SNEAK, InputCommand.BACK));
            }
        }
        if (maid.onGround()) s.airHits = 0;
        commands(maid, input, cmd);
        ResourceLocation old = blade.state.getComboSeq();
        ResourceLocation result = blade.state.progressCombo(maid);
        if (result != null && !result.equals(ComboStateRegistry.NONE.getId()) && !old.equals(blade.state.getComboSeq())) {
            s.inputAt = now + 2;
            if (!maid.onGround()) s.airHits++;
            if (hold) {
                s.holding = true;
                maid.startUsingItem(InteractionHand.MAIN_HAND);
                cmd.remove(InputCommand.R_CLICK);
                commands(maid, input, cmd);
            } else commands(maid, input, EnumSet.noneOf(InputCommand.class));
            ComboState started = ComboStateRegistry.REGISTRY.get().getValue(blade.state.getComboSeq());
            if (started != null) started.tickAction(maid); // Run frame zero of the newly entered node.
        } else commands(maid, input, EnumSet.noneOf(InputCommand.class));
    }

    private static boolean canPayArt(EntityMaid maid, LivingEntity target, Blade blade) {
        int cost = blade.state.getSlashArts().getProudSoulCost();
        if (blade.state.getProudSoulCount() >= cost) return true;
        boolean urgent = MaidCombatReflex.hasLayers(maid) || target.getMaxHealth() >= 40;
        return urgent && blade.stack.getMaxDamage() - blade.stack.getDamageValue() > Math.max(8, blade.stack.getMaxDamage() / 10);
    }

    private static boolean safeArt(EntityMaid maid, Blade blade) {
        return !blade.artId().equals("slashblade:piercing") || safeDash(maid, 3);
    }

    private static void startCharge(EntityMaid maid, Session s, IInputState input, long now) {
        commands(maid, input, EnumSet.of(InputCommand.R_DOWN));
        maid.startUsingItem(InteractionHand.MAIN_HAND);
        maid.getNavigation().stop();
        maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        s.charging = true;
        s.chargeAt = now;
        s.superCharge = TouhouAIFunConfig.SPEC.isLoaded() && TouhouAIFunConfig.SLASHBLADE_SUPER.get()
                && canSuper(maid, s.blade) && (s.enemies >= 3 || target(maid).getMaxHealth() >= 100);
    }

    private static void charge(EntityMaid maid, LivingEntity target, Session s, IInputState input, long now) {
        Blade current = inspect(maid);
        if (!s.blade.sameWeapon(current) || !current.canArt() || !maid.hasLineOfSight(target)
                || !canPayArt(maid, target, current) || !safeArt(maid, current) || !withinArtReach(maid, target, current)) {
            maid.stopUsingItem();
            s.charging = false;
            s.artAt = now + 20;
            commands(maid, input, EnumSet.noneOf(InputCommand.class));
            return;
        }
        maid.getNavigation().stop();
        if (now - s.chargeAt < (s.superCharge ? 20 : current.state.getFullChargeTicks(maid))) return;
        // Native releaseUsing performs enchant/broken/sealed checks, selects the SA and pays its cost.
        if (s.superCharge) {
            maid.stopUsingItem();
            if (canSuper(maid, current)) {
                ResourceLocation art = current.state.getSlashArts().doArts(SlashArts.ArtsType.Super, maid);
                ComboState next = ComboStateRegistry.REGISTRY.get().getValue(art);
                ComboState active = ComboStateRegistry.REGISTRY.get().getValue(current.state.resolvCurrentComboState(maid));
                if (next != null && active != null && active.getPriority() > next.getPriority()) {
                    current.stack.hurtAndBreak(current.stack.getMaxDamage() / 2, maid, ItemSlashBlade.getOnBroken(current.stack));
                    current.state.updateComboSeq(maid, art);
                }
            }
        } else maid.releaseUsingItem();
        s.charging = false;
        commands(maid, input, EnumSet.noneOf(InputCommand.class));
        s.artAt = now + 45;
        s.inputAt = now + 10;
    }

    private static boolean defend(EntityMaid maid, LivingEntity target, Session s, IInputState input, long now) {
        if (now < s.defenseAt || !maid.onGround()) return false;
        Projectile threat = maid.level().getEntitiesOfClass(Projectile.class,
                maid.getBoundingBox().inflate(7), p -> p.getOwner() != maid && p.getDeltaMovement().lengthSqr() > 0.04
                        && maid.hasLineOfSight(p) && incoming(maid, p)).stream().findFirst().orElse(null);
        boolean danger = threat != null || (target.swinging && maid.distanceTo(target) < 3.5);
        if (!danger) return false;
        if (!s.charging && s.blade.stack.getEnchantmentLevel(Enchantments.THORNS) > 0
                && !s.blade.broken && (threat == null || maid.getLookAngle().dot(threat.position().subtract(maid.position())) > 0)) {
            maid.stopUsingItem();
            s.holding = false;
            commands(maid, input, EnumSet.of(InputCommand.SNEAK));
            s.guardUntil = now + 6;
            s.defenseAt = now + 30;
            maid.getNavigation().stop();
            return true;
        }
        if (maid.getCapability(CapabilityMobEffect.MOB_EFFECT).map(e -> e.checkCanAvoid(now)).orElse(false)) {
            Vec3 forward = target.position().subtract(maid.position()).multiply(1, 0, 1).normalize();
            Vec3 side = new Vec3(-forward.z, 0, forward.x).scale(2.3);
            if (tryStep(maid, side) || tryStep(maid, side.scale(-1)) || tryStep(maid, forward.scale(-2)) || tryRise(maid)) {
                maid.getCapability(CapabilityMobEffect.MOB_EFFECT).ifPresent(e -> e.doAvoid(now));
                maid.stopUsingItem();
                s.holding = false;
                s.charging = false;
                commands(maid, input, EnumSet.noneOf(InputCommand.class));
                resetCombo(maid, s.blade);
                Untouchable.setUntouchable(maid, 10);
                s.defenseAt = now + 26;
                s.inputAt = now + 4;
                maid.level().playSound(null, maid.blockPosition(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.NEUTRAL, 0.35f, 1.2f);
                return true;
            }
        }
        return false;
    }

    private static boolean canSuper(EntityMaid maid, Blade blade) {
        return maid.onGround() && blade.bewitched && !blade.broken && !blade.sealed
                && blade.stack.getDamageValue() == 0 && blade.state.getKillCount() >= 1000;
    }

    private static boolean withinArtReach(EntityMaid maid, LivingEntity target, Blade blade) {
        double reach = switch (blade.artId()) {
            case "slashblade:sakura_end", "slashblade:void_slash", "slashblade:circle_slash" ->
                    Math.max(1.5, Math.min(6, TargetSelector.getResolvedReach(maid))) + 1;
            case "slashblade:piercing" -> 6;
            default -> 14;
        };
        return maid.distanceToSqr(target) <= reach * reach;
    }

    private static boolean tryRise(EntityMaid maid) {
        if (!headroom(maid) || !safeLanding(maid, maid.position().add(0, 0.8, 0))) return false;
        maid.move(net.minecraft.world.entity.MoverType.SELF, new Vec3(0, 0.8, 0));
        maid.setDeltaMovement(0, 0.35, 0);
        maid.setOnGround(false);
        maid.hasImpulse = true;
        return true;
    }

    private static boolean incoming(EntityMaid maid, Projectile p) {
        Vec3 start = p.position();
        Vec3 end = start.add(p.getDeltaMovement().scale(5));
        return maid.getBoundingBox().inflate(0.6).clip(start, end).isPresent();
    }

    private static boolean tryStep(EntityMaid maid, Vec3 offset) {
        Vec3 end = maid.position().add(offset);
        if (!safeLanding(maid, end) || !clearPath(maid, end)) return false;
        maid.getNavigation().stop();
        maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        maid.move(net.minecraft.world.entity.MoverType.SELF, offset);
        maid.setDeltaMovement(Vec3.ZERO);
        maid.hasImpulse = true;
        return true;
    }

    private static boolean teleportNear(EntityMaid maid, LivingEntity target) {
        Vec3 back = maid.position().subtract(target.position()).multiply(1, 0, 1).normalize().scale(2);
        Vec3 end = target.position().add(back);
        if (!safeLanding(maid, end) || !clearPath(maid, end)) return false;
        maid.getNavigation().stop();
        maid.teleportTo(end.x, end.y, end.z);
        Untouchable.setUntouchable(maid, 10);
        return true;
    }

    private static boolean headroom(EntityMaid maid) {
        return maid.level().noCollision(maid, maid.getBoundingBox().expandTowards(0, 3, 0));
    }

    private static boolean safeDash(EntityMaid maid, double length) {
        Vec3 offset = maid.getLookAngle().multiply(1, 0, 1).normalize().scale(length);
        for (double t = 0.25; t <= 1; t += 0.25) {
            if (!safeLanding(maid, maid.position().add(offset.scale(t)))) return false;
        }
        return clearPath(maid, maid.position().add(offset));
    }

    private static boolean clearPath(EntityMaid maid, Vec3 end) {
        Vec3 offset = new Vec3(0, maid.getBbHeight() * 0.5, 0);
        return maid.level().clip(new ClipContext(maid.position().add(offset), end.add(offset),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, maid)).getType() == HitResult.Type.MISS;
    }

    private static boolean safeLanding(EntityMaid maid, Vec3 end) {
        BlockPos pos = BlockPos.containing(end);
        if (!maid.level().hasChunkAt(pos) || !maid.level().getWorldBorder().isWithinBounds(pos)
                || maid.hasRestriction() && !maid.isWithinRestriction(pos)) return false;
        AABB box = maid.getBoundingBox().move(end.subtract(maid.position()));
        if (!maid.level().noCollision(maid, box) || maid.level().containsAnyLiquid(box)) return false;
        // Find support within three blocks; refuse fire, magma, cactus and void-adjacent landings.
        for (int d = 1; d <= 3; d++) {
            var state = maid.level().getBlockState(pos.below(d));
            if (state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS) || state.is(Blocks.FIRE)
                    || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SWEET_BERRY_BUSH)) return false;
            if (!state.getCollisionShape(maid.level(), pos.below(d)).isEmpty()) return true;
        }
        return false;
    }

    private static void face(EntityMaid maid, LivingEntity target) {
        Vec3 dir = target.getBoundingBox().getCenter().subtract(maid.getEyePosition());
        float yaw = (float) (Math.toDegrees(Math.atan2(dir.z, dir.x)) - 90);
        maid.setYRot(yaw);
        maid.setYHeadRot(yaw);
        maid.yBodyRot = yaw;
        maid.getLookControl().setLookAt(target, 90, 60);
    }

    private static void commands(EntityMaid maid, IInputState input, EnumSet<InputCommand> next) {
        long now = maid.level().getGameTime();
        for (InputCommand c : next) if (!input.getCommands().contains(c)) input.getLastPressTimes().put(c, now);
        input.getCommands().clear();
        input.getCommands().addAll(next);
    }

    private static void resetCombo(EntityMaid maid, Blade blade) {
        blade.state.setComboSeq(ComboStateRegistry.NONE.getId());
        blade.state.setLastActionTime(maid.level().getGameTime());
    }

    private static void fireSwords(EntityMaid maid, LivingEntity target, Session s, long now) {
        if (TouhouAIFunConfig.SPEC.isLoaded() && !TouhouAIFunConfig.SLASHBLADE_SWORDS.get()) return;
        Blade blade = inspect(maid);
        if (!s.blade.sameWeapon(blade)) return;
        int cost = SlashBladeConfig.SUMMON_SWORD_COST.get();
        boolean formation = s.enemies >= 2 || target.getMaxHealth() >= 40;
        if (formation) cost += SlashBladeConfig.SUMMON_SWORD_ART_COST.get(); // Native hold starts with a single shot.
        if (!blade.canShoot(cost)) return;
        blade.state.setProudSoulCount(blade.state.getProudSoulCount() - cost);
        EntityAbstractSummonedSword single = new EntityAbstractSummonedSword(SlashBlade.RegistryEvents.SummonedSword, maid.level());
        single.setPos(maid.getEyePosition().add(0.6 * Math.cos(Math.toRadians(maid.getYRot())), 0, 0.6 * Math.sin(Math.toRadians(maid.getYRot()))));
        Vec3 dir = target.getBoundingBox().getCenter().subtract(single.position()).normalize();
        single.shoot(dir.x, dir.y, dir.z, 3, 0);
        prepareSword(maid, blade, single);
        if (formation) {
            int rank = maid.getCapability(CapabilityConcentrationRank.RANK_POINT)
                    .map(r -> r.getRank(now).level).orElse(0);
            int count = rank >= 5 ? 8 : 6;
            int kind = s.enemies >= 3 && maid.distanceTo(target) > 4 && target.onGround() ? 3
                    : s.enemies >= 2 && maid.distanceTo(target) < 4 ? 0
                    : !target.onGround() ? 1 : 2;
            if (kind == 3) {
                Vec3 origin = target.position().add(0, 7, 0);
                // Refuse indoor rain rather than spawning swords through a roof.
                if (maid.level().clip(new ClipContext(target.getEyePosition(), origin,
                        ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, maid)).getType() == HitResult.Type.MISS) {
                    for (int i = 0; i < 19; i++) {
                        EntityHeavyRainSwords rain = new EntityHeavyRainSwords(SlashBlade.RegistryEvents.HeavyRainSwords, maid.level());
                        rain.setPos(origin);
                        if (i > 0) rain.setSpread(origin);
                        rain.setDelay(i / 2);
                        rain.setXRot(-90);
                        rain.startRiding(maid, true);
                        prepareSword(maid, blade, rain);
                    }
                }
            } else {
                for (int i = 0; i < count; i++) {
                    EntityAbstractSummonedSword sword;
                    if (kind == 0) sword = new EntitySpiralSwords(SlashBlade.RegistryEvents.SpiralSwords, maid.level());
                    else if (kind == 1) sword = new EntityStormSwords(SlashBlade.RegistryEvents.StormSwords, maid.level());
                    else sword = new EntityBlisteringSwords(SlashBlade.RegistryEvents.BlisteringSwords, maid.level());
                    sword.setPos(maid.position());
                    sword.setOwner(maid);
                    sword.setDelay(kind == 2 ? i : 360 / count * i);
                    sword.startRiding(kind == 1 ? target : maid, true);
                    prepareSword(maid, blade, sword);
                    // Native rideTick reads the living shooter's input and releases with per-sword delay.
                }
            }
        }
        s.swordAt = now + (formation ? 80 : 18);
    }

    private static void prepareSword(EntityMaid maid, Blade blade, EntityAbstractSummonedSword sword) {
        sword.setOwner(maid);
        sword.setColor(blade.state.getColorCode());
        sword.setDamage(blade.power);
        maid.level().addFreshEntity(sword);
    }

    @Nullable public static EntityMaid owner(Entity source) {
        if (source instanceof EntityMaid maid) return maid;
        if (source.getPersistentData().hasUUID(OWNER) && source.level() instanceof ServerLevel level) {
            Entity entity = level.getEntity(source.getPersistentData().getUUID(OWNER));
            return entity instanceof EntityMaid maid ? maid : null;
        }
        Entity shooter = source instanceof IShootable shootable ? shootable.getShooter()
                : source instanceof Projectile p ? p.getOwner() : null;
        return shooter instanceof EntityMaid maid ? maid : null;
    }

    public static boolean canDamage(EntityMaid maid, Entity victim) {
        if (victim instanceof PartEntity<?> part) victim = part.getParent();
        if (!(victim instanceof LivingEntity living)) {
            return victim instanceof Projectile p && p.getOwner() != maid
                    && (p.getOwner() == null || p.getOwner() instanceof LivingEntity l && canDamage(maid, l));
        }
        if (MaidCombatReflex.isSuppressed(maid, living)
                || !MaidCombatPolicy.decision(maid, living, false, true).allowed()) return false;
        if (living instanceof Player && !SlashBladeConfig.PVP_ENABLE.get()) return false;
        LivingEntity focus = target(maid);
        return living == focus || living instanceof Enemy
                || living instanceof Mob mob && (mob.getTarget() == maid
                || maid.getOwner() != null && mob.getTarget() == maid.getOwner());
    }

    public static boolean allowHit(Entity source, Entity victim) {
        if (source.level().isClientSide) return true;
        EntityMaid maid = owner(source);
        if (maid == null) return !source.getPersistentData().hasUUID(TOKEN);
        if (!managed(source, maid)) return true;
        if (!canDamage(maid, victim)) return false;
        if (source instanceof EntityMaid) return !SESSIONS.containsKey(maid.getUUID()) || canControl(maid);
        Session s = SESSIONS.get(maid.getUUID());
        if (!source.getPersistentData().hasUUID(TOKEN)) return !canControl(maid);
        return s != null && s.token.equals(source.getPersistentData().getUUID(TOKEN))
                && s.blade.sameWeapon(inspect(maid)) && canControl(maid);
    }

    public static List<Entity> filterTargets(Entity source, List<Entity> found) {
        if (source.level().isClientSide) return found;
        EntityMaid maid = owner(source);
        return maid == null || !managed(source, maid) ? found
                : found.stream().filter(e -> allowHit(source, e)).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private static boolean managed(Entity source, EntityMaid maid) {
        return SESSIONS.containsKey(maid.getUUID()) || source.getPersistentData().hasUUID(TOKEN);
    }

    public static boolean isBladeSource(Entity source) {
        return source.getPersistentData().hasUUID(TOKEN) || source instanceof IShootable || source instanceof EntityMaid maid
                && maid.getMainHandItem().getItem() instanceof ItemSlashBlade;
    }

    public static boolean mayReflect(Entity projectile, Entity attacker) {
        if (!(attacker instanceof EntityMaid maid) || !SESSIONS.containsKey(maid.getUUID())) return true;
        if (!(projectile instanceof Projectile p)) return false;
        return p.getOwner() != maid && (!(p.getOwner() instanceof LivingEntity shooter)
                || canDamage(maid, shooter));
    }

    public static void reflected(Entity projectile, Entity attacker) {
        if (projectile == null || !(attacker instanceof EntityMaid maid) || projectile.level().isClientSide) return;
        Session s = SESSIONS.get(maid.getUUID());
        if (s == null) return;
        tag(projectile, maid, s);
    }

    /** Every native child effect gets the same generation, including delayed SA effects. */
    public static void track(Entity entity) {
        if (!(entity instanceof IShootable) && !entity.getPersistentData().hasUUID(TOKEN)) return;
        EntityMaid maid = owner(entity);
        Session s = maid == null ? null : SESSIONS.get(maid.getUUID());
        // Saved attacks cannot resurrect after a server restart, dimension change or expired turn.
        if (entity.getPersistentData().hasUUID(TOKEN) && (s == null
                || !s.token.equals(entity.getPersistentData().getUUID(TOKEN)))) {
            entity.discard();
            return;
        }
        if (s == null) return;
        if (!s.blade.sameWeapon(inspect(maid)) || s.projectiles.size() >= MAX_PROJECTILES) {
            entity.discard();
            return;
        }
        tag(entity, maid, s);
    }

    private static void tag(Entity entity, EntityMaid maid, Session s) {
        if (s.projectiles.size() >= MAX_PROJECTILES) { entity.discard(); return; }
        entity.getPersistentData().putUUID(TOKEN, s.token);
        entity.getPersistentData().putUUID(OWNER, maid.getUUID());
        s.projectiles.add(entity);
    }

    /** Guard against another integration starting to tick maid equipment in a newer version. */
    public static boolean allowInventoryTick(Entity entity, ItemStack stack, boolean selected) {
        if (!(entity instanceof EntityMaid maid)) return true;
        Session s = SESSIONS.get(maid.getUUID());
        if (s == null || !selected || stack != maid.getMainHandItem()) return s == null;
        long now = maid.level().getGameTime();
        if (s.inventoryTick == now) return false;
        s.inventoryTick = now;
        return true;
    }

    public static void stop(EntityMaid maid) {
        Session s = SESSIONS.remove(maid.getUUID());
        if (s == null) return;
        for (Entity entity : List.copyOf(s.projectiles)) entity.discard();
        maid.stopUsingItem(); // Never release a cancelled charge: that would cast the old SA.
        maid.getCapability(CapabilityInputState.INPUT_STATE).ifPresent(i -> {
            i.getCommands().clear();
            i.getLastPressTimes().clear();
        });
        s.blade.state.setTargetEntityId(-1);
        resetCombo(maid, s.blade);
        s.animationSync.publish(maid, s.blade.stack);
        maid.getNavigation().stop();
        maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        maid.getBrain().eraseMemory(MemoryModuleType.ATTACK_COOLING_DOWN);
    }

    public static void unload(EntityMaid maid) { stop(maid); FAILED.remove(maid.getUUID()); }
    public static void clear() { SESSIONS.clear(); FAILED.clear(); }
    public static int activeProjectiles(EntityMaid maid) {
        Session s = SESSIONS.get(maid.getUUID()); return s == null ? 0 : s.projectiles.size();
    }
    public static String status(EntityMaid maid) {
        Session s = SESSIONS.get(maid.getUUID());
        Blade b = inspect(maid);
        return "active=" + (s != null) + " power=" + (b == null ? -1 : b.power) + " art=" + (b == null ? "none" : b.artId())
                + " combo=" + (b == null ? "none" : b.state.getComboSeq())
                + " effects=" + (s == null ? 0 : s.projectiles.size())
                + " charging=" + (s != null && s.charging)
                + " distance=" + (target(maid) == null ? -1 : maid.distanceTo(target(maid)))
                + " canControl=" + canControl(maid) + " targetVisible=" + (target(maid) != null && maid.hasLineOfSight(target(maid)));
    }

    private static final class Session {
        final SlashBladeAnimationSync animationSync = new SlashBladeAnimationSync();
        final Blade blade;
        final UUID target;
        final UUID token = UUID.randomUUID();
        final Object task;
        final Object level;
        final Set<Entity> projectiles = new HashSet<>();
        long lastTick = -1, inventoryTick = -1, inputAt, artAt, swordAt, defenseAt, trickAt, sceneAt, pathAt, chargeAt, guardUntil;
        int enemies = 1, cycle, airHits;
        boolean charging, holding, superCharge;
        Session(EntityMaid maid, Blade blade, LivingEntity target) {
            this.blade = blade; this.target = target.getUUID(); this.task = maid.getTask(); this.level = maid.level();
            this.artAt = maid.level().getGameTime() + 12;
        }
    }
}
