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

    public static CompletableFuture<CaptureResult> requestCapture(EntityMaid maid) {
        if (!(maid.getOwner() instanceof ServerPlayer owner) || owner.connection == null) {
            return CompletableFuture.completedFuture(CaptureResult.failed("owner_offline"));
        }
        if (owner.level() != maid.level()) {
            return CompletableFuture.completedFuture(CaptureResult.failed("owner_in_different_dimension"));
        }
        UUID requestId = UUID.randomUUID();
        PendingCapture pending = new PendingCapture(maid.getId(), maid.getUUID(), owner.getUUID(), owner,
                maid.level().dimension().location().toString(), new CompletableFuture<>());
        PENDING.put(requestId, pending);
        try {
            AIFunNetwork.sendVisionCaptureRequest(owner, requestId, maid.getId(), maid.getUUID());
        } catch (RuntimeException exception) {
            PENDING.remove(requestId, pending);
            return CompletableFuture.completedFuture(CaptureResult.failed("capture_request_send_failed"));
        }
        return pending.future().orTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
                .exceptionally(ignored -> {
                    PendingCapture timedOut = PENDING.remove(requestId);
                    ASSEMBLIES.remove(requestId);
                    if (timedOut != null) AIFunNetwork.sendVisionCaptureCancel(
                            timedOut.ownerPlayer(), requestId, timedOut.maidUuid());
                    return CaptureResult.failed("capture_timeout");
                });
    }

    public static void acceptChunk(ServerPlayer sender, AIFunVisionCaptureChunkMessage message) {
        PendingCapture pending = PENDING.get(message.requestId());
        if (pending == null || sender == null || !pending.owner().equals(sender.getUUID())
                || pending.maidId() != message.maidId()
                || !pending.maidUuid().equals(message.maidUuid())
                || !(sender.level().getEntity(message.maidId()) instanceof EntityMaid maid)
                || !maid.getUUID().equals(message.maidUuid())
                || !maid.isOwnedBy(sender)
                || !pending.dimension().equals(sender.level().dimension().location().toString())) {
            return;
        }
        if (!FACES.contains(message.face()) || message.chunkCount() < 1 || message.chunkCount() > 128
                || message.chunkIndex() < 0 || message.chunkIndex() >= message.chunkCount()
                || message.data() == null || message.data().length > 32 * 1024
                || !Float.isFinite(message.captureYaw()) || message.captureStartTick() < 0
                || message.captureEndTick() < message.captureStartTick()
                || message.captureEndTick() - message.captureStartTick() > 40) {
            fail(message.requestId(), "invalid_capture_chunk");
            return;
        }
        ChunkAssembly assembly = ASSEMBLIES.computeIfAbsent(message.requestId(), ignored -> new ChunkAssembly(
                message.captureYaw(), message.captureStartTick(), message.captureEndTick()));
        if (!assembly.add(message.face(), message.chunkIndex(), message.chunkCount(), message.data(), MAX_TOTAL_BYTES,
                message.captureYaw(), message.captureStartTick(), message.captureEndTick())) {
            fail(message.requestId(), "invalid_or_oversized_capture");
            return;
        }
        if (assembly.complete()) {
            Map<String, String> images = assembly.asDataUrls();
            PendingCapture completed = PENDING.remove(message.requestId());
            ASSEMBLIES.remove(message.requestId());
            if (completed != null) {
                completed.future().complete(CaptureResult.success(images, assembly.captureYaw,
                        assembly.captureStartTick, assembly.captureEndTick));
            }
        }
    }

    public static void acceptFailure(ServerPlayer sender, UUID requestId, int maidId, UUID maidUuid,
                                     String reason) {
        PendingCapture pending = PENDING.get(requestId);
        if (pending == null || sender == null || !pending.owner().equals(sender.getUUID())
                || pending.maidId() != maidId || !pending.maidUuid().equals(maidUuid)) {
            return;
        }
        fail(requestId, reason == null || reason.isBlank() ? "capture_failed" : reason);
    }

    private static void fail(UUID requestId, String reason) {
        PendingCapture pending = PENDING.remove(requestId);
        ASSEMBLIES.remove(requestId);
        if (pending != null) {
            AIFunNetwork.sendVisionCaptureCancel(pending.ownerPlayer(), requestId, pending.maidUuid());
            pending.future().complete(CaptureResult.failed(reason));
        }
    }

    public static void cancelForMaid(int maidId) {
        PENDING.entrySet().removeIf(entry -> {
            if (entry.getValue().maidId() == maidId) {
                AIFunNetwork.sendVisionCaptureCancel(entry.getValue().ownerPlayer(), entry.getKey(),
                        entry.getValue().maidUuid());
                entry.getValue().future().complete(CaptureResult.failed("superseded"));
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
                AIFunNetwork.sendVisionCaptureCancel(entry.getValue().ownerPlayer(), entry.getKey(), maidUuid);
                entry.getValue().future().complete(CaptureResult.failed("superseded"));
                ASSEMBLIES.remove(entry.getKey());
                return true;
            }
            return false;
        });
    }

    public static void cancelAll() {
        PENDING.forEach((requestId, pending) -> {
            AIFunNetwork.sendVisionCaptureCancel(pending.ownerPlayer(), requestId, pending.maidUuid());
            pending.future().complete(CaptureResult.failed("server_stopping"));
        });
        PENDING.clear();
        ASSEMBLIES.clear();
    }

    private record PendingCapture(int maidId, UUID maidUuid, UUID owner, ServerPlayer ownerPlayer, String dimension,
                                  CompletableFuture<CaptureResult> future) {
    }

    public record CaptureResult(boolean success, Map<String, String> images, String error,
                                float captureYaw, long captureStartTick, long captureEndTick) {
        static CaptureResult success(Map<String, String> images, float captureYaw,
                                     long captureStartTick, long captureEndTick) {
            return new CaptureResult(true, Map.copyOf(images), "", captureYaw, captureStartTick, captureEndTick);
        }

        static CaptureResult failed(String error) {
            return new CaptureResult(false, Map.of(), error == null ? "capture_failed" : error,
                    Float.NaN, -1, -1);
        }
    }

    private static final class ChunkAssembly {
        private final Map<String, FaceAssembly> faces = new HashMap<>();
        private final float captureYaw;
        private final long captureStartTick;
        private final long captureEndTick;
        private int bytes;

        private ChunkAssembly(float captureYaw, long captureStartTick, long captureEndTick) {
            this.captureYaw = captureYaw;
            this.captureStartTick = captureStartTick;
            this.captureEndTick = captureEndTick;
        }

        private boolean add(String face, int index, int count, byte[] data, int maxBytes,
                            float captureYaw, long captureStartTick, long captureEndTick) {
            if (Float.compare(this.captureYaw, captureYaw) != 0
                    || this.captureStartTick != captureStartTick || this.captureEndTick != captureEndTick) {
                return false;
            }
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
