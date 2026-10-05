package com.wjx.touhou_aifun.vision.scan;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.wjx.touhou_aifun.TouhouAIFun;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Main-thread scheduler for shallow scans. A scan is deliberately not moved to an async executor:
 * Minecraft world state is only safe to read on the server thread. Instead, each server tick gets a
 * strict ray/DDA/section budget and identical requests share the same future.
 */
@Mod.EventBusSubscriber(modid = TouhouAIFun.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class VisionScanScheduler {
    private static final int MAX_RAYS_PER_TICK = 2_000;
    private static final int MAX_DDA_VISITS_PER_TICK = 40_000;
    private static final int MAX_SECTIONS_PER_TICK = 4;
    private static final int HARD_TIMEOUT_TICKS = 40;
    /** Exactly one in-progress scan per maid; identical requests share its future. */
    private static final ConcurrentMap<UUID, Active> ACTIVE = new ConcurrentHashMap<>();
    private static int roundRobinOffset;

    private VisionScanScheduler() {
    }

    public static CompletableFuture<EnvironmentScanResult> request(EntityMaid maid,
                                                                     EnvironmentScanRequest request) {
        EnvironmentScanRequest normalized = request == null ? EnvironmentScanRequest.defaults() : request;
        EnvironmentScanResult cached = VisionScanCache.getIfFresh(maid, normalized);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        VisionScanCache.Key key = VisionScanCache.keyOf(maid, normalized);
        Active active = ACTIVE.compute(maid.getUUID(), (ignored, current) -> {
            if (current != null && current.key.equals(key) && !current.future.isDone()) {
                return current;
            }
            if (current != null) {
                current.job.abort();
                current.future.cancel(false);
            }
            return new Active(key, ShallowEnvironmentScanner.begin(maid, VisionScanCache.geometryRequest(normalized)));
        });
        return active.future.thenApply(result -> result.project(normalized));
    }

    public static void cancelForMaid(UUID maidId) {
        Active active = ACTIVE.remove(maidId);
        if (active != null) {
            active.job.abort();
            active.future.cancel(false);
        }
    }

    public static void cancelAll() {
        ACTIVE.values().forEach(active -> {
            active.job.abort();
            active.future.cancel(false);
        });
        ACTIVE.clear();
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ACTIVE.isEmpty()) {
            return;
        }
        advanceAll();
    }

    /** One shared server-wide allowance, distributed fairly instead of multiplied per maid. */
    private static void advanceAll() {
        List<Map.Entry<UUID, Active>> scans = new ArrayList<>(ACTIVE.entrySet());
        scans.sort(Comparator.comparing(entry -> entry.getKey().toString()));
        if (scans.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, Active> entry : scans) {
            entry.getValue().ticks++;
        }

        int start = Math.floorMod(roundRobinOffset, scans.size());
        int remainingRays = MAX_RAYS_PER_TICK;
        int remainingVisits = MAX_DDA_VISITS_PER_TICK;
        int remainingSections = MAX_SECTIONS_PER_TICK;
        for (int offset = 0; offset < scans.size(); offset++) {
            Map.Entry<UUID, Active> entry = scans.get((start + offset) % scans.size());
            UUID maidId = entry.getKey();
            Active active = entry.getValue();
            if (active.future.isDone()) {
                ACTIVE.remove(maidId, active);
                continue;
            }
            EntityMaid maid = active.job.maid();
            if (maid.isRemoved() || maid.level().isClientSide
                    || !active.key.dimension().equals(maid.level().dimension().location().toString())) {
                active.job.abort();
                active.future.cancel(false);
                ACTIVE.remove(maidId, active);
                continue;
            }

            int jobsLeft = scans.size() - offset;
            int rayShare = ceilingShare(remainingRays, jobsLeft);
            int visitShare = ceilingShare(remainingVisits, jobsLeft);
            int sectionShare = ceilingShare(remainingSections, jobsLeft);
            int beforeRays = active.job.primaryRays();
            int beforeVisits = active.job.ddaVisits();
            int beforeSections = active.job.expandedSections();
            try {
                boolean done = active.job.step(rayShare, visitShare, sectionShare);
                remainingRays = Math.max(0, remainingRays - (active.job.primaryRays() - beforeRays));
                remainingVisits = Math.max(0, remainingVisits - (active.job.ddaVisits() - beforeVisits));
                remainingSections = Math.max(0,
                        remainingSections - (active.job.expandedSections() - beforeSections));
                if (done || active.ticks >= HARD_TIMEOUT_TICKS) {
                    com.wjx.touhou_aifun.chat.agent.AgentTelemetry.stage("scan_execution",active.started,0);
                    if (!done) {
                        active.job.abort("hard_timeout");
                    }
                    EnvironmentScanResult result = active.job.result().completedAt(maid.level().getGameTime());
                    VisionScanCache.put(active.key, result);
                    active.future.complete(result);
                    ACTIVE.remove(maidId, active);
                }
            } catch (Throwable throwable) {
                active.future.completeExceptionally(throwable);
                ACTIVE.remove(maidId, active);
                TouhouAIFun.LOGGER.error("Incremental visual scan failed", throwable);
            }
        }
        roundRobinOffset = (start + 1) % Math.max(1, scans.size());
    }

    private static int ceilingShare(int remaining, int jobsLeft) {
        return remaining <= 0 ? 0 : (remaining + jobsLeft - 1) / jobsLeft;
    }

    private static final class Active {
        private final long started=System.nanoTime();
        private final VisionScanCache.Key key;
        private final ShallowEnvironmentScanner.ScanJob job;
        private final CompletableFuture<EnvironmentScanResult> future = new CompletableFuture<>();
        private int ticks;

        private Active(VisionScanCache.Key key, ShallowEnvironmentScanner.ScanJob job) {
            this.key = key;
            this.job = job;
        }
    }
}
