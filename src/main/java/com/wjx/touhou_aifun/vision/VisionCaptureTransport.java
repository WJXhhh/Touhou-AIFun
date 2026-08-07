package com.wjx.touhou_aifun.vision;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.wjx.touhou_aifun.network.AIFunNetwork;
import net.minecraft.server.level.ServerPlayer;
import com.wjx.touhou_aifun.network.message.AIFunVisionCaptureChunkMessage;

import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory request table for six-face captures; no screenshots are written to disk. */
public final class VisionCaptureTransport {
    private static final Map<UUID, PendingCapture> PENDING = new ConcurrentHashMap<>();
    private static final Map<UUID, ChunkAssembly> ASSEMBLIES = new ConcurrentHashMap<>();
    private static final java.util.Set<String> FACES = java.util.Set.of("front", "right", "back", "left", "up", "down");
    private static final int MAX_TOTAL_BYTES = 900 * 1024;

    private VisionCaptureTransport() {
    }

    public static CompletableFuture<Map<String, String>> requestCapture(EntityMaid maid, String focus, String scanJson) {
        if (!(maid.getOwner() instanceof ServerPlayer owner) || owner.connection == null) {
            return CompletableFuture.completedFuture(Map.of());
        }
        UUID requestId = UUID.randomUUID();
        PendingCapture pending = new PendingCapture(maid.getId(), maid.getUUID(), owner.getUUID(),
                maid.level().dimension().location().toString(), System.currentTimeMillis(), new CompletableFuture<>());
        PENDING.put(requestId, pending);
        AIFunNetwork.sendVisionCaptureRequest(owner, requestId, maid.getId(), focus, scanJson);
        return pending.future().orTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
                .exceptionally(ignored -> {
                    PENDING.remove(requestId);
                    ASSEMBLIES.remove(requestId);
                    return Map.of();
                });
    }

    public static void complete(UUID requestId, ServerPlayer sender, int maidId, Map<String, String> images) {
        PendingCapture pending = PENDING.remove(requestId);
        if (pending == null || sender == null || !pending.owner().equals(sender.getUUID())
                || pending.maidId() != maidId || images == null || images.isEmpty()) {
            return;
        }
        pending.future().complete(Map.copyOf(images));
    }

    public static void acceptChunk(ServerPlayer sender, AIFunVisionCaptureChunkMessage message) {
        PendingCapture pending = PENDING.get(message.requestId());
        if (pending == null || sender == null || !pending.owner().equals(sender.getUUID())
                || pending.maidId() != message.maidId()
                || !(sender.level().getEntity(message.maidId()) instanceof EntityMaid maid)
                || !maid.isOwnedBy(sender)
                || !pending.dimension().equals(sender.level().dimension().location().toString())) {
            return;
        }
        if (!FACES.contains(message.face()) || message.chunkCount() < 1 || message.chunkCount() > 128
                || message.chunkIndex() < 0 || message.chunkIndex() >= message.chunkCount()
                || message.data() == null || message.data().length > 32 * 1024) {
            return;
        }
        ChunkAssembly assembly = ASSEMBLIES.computeIfAbsent(message.requestId(), ignored -> new ChunkAssembly());
        if (!assembly.add(message.face(), message.chunkIndex(), message.chunkCount(), message.data(), MAX_TOTAL_BYTES)) {
            PENDING.remove(message.requestId());
            ASSEMBLIES.remove(message.requestId());
            return;
        }
        if (assembly.complete()) {
            Map<String, String> images = assembly.asDataUrls();
            PendingCapture completed = PENDING.remove(message.requestId());
            ASSEMBLIES.remove(message.requestId());
            if (completed != null) {
                completed.future().complete(Map.copyOf(images));
            }
        }
    }

    public static void cancelForMaid(int maidId) {
        PENDING.entrySet().removeIf(entry -> {
            if (entry.getValue().maidId() == maidId) {
                entry.getValue().future().complete(Map.of());
                ASSEMBLIES.remove(entry.getKey());
                return true;
            }
            return false;
        });
    }

    public static void cancelForMaid(UUID maidUuid) {
        if (maidUuid == null) return;
        PENDING.entrySet().removeIf(entry -> {
            if (maidUuid.equals(entry.getValue().maidUuid())) {
                entry.getValue().future().complete(Map.of());
                ASSEMBLIES.remove(entry.getKey());
                return true;
            }
            return false;
        });
    }

    private record PendingCapture(int maidId, UUID maidUuid, UUID owner, String dimension, long createdAt,
                                  CompletableFuture<Map<String, String>> future) {
    }

    private static final class ChunkAssembly {
        private final Map<String, FaceAssembly> faces = new HashMap<>();
        private int bytes;

        private boolean add(String face, int index, int count, byte[] data, int maxBytes) {
            FaceAssembly assembly = faces.computeIfAbsent(face, ignored -> new FaceAssembly(count));
            if (assembly.count != count || assembly.chunks.containsKey(index)) return false;
            bytes += data.length;
            if (bytes > maxBytes) return false;
            assembly.chunks.put(index, data.clone());
            return true;
        }

        private boolean complete() {
            return faces.size() == FACES.size() && faces.entrySet().stream()
                    .allMatch(entry -> entry.getValue().chunks.size() == entry.getValue().count);
        }

        private Map<String, String> asDataUrls() {
            Map<String, String> result = new LinkedHashMap<>();
            for (String face : FACES) {
                FaceAssembly assembly = faces.get(face);
                int length = assembly.chunks.values().stream().mapToInt(bytes -> bytes.length).sum();
                byte[] merged = new byte[length];
                int offset = 0;
                for (int i = 0; i < assembly.count; i++) {
                    byte[] bytes = assembly.chunks.get(i);
                    System.arraycopy(bytes, 0, merged, offset, bytes.length);
                    offset += bytes.length;
                }
                result.put(face, "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(merged));
            }
            return result;
        }
    }

    private static final class FaceAssembly {
        private final int count;
        private final Map<Integer, byte[]> chunks = new HashMap<>();

        private FaceAssembly(int count) {
            this.count = count;
        }
    }
}
