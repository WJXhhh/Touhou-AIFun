package com.wjx.touhou_aifun.maid.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-thread combat state for every maid.
 * <p>
 * Three independent layers are kept:
 * <ul>
 *   <li>automatic owner/self threat (fixed short lifetime);</li>
 *   <li>explicitly commanded target (longer lifetime, survives automatic expiry);</li>
 *   <li>target suppression after a clear/stop command.</li>
 * </ul>
 * Target selection priority is owner threat &gt; commanded target &gt; self threat.
 * Only targets this class actually wrote are ever removed from the brain.
 */
public final class MaidCombatReflex {
    private static final Map<UUID, Threat> THREATS = new ConcurrentHashMap<>();
    private static final Map<UUID, Command> COMMANDS = new ConcurrentHashMap<>();
    private static final Map<UUID, UUID> OWNED_TARGETS = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<UUID, Suppression>> SUPPRESSIONS = new ConcurrentHashMap<>();

    private MaidCombatReflex() {
    }

    public record Threat(UUID targetUuid, int targetEntityId, long untilGameTime, boolean ownerThreat) {
    }

    public record Command(UUID targetUuid, int targetEntityId, long untilGameTime) {
    }

    public record Suppression(UUID targetUuid, int targetEntityId, long untilGameTime) {
    }

    public record CommandResult(boolean success, String reason, String target) {
    }

    public record ClearResult(boolean cleared, String target, String suppressed) {
    }

    // ------------------------------------------------------------------
    // Automatic reflex
    // ------------------------------------------------------------------

    public static void trigger(EntityMaid maid, LivingEntity target, boolean ownerThreat, boolean selfDefense) {
        if (!(maid.level() instanceof ServerLevel level) || target == null) {
            return;
        }
        if (!MaidCombatPolicy.enabled(maid) || isSuppressed(maid, target)) {
            return;
        }
        MaidCombatPolicy.Decision decision = MaidCombatPolicy.decision(maid, target, ownerThreat, false);
        if (!decision.allowed()) {
            MaidCombatPolicy.debug(maid, "reflex denied owner=" + ownerThreat + " target="
                    + target.getUUID() + " reason=" + decision.reason());
            return;
        }
        if (MaidCombatPolicy.engagement(maid, false) == MaidCombatRules.Engagement.NONE) {
            MaidCombatPolicy.debug(maid, "reflex denied engagement=none owner=" + ownerThreat);
            return;
        }
        long until = level.getGameTime() + threatTicks();
        THREATS.compute(maid.getUUID(), (id, existing) -> {
            // An owner threat must not be displaced by a self-defence event.
            if (existing != null && existing.ownerThreat() && !ownerThreat) {
                return existing;
            }
            return new Threat(target.getUUID(), target.getId(), until, ownerThreat);
        });
        Threat effective = THREATS.get(maid.getUUID());
        boolean commandHigher = !ownerThreat && COMMANDS.containsKey(maid.getUUID());
        if (effective != null && effective.targetUuid().equals(target.getUUID()) && !commandHigher) {
            setTarget(maid, target);
            if (selfDefense) {
                maid.setLastHurtByMob(target);
            }
        }
        MaidCombatPolicy.debug(maid, "reflex trigger owner=" + ownerThreat + " target="
                + target.getUUID() + " until=" + until);
    }

    // ------------------------------------------------------------------
    // Commanded target and clear/stop
    // ------------------------------------------------------------------

    public static CommandResult command(EntityMaid maid, LivingEntity target) {
        if (!(maid.level() instanceof ServerLevel level) || target == null) {
            return new CommandResult(false, "target_unavailable", "none");
        }
        if (!MaidCombatPolicy.enabled(maid)) {
            return new CommandResult(false, "combat_disabled", "none");
        }
        MaidCombatPolicy.Decision decision = MaidCombatPolicy.decision(maid, target, false, true);
        if (!decision.allowed()) {
            return new CommandResult(false, decision.reason(), "none");
        }
        if (MaidCombatPolicy.engagement(maid, true) == MaidCombatRules.Engagement.NONE) {
            return new CommandResult(false, "cannot_engage_with_current_task", "none");
        }
        long until = level.getGameTime() + commandTicks();
        COMMANDS.put(maid.getUUID(), new Command(target.getUUID(), target.getId(), until));
        clearSuppression(maid, target);
        Threat threat = THREATS.get(maid.getUUID());
        if (threat == null || !threat.ownerThreat()) {
            setTarget(maid, target);
        }
        if (!(target instanceof net.minecraft.world.entity.player.Player)) {
            maid.setLastHurtByMob(target);
        }
        MaidCombatPolicy.debug(maid, "command target=" + target.getUUID() + " until=" + until);
        return new CommandResult(true, "ok", describe(maid, target));
    }

    public static ClearResult clearAggro(EntityMaid maid, @Nullable Entity specified) {
        if (!(maid.level() instanceof ServerLevel)) {
            return new ClearResult(false, "none", "none");
        }
        LivingEntity current = currentTarget(maid);
        Entity suppress = specified != null ? specified : current;
        UUID id = maid.getUUID();
        LivingEntity brainTarget = maid.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null);
        THREATS.remove(id);
        COMMANDS.remove(id);
        OWNED_TARGETS.remove(id);

        Brain<EntityMaid> brain = maid.getBrain();
        brain.eraseMemory(MemoryModuleType.ATTACK_TARGET);
        brain.eraseMemory(MemoryModuleType.LOOK_TARGET);
        brain.eraseMemory(MemoryModuleType.WALK_TARGET);
        brain.eraseMemory(MemoryModuleType.PATH);
        brain.eraseMemory(MemoryModuleType.HURT_BY);
        brain.eraseMemory(MemoryModuleType.HURT_BY_ENTITY);
        maid.setLastHurtByMob(null);
        maid.getNavigation().stop();

        if (current != null) {
            suppress(maid, current);
        }
        if (specified != null && (current == null || !specified.getUUID().equals(current.getUUID()))) {
            suppress(maid, specified);
        }
        MaidCombatPolicy.debug(maid, "clear aggro current="
                + (brainTarget == null ? "none" : brainTarget.getUUID())
                + " specified=" + (specified == null ? "none" : specified.getUUID()));
        return new ClearResult(true, describe(maid, current),
                describeSuppressions(maid, now(maid)));
    }

    // ------------------------------------------------------------------
    // Resolution and target memory
    // ------------------------------------------------------------------

    @Nullable
    public static LivingEntity currentTarget(EntityMaid maid) {
        if (!(maid.level() instanceof ServerLevel)) {
            return null;
        }
        UUID id = maid.getUUID();
        long now = now(maid);

        Threat ownerThreat = THREATS.get(id);
        if (ownerThreat != null && ownerThreat.ownerThreat()) {
            LivingEntity target = resolve(maid, ownerThreat.targetUuid(), ownerThreat.targetEntityId());
            if (target != null && ownerThreat.untilGameTime() > now
                    && valid(maid, target, true, false)) {
                return target;
            }
            THREATS.remove(id, ownerThreat);
        }

        Command command = COMMANDS.get(id);
        if (command != null) {
            LivingEntity target = resolve(maid, command.targetUuid(), command.targetEntityId());
            if (target != null && command.untilGameTime() > now
                    && valid(maid, target, false, true)) {
                return target;
            }
            COMMANDS.remove(id, command);
        }

        Threat selfThreat = THREATS.get(id);
        if (selfThreat != null) {
            LivingEntity target = resolve(maid, selfThreat.targetUuid(), selfThreat.targetEntityId());
            if (target != null && selfThreat.untilGameTime() > now
                    && valid(maid, target, false, false)) {
                return target;
            }
            THREATS.remove(id, selfThreat);
        }
        return null;
    }

    public static boolean hasLayers(EntityMaid maid) {
        UUID id = maid.getUUID();
        return THREATS.containsKey(id) || COMMANDS.containsKey(id);
    }

    /** True when the supplied target is the explicitly commanded layer. */
    public static boolean isCommandTarget(EntityMaid maid, LivingEntity target) {
        Command command = COMMANDS.get(maid.getUUID());
        return command != null && target != null && command.targetUuid().equals(target.getUUID());
    }

    public static void recheck(EntityMaid maid) {
        if (!MaidCombatPolicy.enabled(maid)) {
            clear(maid);
            return;
        }
        LivingEntity target = currentTarget(maid);
        if (target != null) {
            setTarget(maid, target);
        } else {
            releaseIfOwned(maid);
        }
    }

    /** Called when a target behavior stops: clear only a target this class owns. */
    public static void releaseIfOwned(EntityMaid maid) {
        UUID id = maid.getUUID();
        UUID owned = OWNED_TARGETS.get(id);
        if (owned == null) {
            return;
        }
        if (THREATS.containsKey(id) || COMMANDS.containsKey(id)) {
            return;
        }
        LivingEntity current = maid.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null);
        if (current != null && owned.equals(current.getUUID())) {
            Brain<EntityMaid> brain = maid.getBrain();
            brain.eraseMemory(MemoryModuleType.ATTACK_TARGET);
            brain.eraseMemory(MemoryModuleType.LOOK_TARGET);
            brain.eraseMemory(MemoryModuleType.WALK_TARGET);
            brain.eraseMemory(MemoryModuleType.PATH);
        }
        OWNED_TARGETS.remove(id);
    }

    public static void clear(EntityMaid maid) {
        UUID id = maid.getUUID();
        LivingEntity current = maid.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null);
        UUID owned = OWNED_TARGETS.remove(id);
        THREATS.remove(id);
        COMMANDS.remove(id);
        SUPPRESSIONS.remove(id);
        if (owned != null && current != null && owned.equals(current.getUUID())) {
            Brain<EntityMaid> brain = maid.getBrain();
            brain.eraseMemory(MemoryModuleType.ATTACK_TARGET);
            brain.eraseMemory(MemoryModuleType.LOOK_TARGET);
            brain.eraseMemory(MemoryModuleType.WALK_TARGET);
            brain.eraseMemory(MemoryModuleType.PATH);
        }
    }

    // ------------------------------------------------------------------
    // Suppression
    // ------------------------------------------------------------------

    public static boolean isSuppressed(EntityMaid maid, Entity target) {
        if (target == null || !(maid.level() instanceof ServerLevel)) {
            return false;
        }
        Map<UUID, Suppression> map = SUPPRESSIONS.get(maid.getUUID());
        if (map == null) {
            return false;
        }
        Suppression suppression = map.get(target.getUUID());
        if (suppression == null) {
            return false;
        }
        if (suppression.untilGameTime() <= now(maid)) {
            map.remove(target.getUUID(), suppression);
            if (map.isEmpty()) {
                SUPPRESSIONS.remove(maid.getUUID(), map);
            }
            return false;
        }
        return true;
    }

    private static void suppress(EntityMaid maid, Entity target) {
        long until = now(maid) + aggroClearTicks();
        SUPPRESSIONS.computeIfAbsent(maid.getUUID(), ignored -> new ConcurrentHashMap<>())
                .put(target.getUUID(), new Suppression(target.getUUID(), target.getId(), until));
        MaidCombatPolicy.debug(maid, "suppress target=" + target.getUUID() + " until=" + until);
    }

    private static void clearSuppression(EntityMaid maid, Entity target) {
        Map<UUID, Suppression> map = SUPPRESSIONS.get(maid.getUUID());
        if (map == null) {
            return;
        }
        map.remove(target.getUUID());
        if (map.isEmpty()) {
            SUPPRESSIONS.remove(maid.getUUID(), map);
        }
    }

    // ------------------------------------------------------------------
    // Status / describe
    // ------------------------------------------------------------------

    public static MaidCombatStatus status(EntityMaid maid) {
        MaidCombatData.Snapshot snapshot = MaidCombatData.load(maid);
        LivingEntity current = currentTarget(maid);
        UUID id = maid.getUUID();
        long now = now(maid);
        Command command = COMMANDS.get(id);
        if (command != null && command.untilGameTime() <= now) {
            COMMANDS.remove(id, command);
            command = null;
        }
        LivingEntity commandTarget = command == null ? null
                : resolve(maid, command.targetUuid(), command.targetEntityId());
        long remaining = 0;
        Threat threat = THREATS.get(id);
        if (threat != null) {
            remaining = Math.max(remaining, threat.untilGameTime() - now);
        }
        if (command != null) {
            remaining = Math.max(remaining, command.untilGameTime() - now);
        }
        Map<UUID, Suppression> suppressions = SUPPRESSIONS.get(id);
        if (suppressions != null) {
            for (Suppression suppression : suppressions.values()) {
                remaining = Math.max(remaining, suppression.untilGameTime() - now);
            }
        }
        boolean explicit = command != null;
        String engagement = MaidCombatPolicy.engagement(maid, explicit).name().toLowerCase(Locale.ROOT);
        return new MaidCombatStatus(snapshot.enabled(), MaidCombatData.modeName(snapshot.mode()),
                snapshot.explicitMode(), snapshot.protectOwner(), snapshot.attackPlayers(),
                snapshot.engageRadius(), snapshot.publicMaid(), snapshot.friendlyFireAllowed(),
                MaidCombatPolicy.playerGate(maid), engagement,
                describe(maid, current),
                commandTarget == null ? "none" : describe(maid, commandTarget),
                describeSuppressions(maid, now),
                Math.max(0, remaining), snapshot.notes());
    }

    // ------------------------------------------------------------------
    // Lifecycle / test hooks
    // ------------------------------------------------------------------

    public static void remove(UUID maidId) {
        THREATS.remove(maidId);
        COMMANDS.remove(maidId);
        OWNED_TARGETS.remove(maidId);
        SUPPRESSIONS.remove(maidId);
    }

    public static void serverStopped() {
        THREATS.clear();
        COMMANDS.clear();
        OWNED_TARGETS.clear();
        SUPPRESSIONS.clear();
    }

    public static void clearAllForTests() {
        serverStopped();
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private static boolean valid(EntityMaid maid, LivingEntity target, boolean ownerThreat, boolean explicit) {
        if (isSuppressed(maid, target)) {
            MaidCombatPolicy.debug(maid, "layer invalid suppressed target=" + target.getUUID());
            return false;
        }
        if (MaidCombatPolicy.engagement(maid, explicit) == MaidCombatRules.Engagement.NONE) {
            MaidCombatPolicy.debug(maid, "layer invalid engagement=none owner=" + ownerThreat
                    + " explicit=" + explicit + " target=" + target.getUUID());
            return false;
        }
        MaidCombatPolicy.Decision decision = MaidCombatPolicy.decision(maid, target, ownerThreat, explicit);
        if (!decision.allowed()) {
            MaidCombatPolicy.debug(maid, "layer invalid owner=" + ownerThreat + " explicit=" + explicit
                    + " target=" + target.getUUID() + " reason=" + decision.reason());
            return false;
        }
        return true;
    }

    @Nullable
    private static LivingEntity resolve(EntityMaid maid, UUID targetUuid, int targetEntityId) {
        Entity entity = maid.level().getEntity(targetEntityId);
        if (!(entity instanceof LivingEntity living)) {
            return null;
        }
        if (!living.getUUID().equals(targetUuid) || living.isRemoved() || !living.isAlive()
                || living.level() != maid.level()) {
            return null;
        }
        return living;
    }

    private static void setTarget(EntityMaid maid, LivingEntity target) {
        Brain<EntityMaid> brain = maid.getBrain();
        brain.setMemory(MemoryModuleType.ATTACK_TARGET, target);
        brain.setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(target, true));
        OWNED_TARGETS.put(maid.getUUID(), target.getUUID());
    }

    private static String describe(EntityMaid maid, @Nullable Entity entity) {
        if (entity == null) {
            return "none";
        }
        String name = MaidCombatPolicy.entityName(entity);
        String type = MaidCombatPolicy.entityType(entity);
        String distance = "?";
        if (entity.level() == maid.level()) {
            distance = String.format(Locale.ROOT, "%.1f", Math.sqrt(maid.distanceToSqr(entity)));
        }
        return name + " [" + type + ", id=" + entity.getId() + ", d=" + distance + "]";
    }

    private static String describeSuppressions(EntityMaid maid, long now) {
        Map<UUID, Suppression> map = SUPPRESSIONS.get(maid.getUUID());
        if (map == null || map.isEmpty()) {
            return "none";
        }
        List<String> names = new ArrayList<>();
        for (Suppression suppression : map.values()) {
            if (suppression.untilGameTime() <= now) {
                continue;
            }
            LivingEntity target = resolve(maid, suppression.targetUuid(), suppression.targetEntityId());
            names.add(target == null ? "id=" + suppression.targetEntityId()
                    : MaidCombatPolicy.entityName(target));
        }
        return names.isEmpty() ? "none" : String.join(", ", names);
    }

    private static long now(EntityMaid maid) {
        return maid.level() instanceof ServerLevel level ? level.getGameTime() : 0L;
    }

    private static int threatTicks() {
        return TouhouAIFunConfig.SPEC.isLoaded()
                ? TouhouAIFunConfig.COMBAT_THREAT_MEMORY_TICKS.get() : 200;
    }

    private static int commandTicks() {
        return TouhouAIFunConfig.SPEC.isLoaded()
                ? TouhouAIFunConfig.COMBAT_COMMAND_TICKS.get() : 600;
    }

    private static int aggroClearTicks() {
        return TouhouAIFunConfig.SPEC.isLoaded()
                ? TouhouAIFunConfig.COMBAT_AGGRO_CLEAR_TICKS.get() : 200;
    }
}
