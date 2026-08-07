package com.wjx.touhou_aifun.vision.scan;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.wjx.touhou_aifun.TouhouAIFun;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

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
    private static final ConcurrentMap<VisionScanCache.Key, Active> ACTIVE = new ConcurrentHashMap<>();

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
        Active active = ACTIVE.computeIfAbsent(key,
                ignored -> new Active(ShallowEnvironmentScanner.begin(maid, normalized)));
        return active.future;
    }

    public static void cancelForMaid(UUID maidId) {
        ACTIVE.entrySet().removeIf(entry -> {
            if (!entry.getKey().maid().equals(maidId)) {
                return false;
            }
            entry.getValue().job.abort();
            entry.getValue().future.cancel(false);
            return true;
        });
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ACTIVE.isEmpty()) {
            return;
        }
        ACTIVE.entrySet().removeIf(entry -> advance(entry.getKey(), entry.getValue()));
    }

    private static boolean advance(VisionScanCache.Key key, Active active) {
        if (active.future.isCancelled()) {
            return true;
        }
        EntityMaid maid = active.job.maid();
        if (maid.isRemoved() || maid.level().isClientSide
                || !key.dimension().equals(maid.level().dimension().location().toString())) {
            active.future.cancel(false);
            return true;
        }
        try {
            active.ticks++;
            boolean done = active.job.step(MAX_RAYS_PER_TICK, MAX_DDA_VISITS_PER_TICK, MAX_SECTIONS_PER_TICK);
            if (!done && active.ticks < HARD_TIMEOUT_TICKS) {
                return false;
            }
            if (!done) {
                active.job.abort();
            }
            EnvironmentScanResult result = active.job.result();
            VisionScanCache.put(key, result);
            active.future.complete(result);
        } catch (Throwable throwable) {
            active.future.completeExceptionally(throwable);
            TouhouAIFun.LOGGER.error("Incremental visual scan failed", throwable);
        }
        return true;
    }

    private static final class Active {
        private final ShallowEnvironmentScanner.ScanJob job;
        private final CompletableFuture<EnvironmentScanResult> future = new CompletableFuture<>();
        private int ticks;

        private Active(ShallowEnvironmentScanner.ScanJob job) {
            this.job = job;
        }
    }
}
