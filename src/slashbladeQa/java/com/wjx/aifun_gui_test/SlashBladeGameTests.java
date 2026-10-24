package com.wjx.aifun_gui_test;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskAttack;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import com.wjx.touhou_aifun.compat.slashblade.SlashBladeCombat;
import com.wjx.touhou_aifun.maid.combat.MaidCombatReflex;
import com.wjx.touhou_aifun.maid.combat.MaidSlashBladeCombat;
import mods.flammpfeil.slashblade.capability.slashblade.ISlashBladeState;
import mods.flammpfeil.slashblade.entity.EntityAbstractSummonedSword;
import mods.flammpfeil.slashblade.item.ItemSlashBlade;
import mods.flammpfeil.slashblade.registry.ComboStateRegistry;
import mods.flammpfeil.slashblade.registry.SlashArtsRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.UUID;

@GameTestHolder("aifun_slashblade_test")
@PrefixGameTestTemplate(false)
public final class SlashBladeGameTests {
    @GameTest(batch="bladeGates", template="empty", timeoutTicks=100)
    public static void currentBladePowerAndSaAreReadAgain(GameTestHelper h) {
        Fixture f = fixture(h, 3);
        ItemStack powered = blade(true, "judgement_cut");
        f.maid.setItemInHand(InteractionHand.MAIN_HAND, powered);
        SlashBladeCombat.Blade first = SlashBladeCombat.inspect(f.maid);
        h.assertTrue(first.power() == 3 && first.canShoot(2), "Power III blade must allow phantom swords");
        ItemStack plain = blade(false, "circle_slash");
        f.maid.setItemInHand(InteractionHand.MAIN_HAND, plain);
        SlashBladeCombat.Blade second = SlashBladeCombat.inspect(f.maid);
        h.assertTrue(!first.sameWeapon(second) && !second.canShoot(2), "swap must revoke Power and stale blade identity");
        h.assertTrue(second.artId().equals("slashblade:circle_slash"), "SA must come from the new blade");
        state(plain).setSlashArtsKey(SlashArtsRegistry.PIERCING.getId());
        h.assertTrue(!second.sameWeapon(SlashBladeCombat.inspect(f.maid)), "in-place SA edits also invalidate pending actions");
        plain.enchant(Enchantments.POWER_ARROWS, 1);
        h.assertTrue(SlashBladeCombat.inspect(f.maid).canShoot(2), "in-place Power edit must be visible");
        state(plain).setSlashArtsKey(null);
        h.assertTrue(SlashBladeCombat.inspect(f.maid).artId().equals("slashblade:judgement_cut"), "missing SA key must resolve the native default");
        state(plain).setSlashArtsKey(SlashArtsRegistry.NONE.getId());
        h.assertTrue(!SlashBladeCombat.inspect(f.maid).canArt(), "explicit NONE must not fall back to automatic Judgement Cut");
        cleanup(f); h.succeed();
    }

    @GameTest(batch="bladeCombat", template="empty", timeoutTicks=180)
    public static void nativeCombosHurtAndStopImmediately(GameTestHelper h) {
        Fixture f = fixture(h, 3);
        f.maid.setItemInHand(InteractionHand.MAIN_HAND, blade(false, "sakura_end"));
        h.assertTrue(MaidCombatReflex.command(f.maid, f.zombie).success(), "command must be accepted");
        float health = f.zombie.getHealth();
        h.runAtTickTime(65, () -> {
            h.assertTrue(f.zombie.getHealth() < health, "native combo timeline must actually damage the target");
            h.assertTrue(MaidSlashBladeCombat.controls(f.maid), "native driver must retain ownership");
            MaidCombatReflex.clearAggro(f.maid, f.zombie);
            h.assertTrue(SlashBladeCombat.activeProjectiles(f.maid) == 0, "clear must discard every tracked effect");
            float stoppedHealth = f.zombie.getHealth();
            h.runAtTickTime(90, () -> {
                h.assertTrue(f.zombie.getHealth() == stoppedHealth, "cleared target must take no delayed damage");
                cleanup(f); h.succeed();
            });
        });
    }

    @GameTest(batch="bladeSwap", template="empty", timeoutTicks=180)
    public static void swapCancelsFlyingSwordsAndOldCharge(GameTestHelper h) {
        Fixture f = fixture(h, 8);
        f.maid.setItemInHand(InteractionHand.MAIN_HAND, blade(true, "judgement_cut"));
        MaidCombatReflex.command(f.maid, f.zombie);
        h.runAtTickTime(6, () -> {
            h.assertTrue(SlashBladeCombat.activeProjectiles(f.maid) > 0, "Power blade should emit real native projectiles: " + SlashBladeCombat.status(f.maid));
            var oldEffects = h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.projectile.Projectile.class,
                    f.maid.getBoundingBox().inflate(32), e -> SlashBladeCombat.owner(e) == f.maid);
            ItemStack plain = blade(false, "circle_slash");
            f.maid.setItemInHand(InteractionHand.MAIN_HAND, plain);
            SlashBladeCombat.tick(f.maid);
            h.assertTrue(!oldEffects.isEmpty() && oldEffects.stream().allMatch(net.minecraft.world.entity.Entity::isRemoved),
                    "swap must remove old projectiles before their next hit");
            h.assertTrue(!f.maid.isUsingItem(), "old charge must not survive the swap");
            h.assertTrue(SlashBladeCombat.inspect(f.maid).artId().equals("slashblade:circle_slash"), "replacement SA must be selected");
            h.runAtTickTime(30, () -> {
                h.assertTrue(h.getLevel().getEntitiesOfClass(EntityAbstractSummonedSword.class,
                        f.maid.getBoundingBox().inflate(32), e -> e.getShooter() == f.maid).isEmpty(),
                        "non-Power blade must not summon phantom swords");
                cleanup(f); h.succeed();
            });
        });
    }

    @GameTest(batch="bladeArts", template="empty", timeoutTicks=100)
    public static void allEightSaUseTheirOwnNativeReleaseAndCost(GameTestHelper h) {
        Fixture f = fixture(h, 3);
        f.maid.setNoAi(true);
        f.maid.setOnGround(true);
        for (String art : new String[]{"judgement_cut", "sakura_end", "void_slash", "circle_slash",
                "drive_horizontal", "drive_vertical", "wave_edge", "piercing"}) {
            ItemStack stack = blade(false, art);
            f.maid.setItemInHand(InteractionHand.MAIN_HAND, stack);
            ISlashBladeState s = state(stack);
            s.setTargetEntityId(f.zombie);
            s.setComboSeq(ComboStateRegistry.NONE.getId());
            s.setLastActionTime(h.getLevel().getGameTime());
            int before = s.getProudSoulCount();
            int cost = s.getSlashArts().getProudSoulCost();
            ResourceLocation expected = s.getSlashArts().getComboStateJust(f.maid);
            stack.getItem().releaseUsing(stack, h.getLevel(), f.maid,
                    stack.getUseDuration() - s.getFullChargeTicks(f.maid));
            h.assertTrue(s.getComboSeq().equals(expected), art + " must release the equipped SA's perfect variant");
            h.assertTrue(s.getProudSoulCount() == before - cost, art + " must pay its native cost exactly once");
            h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.projectile.Projectile.class,
                    f.maid.getBoundingBox().inflate(32), e -> SlashBladeCombat.owner(e) == f.maid)
                    .forEach(net.minecraft.world.entity.Entity::discard);
        }
        cleanup(f); h.succeed();
    }

    @GameTest(batch="bladeMutation", template="empty", timeoutTicks=100)
    public static void sameStackPowerRemovalAndSaChangeCancelOldEffects(GameTestHelper h) {
        Fixture f = fixture(h, 8);
        ItemStack stack = blade(true, "judgement_cut");
        f.maid.setItemInHand(InteractionHand.MAIN_HAND, stack);
        MaidCombatReflex.command(f.maid, f.zombie);
        h.runAtTickTime(6, () -> {
            var old = h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.projectile.Projectile.class,
                    f.maid.getBoundingBox().inflate(32), e -> SlashBladeCombat.owner(e) == f.maid);
            h.assertTrue(!old.isEmpty(), "setup must create native effects");
            net.minecraft.world.item.enchantment.EnchantmentHelper.setEnchantments(
                    java.util.Map.of(Enchantments.UNBREAKING, 1), stack);
            state(stack).setSlashArtsKey(SlashArtsRegistry.CIRCLE_SLASH.getId());
            SlashBladeCombat.tick(f.maid);
            h.assertTrue(f.maid.getMainHandItem() == stack, "test must mutate the same stack instance");
            h.assertTrue(old.stream().allMatch(net.minecraft.world.entity.Entity::isRemoved), "in-place mutation must retire old generation");
            h.assertTrue(!SlashBladeCombat.inspect(f.maid).canShoot(2), "removed Power must revoke phantom skills");
            h.assertTrue(SlashBladeCombat.inspect(f.maid).artId().equals("slashblade:circle_slash"), "in-place new SA must be read");
            cleanup(f); h.succeed();
        });
    }

    @GameTest(batch="bladeChargeSwap", template="empty", timeoutTicks=100)
    public static void swapDuringChargeDoesNotReleaseOrPayOldSa(GameTestHelper h) {
        Fixture f = fixture(h, 8);
        f.maid.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0);
        ItemStack oldBlade = blade(true, "judgement_cut");
        f.maid.setItemInHand(InteractionHand.MAIN_HAND, oldBlade);
        MaidCombatReflex.command(f.maid, f.zombie);
        // Keep the setup at ranged distance: native rapid-slash movement is independent of walk speed.
        var origin = f.maid.position();
        for (int tick = 1; tick < 16; tick++) h.runAtTickTime(tick,
                () -> { f.maid.teleportTo(origin.x, origin.y, origin.z); f.maid.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO); });
        h.runAtTickTime(16, () -> {
            h.assertTrue(f.maid.isUsingItem(), "setup must reach a real pending SA charge: " + SlashBladeCombat.status(f.maid));
            int souls = state(oldBlade).getProudSoulCount();
            f.maid.setItemInHand(InteractionHand.MAIN_HAND, blade(false, "circle_slash"));
            SlashBladeCombat.tick(f.maid);
            h.assertTrue(!f.maid.isUsingItem(), "swap must stop old charge without releasing it");
            h.runAtTickTime(30, () -> {
                h.assertTrue(state(oldBlade).getProudSoulCount() == souls, "cancelled old SA must never pay or release later");
                h.assertTrue(SlashBladeCombat.inspect(f.maid).artId().equals("slashblade:circle_slash"), "pending old SA must not override new blade");
                cleanup(f); h.succeed();
            });
        });
    }

    @GameTest(batch="bladeProtection", template="empty", timeoutTicks=120)
    public static void areaAndForcedSwordHitsCannotHurtOwner(GameTestHelper h) {
        Fixture f = fixture(h, 3);
        f.maid.setItemInHand(InteractionHand.MAIN_HAND, blade(true, "circle_slash"));
        MaidCombatReflex.command(f.maid, f.zombie);
        SlashBladeCombat.tick(f.maid);
        h.assertTrue(!SlashBladeCombat.canDamage(f.maid, f.owner), "owner must fail target filtering");
        EntityAbstractSummonedSword sword = new EntityAbstractSummonedSword(
                mods.flammpfeil.slashblade.SlashBlade.RegistryEvents.SummonedSword, h.getLevel());
        sword.setOwner(f.maid); sword.setDamage(50); sword.setPos(f.owner.position());
        h.getLevel().addFreshEntity(sword);
        float health = f.owner.getHealth();
        sword.doForceHitEntity(f.owner);
        h.assertTrue(f.owner.getHealth() == health && !f.owner.isOnFire(), "forced sword hit must have no owner damage or fire");
        h.assertTrue(!f.owner.hasEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN), "owner must receive no sword debuff");
        var arrow = new net.minecraft.world.entity.projectile.Arrow(EntityType.ARROW, h.getLevel());
        arrow.setPos(f.maid.position()); arrow.setOwner(f.zombie); h.getLevel().addFreshEntity(arrow);
        mods.flammpfeil.slashblade.ability.ArrowReflector.doReflect(arrow, f.maid);
        h.assertTrue(!SlashBladeCombat.allowHit(arrow, f.owner), "reflected vanilla arrows must also protect the owner");
        f.owner.hurt(f.owner.damageSources().arrow(arrow, f.zombie), 10);
        h.assertTrue(f.owner.getHealth() == health, "reflected projectile damage fallback must protect owner");
        cleanup(f); h.succeed();
    }

    private static ItemStack blade(boolean power, String art) {
        ItemStack stack = new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation("slashblade", "slashblade")));
        stack.enchant(Enchantments.UNBREAKING, 1);
        if (power) stack.enchant(Enchantments.POWER_ARROWS, 3);
        ISlashBladeState s = state(stack);
        s.setDefaultBewitched(true); s.setSealed(false); s.setBroken(false);
        s.setProudSoulCount(2000); s.setComboRoot(ComboStateRegistry.STANDBY.getId());
        s.setSlashArtsKey(new ResourceLocation("slashblade", art));
        return stack;
    }
    private static ISlashBladeState state(ItemStack stack) {
        return stack.getCapability(ItemSlashBlade.BLADESTATE).orElseThrow(IllegalStateException::new);
    }
    private record Fixture(EntityMaid maid, Zombie zombie, Player owner) {}
    private static Fixture fixture(GameTestHelper h, int distance) {
        for (int x=0;x<16;x++) for (int z=0;z<12;z++) {
            h.setBlock(new BlockPos(x,0,z), Blocks.STONE);
            for (int y=1;y<8;y++) h.setBlock(new BlockPos(x,y,z), Blocks.AIR);
        }
        Player owner = new Player(h.getLevel(), BlockPos.ZERO, 0,
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "blade-owner")) {
            @Override public boolean isCreative() { return false; }
            @Override public boolean isSpectator() { return false; }
        };
        BlockPos origin = h.absolutePos(new BlockPos(2,1,3));
        owner.setPos(origin.getX()+0.5, origin.getY(), origin.getZ()+3.5);
        EntityMaid maid = InitEntities.MAID.get().create(h.getLevel());
        maid.setPos(origin.getX()+0.5, origin.getY(), origin.getZ()+0.5);
        maid.setOwnerUUID(owner.getUUID()); h.getLevel().addFreshEntity(maid);
        maid.setTask(TaskManager.findTask(TaskAttack.UID).orElse(TaskManager.getIdleTask()));
        Zombie zombie = h.spawn(EntityType.ZOMBIE, new BlockPos(2+distance,1,3));
        zombie.setNoAi(true);
        zombie.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000); zombie.setHealth(1000);
        return new Fixture(maid,zombie,owner);
    }
    private static void cleanup(Fixture f) {
        MaidCombatReflex.clear(f.maid); f.maid.discard(); f.zombie.discard(); f.owner.discard();
    }
}
