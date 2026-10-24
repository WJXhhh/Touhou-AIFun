package com.wjx.touhou_aifun.chat.agent.build;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.Gson;
import com.wjx.touhou_aifun.TouhouAIFun;
import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Persistence for the build queue. State is stored on the maid entity's persistent data as one
 * UTF-8 Gson JSON blob. Loading is deliberately failure-tolerant: a corrupt or oversized blob is
 * logged and replaced by a fresh empty state instead of throwing into the world tick.
 */
public final class BuildQueueData {
    public static final String TAG = "TouhouAIFunBuildV1";
    public static final int MAX_BYTES = 1024 * 1024;

    private static final Gson GSON = new Gson();
    private static final Map<UUID, BuildQueueState> CACHE = new HashMap<>();

    private BuildQueueData() {
    }

    /** Lazy-loads and repairs the maid's queue. Server-thread use only. */
    public static BuildQueueState get(EntityMaid maid) {
        UUID id = maid.getUUID();
        BuildQueueState cached = CACHE.get(id);
        if (cached != null) return cached;
        BuildQueueState loaded = load(maid);
        CACHE.put(id, loaded);
        return loaded;
    }

    private static BuildQueueState load(EntityMaid maid) {
        BuildQueueState state = new BuildQueueState();
        CompoundTag data = maid.getPersistentData();
        if (data == null || !data.contains(TAG)) {
            state.ownerUUID = ownerOf(maid);
            state.updatedTick = gameTime(maid);
            return state;
        }
        byte[] bytes;
        try {
            bytes = data.getByteArray(TAG);
        } catch (RuntimeException broken) {
            TouhouAIFun.LOGGER.warn("AIFun build queue unreadable for maid {}; starting empty", maid.getUUID());
            return fresh(maid, state);
        }
        if (bytes == null || bytes.length == 0) return fresh(maid, state);
        if (bytes.length > MAX_BYTES) {
            TouhouAIFun.LOGGER.warn("AIFun build queue oversized ({} bytes) for maid {}; starting empty",
                    bytes.length, maid.getUUID());
            return fresh(maid, state);
        }
        BuildQueueState parsed;
        try {
            parsed = GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), BuildQueueState.class);
        } catch (RuntimeException corrupt) {
            TouhouAIFun.LOGGER.warn("AIFun build queue corrupt for maid {}; starting empty", maid.getUUID(), corrupt);
            return fresh(maid, state);
        }
        if (parsed == null || parsed.schemaVersion != BuildQueueState.SCHEMA_VERSION) {
            TouhouAIFun.LOGGER.warn("AIFun build queue unsupported schema for maid {}; starting empty", maid.getUUID());
            return fresh(maid, state);
        }
        boolean repaired = parsed.repair();
        String owner = ownerOf(maid);
        if (parsed.ownerUUID.isBlank()) {
            parsed.ownerUUID = owner;
            repaired = true;
        } else if (!parsed.ownerUUID.equals(owner)) {
            // A different owner must explicitly resume the old designs instead of silently running them.
            parsed.status = "paused";
            parsed.pausedReason = "owner_changed";
            repaired = true;
        }
        if ("running".equals(parsed.status)) {
            parsed.status = "paused";
            parsed.pausedReason = "world_reloaded";
            repaired = true;
        }
        if (!"paused".equals(parsed.status) && !"blocked".equals(parsed.status)) {
            parsed.status = "idle";
            parsed.pausedReason = "";
        }
        parsed.updatedTick = gameTime(maid);
        if (repaired) {
            TouhouAIFun.LOGGER.info("AIFun build queue repaired for maid {}: designs={} steps={} status={}",
                    maid.getUUID(), parsed.designs.size(), parsed.steps.size(), parsed.status);
        }
        return parsed;
    }

    private static BuildQueueState fresh(EntityMaid maid, BuildQueueState reuse) {
        BuildQueueState state = new BuildQueueState();
        state.ownerUUID = ownerOf(maid);
        state.updatedTick = gameTime(maid);
        return state;
    }

    /** Persists the queue. A state that cannot fit is logged and skipped, never thrown into a tick. */
    public static void save(EntityMaid maid) {
        BuildQueueState state = CACHE.get(maid.getUUID());
        if (state == null) return;
        if (state.ownerUUID == null || state.ownerUUID.isBlank()) state.ownerUUID = ownerOf(maid);
        byte[] bytes;
        try {
            bytes = GSON.toJson(state).getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException failed) {
            TouhouAIFun.LOGGER.warn("AIFun build queue serialization failed for maid {}", maid.getUUID(), failed);
            return;
        }
        if (bytes.length > MAX_BYTES) {
            TouhouAIFun.LOGGER.warn("AIFun build queue save skipped for maid {} ({} bytes > {}): state unchanged in memory",
                    maid.getUUID(), bytes.length, MAX_BYTES);
            return;
        }
        CompoundTag data = maid.getPersistentData();
        if (data == null) return;
        data.putByteArray(TAG, bytes);
    }

    /**
     * Throws only for a caller's new mutation that cannot be persisted; ordinary periodic saves use
     * {@link #save(EntityMaid)} and skip instead.
     */
    public static void ensureWithinLimit(EntityMaid maid, BuildQueueState state) {
        int size = serializedSize(state);
        if (size > MAX_BYTES) throw new IllegalStateException("build_queue_state_too_large");
    }

    static int serializedSize(BuildQueueState state) {
        try {
            return GSON.toJson(state).getBytes(StandardCharsets.UTF_8).length;
        } catch (RuntimeException failed) {
            return Integer.MAX_VALUE;
        }
    }

    /** Drops only the in-memory cache; persistent data is left untouched. */
    public static void clearAll() {
        CACHE.clear();
    }

    public static void forget(UUID maidId) {
        CACHE.remove(maidId);
    }

    /** Cached state for lifecycle callbacks that only have a maid UUID (never loads from disk). */
    @Nullable
    public static BuildQueueState cached(UUID maidId) {
        return maidId == null ? null : CACHE.get(maidId);
    }

    private static String ownerOf(EntityMaid maid) {
        UUID owner = maid.getOwnerUUID();
        return owner == null ? "" : owner.toString();
    }

    private static long gameTime(EntityMaid maid) {
        try {
            return maid.level().getGameTime();
        } catch (RuntimeException unavailable) {
            return 0L;
        }
    }
}
