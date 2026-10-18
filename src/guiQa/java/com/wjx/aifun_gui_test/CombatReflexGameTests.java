package com.wjx.aifun_gui_test;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskAttack;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import com.wjx.touhou_aifun.maid.PublicMaidAccess;
import com.wjx.touhou_aifun.maid.combat.MaidCombatData;
import com.wjx.touhou_aifun.maid.combat.MaidCombatPolicy;
import com.wjx.touhou_aifun.maid.combat.MaidCombatReflex;
import com.wjx.touhou_aifun.maid.combat.MaidCombatRules;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Focused integration tests for the server-side combat reflex and the
 * command/clear suppression path. These deliberately do not use setNoAi(true):
 * the reflex lives in the brain and must tick.
 * <p>
 * Each test uses its own batch so the tests do not run concurrently: the combat
 * state maps are static, and concurrent tests must not clear each other's
 * per-maid state. No QaConfigScope is used, so no test depends on mutating the
 * global config while another test is running.
 */
@GameTestHolder("aifun_combat_test")
@PrefixGameTestTemplate(false)
public final class CombatReflexGameTests {
    private static final BlockPos MAID_POS = new BlockPos(2, 1, 2);
    private static final BlockPos OWNER_POS = new BlockPos(4, 1, 2);
    private static final BlockPos TARGET_POS = new BlockPos(3, 1, 2);

    @GameTest(batch = "aifunCombatMelee", template = "empty", templateNamespace = "aifun_combat_test",
            timeoutTicks = 600)
    public static void maidHurtLocksAndMeleeHitsImmediately(GameTestHelper helper) throws Exception {
        floor(helper);
        Player owner = mockPlayer(helper, OWNER_POS);
        Zombie zombie = spawnZombie(helper, TARGET_POS);
        EntityMaid maid = spawnMaid(helper, owner, MAID_POS, true);
        try {
            maid.hurt(maid.damageSources().mobAttack(zombie), 1.0f);
            assertTarget(helper, maid, zombie);
            helper.runAfterDelay(2, () -> {
                try {
                    String diag = "target=" + maid.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET)
                            + " status=" + MaidCombatReflex.status(maid).toJson()
                            + " dist=" + maid.distanceTo(zombie)
                            + " los=" + maid.hasLineOfSight(zombie)
                            + " range=" + maid.isWithinMeleeAttackRange(zombie)
                            + " noAi=" + maid.isNoAi()
                            + " task=" + maid.getTask().getUid()
                            + " health=" + zombie.getHealth()
                            + " decision=" + MaidCombatPolicy.decision(maid, zombie, false, false)
                            + " engagement=" + MaidCombatPolicy.engagement(maid, false)
                            + " canMove=" + maid.canBrainMoving();
                    helper.assertTrue(zombie.getHealth() < zombie.getMaxHealth(),
                            "reflex melee should damage the attacker within 2 ticks: " + diag);
                    helper.succeed();
                } catch (Throwable error) {
                    helper.fail("melee reflex failed: " + error);
                } finally {
                    cleanup(maid, owner, zombie);
                }
            });
        } catch (Throwable error) {
            cleanup(maid, owner, zombie);
            helper.fail("maid hurt scenario failed: " + error);
        }
    }

    @GameTest(batch = "aifunCombatOwner", template = "empty", templateNamespace = "aifun_combat_test",
            timeoutTicks = 600)
    public static void ownerProtectionAndPlayerHardGates(GameTestHelper helper) throws Exception {
        floor(helper);
        Player owner = mockPlayer(helper, OWNER_POS);
        Zombie zombie = spawnZombie(helper, TARGET_POS);
        Player attacker = mockPlayer(helper, OWNER_POS.offset(1, 0, 0));
        EntityMaid maid = spawnMaid(helper, owner, MAID_POS, true);
        try {
            // Owner hurt by a mob: the maid protects the owner immediately.
            postOwnerHurt(owner, owner.damageSources().mobAttack(zombie));
            assertTarget(helper, maid, zombie);

            // Safe, non-public, friendly-fire-on maid may retaliate against a player.
            MaidCombatRules.PlayerBlock block = MaidCombatPolicy.playerBlock(maid, attacker);
            helper.assertTrue(block == MaidCombatRules.PlayerBlock.ALLOWED,
                    "mock player gate=" + block
                            + " public=" + PublicMaidAccess.isPublic(maid)
                            + " friendlyFire=" + PublicMaidAccess.isFriendlyFireAllowed(maid)
                            + " creative=" + attacker.isCreative()
                            + " spectator=" + attacker.isSpectator()
                            + " fake=" + (attacker instanceof FakePlayer));
            MaidCombatReflex.clear(maid);
            postOwnerHurt(owner, owner.damageSources().playerAttack(attacker));
            assertTarget(helper, maid, attacker);

            // Public maid: every player target is refused.
            PublicMaidAccess.data(maid).touhouAIFun$setPublicMaid(true);
            MaidCombatReflex.clear(maid);
            postOwnerHurt(owner, owner.damageSources().playerAttack(attacker));
            assertNoTarget(helper, maid);

            // Friendly fire disabled: every player target is refused.
            PublicMaidAccess.data(maid).touhouAIFun$setPublicMaid(false);
            PublicMaidAccess.data(maid).touhouAIFun$setFriendlyFireAllowed(false);
            MaidCombatReflex.clear(maid);
            postOwnerHurt(owner, owner.damageSources().playerAttack(attacker));
            assertNoTarget(helper, maid);

            // attack_players=false is an additional per-maid restriction.
            PublicMaidAccess.data(maid).touhouAIFun$setFriendlyFireAllowed(true);
            MaidCombatData.store(maid, null, null, false, null, null, false);
            MaidCombatReflex.clear(maid);
            postOwnerHurt(owner, owner.damageSources().playerAttack(attacker));
            assertNoTarget(helper, maid);
            MaidCombatData.reset(maid);

            // The actual owner is never a valid target, even when the maid is hurt by them.
            MaidCombatReflex.clear(maid);
            maid.hurt(maid.damageSources().playerAttack(owner), 1.0f);
            assertNoTarget(helper, maid);

            // A normal player can still be commanded as an explicit target.
            MaidCombatReflex.clear(maid);
            helper.assertTrue(MaidCombatReflex.command(maid, attacker).success(),
                    "explicit player target should be accepted when all gates pass");
            assertTarget(helper, maid, attacker);

            helper.succeed();
        } catch (Throwable error) {
            helper.fail("owner/player gate scenario failed: " + error);
        } finally {
            cleanup(maid, owner, zombie, attacker);
        }
    }

    @GameTest(batch = "aifunCombatClear", template = "empty", templateNamespace = "aifun_combat_test",
            timeoutTicks = 900)
    public static void commandAndClearSuppressesTarget(GameTestHelper helper) throws Exception {
        floor(helper);
        Player owner = mockPlayer(helper, OWNER_POS);
        Zombie zombie = spawnZombie(helper, TARGET_POS);
        EntityMaid maid = spawnMaid(helper, owner, MAID_POS, false);
        try {
            // Idle (non-attack) task: an explicit command still engages.
            helper.assertTrue(MaidCombatReflex.command(maid, zombie).success(),
                    "explicit command should engage a non-attack task");
            assertTarget(helper, maid, zombie);
            helper.assertTrue(maid.canAttack(zombie), "no suppression before clear");

            MaidCombatReflex.clearAggro(maid, null);
            helper.assertTrue(!maid.canAttack(zombie),
                    "cleared target must be suppressed from base target acquisition");
            float before = zombie.getHealth();
            maid.doHurtTarget(zombie);
            helper.assertTrue(zombie.getHealth() == before,
                    "suppressed target must not receive melee damage");

            // Default aggroClearTicks is 200; wait past it rather than mutating the
            // global config while other GameTest batches could be running.
            helper.runAfterDelay(205, () -> {
                try {
                    helper.assertTrue(maid.canAttack(zombie),
                            "suppression must expire after aggroClearTicks");
                    helper.succeed();
                } catch (Throwable error) {
                    helper.fail("suppression expiry failed: " + error);
                } finally {
                    cleanup(maid, owner, zombie);
                }
            });
        } catch (Throwable error) {
            cleanup(maid, owner, zombie);
            helper.fail("command/clear scenario failed: " + error);
        }
    }

    @GameTest(batch = "aifunCombatDefend", template = "empty", templateNamespace = "aifun_combat_test",
            timeoutTicks = 600)
    public static void nonAttackTaskNeedsDefendMode(GameTestHelper helper) throws Exception {
        floor(helper);
        Player owner = mockPlayer(helper, OWNER_POS);
        Zombie zombie = spawnZombie(helper, TARGET_POS);
        EntityMaid maid = spawnMaid(helper, owner, MAID_POS, false);
        try {
            // Default: an idle/follow maid must not gain an attack target.
            maid.hurt(maid.damageSources().mobAttack(zombie), 1.0f);
            assertNoTarget(helper, maid);

            // Per-maid defend mode opts in to self-defence without changing the task.
            MaidCombatData.store(maid, MaidCombatData.Mode.DEFEND, null, null, null, null, false);
            MaidCombatReflex.clear(maid);
            maid.invulnerableTime = 0;
            maid.hurt(maid.damageSources().mobAttack(zombie), 1.0f);
            assertTarget(helper, maid, zombie);
            helper.assertTrue(maid.getTask() == TaskManager.getIdleTask(),
                    "defend mode must not change the current work task");
            helper.succeed();
        } catch (Throwable error) {
            helper.fail("non-attack defend scenario failed: " + error);
        } finally {
            cleanup(maid, owner, zombie);
        }
    }

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x <= 7; x++) {
            for (int z = 0; z <= 5; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
    }

    private static EntityMaid spawnMaid(GameTestHelper helper, Player owner, BlockPos pos,
                                        boolean attackTask) {
        EntityMaid maid = new EntityMaid(InitEntities.MAID.get(), helper.getLevel()) {
            @Override
            public LivingEntity getOwner() {
                return owner;
            }
        };
        BlockPos absolute = helper.absolutePos(pos);
        maid.setPos(absolute.getX() + 0.5, absolute.getY(), absolute.getZ() + 0.5);
        maid.setOwnerUUID(owner.getUUID());
        maid.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_SWORD));
        helper.getLevel().addFreshEntity(maid);
        maid.setTask(attackTask
                ? TaskManager.findTask(TaskAttack.UID).orElse(TaskManager.getIdleTask())
                : TaskManager.getIdleTask());
        return maid;
    }

    private static Player mockPlayer(GameTestHelper helper, BlockPos pos) {
        // GameTestHelper's mock player variants report creative/spectator state in ways
        // the combat policy deliberately rejects. Use an explicit ordinary player so the
        // "safe player" gate can be exercised deterministically.
        Player player = new Player(helper.getLevel(), BlockPos.ZERO, 0.0f,
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "test-mock-player")) {
            @Override
            public boolean isCreative() {
                return false;
            }

            @Override
            public boolean isSpectator() {
                return false;
            }
        };
        BlockPos absolute = helper.absolutePos(pos);
        player.setPos(absolute.getX() + 0.5, absolute.getY(), absolute.getZ() + 0.5);
        return player;
    }

    private static Zombie spawnZombie(GameTestHelper helper, BlockPos pos) {
        Zombie zombie = helper.spawn(EntityType.ZOMBIE, pos);
        zombie.setNoAi(true);
        return zombie;
    }

    private static void postOwnerHurt(Player owner, net.minecraft.world.damagesource.DamageSource source) {
        MinecraftForge.EVENT_BUS.post(new LivingHurtEvent(owner, source, 1.0f));
    }

    private static void assertTarget(GameTestHelper helper, EntityMaid maid, LivingEntity expected) {
        LivingEntity actual = maid.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null);
        helper.assertTrue(actual == expected, "expected ATTACK_TARGET=" + expected + " but was " + actual);
    }

    private static void assertNoTarget(GameTestHelper helper, EntityMaid maid) {
        helper.assertTrue(maid.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).isEmpty(),
                "expected no ATTACK_TARGET");
    }

    private static void cleanup(EntityMaid maid, Player... players) {
        if (maid != null) {
            MaidCombatReflex.clear(maid);
            MaidCombatData.reset(maid);
            maid.discard();
        }
        for (Player player : players) {
            if (player != null) {
                player.discard();
            }
        }
    }

    private static void cleanup(EntityMaid maid, Player owner, Zombie zombie) {
        cleanup(maid, new Player[]{owner});
        if (zombie != null) {
            zombie.discard();
        }
    }

    private static void cleanup(EntityMaid maid, Player owner, Zombie zombie, Player attacker) {
        cleanup(maid, new Player[]{owner, attacker});
        if (zombie != null) {
            zombie.discard();
        }
    }
}
