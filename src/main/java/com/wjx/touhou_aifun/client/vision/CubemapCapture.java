package com.wjx.touhou_aifun.client.vision;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import com.wjx.touhou_aifun.TouhouAIFun;
import com.wjx.touhou_aifun.mixin.client.MinecraftRenderTargetAccessor;
import com.wjx.touhou_aifun.network.AIFunNetwork;
import com.wjx.touhou_aifun.network.message.AIFunVisionCaptureChunkMessage;
import com.wjx.touhou_aifun.network.message.AIFunVisionCaptureFailureMessage;
import com.wjx.touhou_aifun.network.message.AIFunVisionCaptureRequestMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.CameraType;
import net.minecraft.client.Screenshot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModList;

import javax.imageio.ImageIO;
import javax.imageio.IIOImage;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;

/**
 * Client-side capture bridge. The renderer is sampled on the render thread and data stays in memory.
 * The wire contract carries six named cubemap faces and enforces a bounded JPEG payload.
 */
@Mod.EventBusSubscriber(modid = TouhouAIFun.MOD_ID, value = Dist.CLIENT)
public final class CubemapCapture {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String[] FACES = {"front", "right", "back", "left", "up", "down"};
    /**
     * Cubemap face edge length in pixels. Measured against zhipu/stepfun/qwen vision models
     * (scripts/smoke-vision-resolution.py): all three accept 4096×4096 and read detail best at
     * 1024–2048 (OCR degrades at 4096). 1024 keeps six JPEG faces ≈310 KiB, far below the
     * 900 KiB wire limit (VisionCaptureTransport.MAX_TOTAL_BYTES) and single-chunk caps;
     * 2048 would sit ≈800 KiB on the edge. 256 was too coarse for sign/entity detail.
     */
    private static final int SIZE = 1024;
    private static final int CHUNK_SIZE = 24 * 1024;
    private static final float JPEG_QUALITY = 0.82F;
    private static final int MAX_QUEUED_CAPTURES = 4;
    private static final ExecutorService ENCODER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "AIFun-Vision-JPEG");
        thread.setDaemon(true);
        return thread;
    });
    private static boolean cubemapProjectionActive;
    private static boolean offscreenRendering;
    private static TextureTarget offscreenTarget;
    private static final ArrayDeque<CaptureSession> QUEUE = new ArrayDeque<>();
    private static final Map<UUID, UUID> REQUEST_MAIDS = new HashMap<>();
    private static final Set<UUID> CANCELLED_REQUESTS = new HashSet<>();
    private static CaptureSession active;

    private CubemapCapture() {
    }

    public static boolean isCubemapProjectionActive() {
        return cubemapProjectionActive;
    }

    public static void captureAndSend(AIFunVisionCaptureRequestMessage request) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            Entity maid = minecraft.level == null ? null : minecraft.level.getEntity(request.maidId());
            if (minecraft.player == null || minecraft.level == null) {
                fail(request, "client_world_unavailable");
                return;
            }
            if (!(maid instanceof EntityMaid maidEntity) || !maidEntity.getUUID().equals(request.maidUuid())) {
                fail(request, "maid_not_tracked");
                return;
            }
            if (QUEUE.size() + (active == null ? 0 : 1) >= MAX_QUEUED_CAPTURES) {
                fail(request, "capture_queue_full");
                return;
            }
            REQUEST_MAIDS.put(request.requestId(), request.maidUuid());
            QUEUE.addLast(new CaptureSession(request));
        });
    }

    public static void cancelForMaid(UUID maidUuid) {
        if (maidUuid == null) return;
        REQUEST_MAIDS.forEach((requestId, requestMaid) -> {
            if (maidUuid.equals(requestMaid)) CANCELLED_REQUESTS.add(requestId);
        });
        QUEUE.removeIf(session -> {
            if (!maidUuid.equals(session.request.maidUuid())) return false;
            REQUEST_MAIDS.remove(session.request.requestId());
            CANCELLED_REQUESTS.remove(session.request.requestId());
            return true;
        });
        if (active != null && maidUuid.equals(active.request.maidUuid())) {
            REQUEST_MAIDS.remove(active.request.requestId());
            CANCELLED_REQUESTS.remove(active.request.requestId());
            active = null;
        }
    }

    public static void cancelRequest(UUID requestId, UUID maidUuid) {
        if (requestId == null || maidUuid == null || !maidUuid.equals(REQUEST_MAIDS.get(requestId))) return;
        CANCELLED_REQUESTS.add(requestId);
        boolean stoppedBeforeEncoding = QUEUE.removeIf(session -> session.request.requestId().equals(requestId));
        if (active != null && active.request.requestId().equals(requestId)) {
            active = null;
            stoppedBeforeEncoding = true;
        }
        if (stoppedBeforeEncoding) {
            REQUEST_MAIDS.remove(requestId);
            CANCELLED_REQUESTS.remove(requestId);
        }
        // If ImageIO is already encoding, keep the cancellation marker until its completion callback
        // observes it and suppresses all late network chunks.
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        // Encoding jobs cannot always be interrupted inside ImageIO, but marking them cancelled
        // prevents any late packet from leaking into the next server connection.
        CANCELLED_REQUESTS.addAll(REQUEST_MAIDS.keySet());
        for (CaptureSession session : QUEUE) {
            REQUEST_MAIDS.remove(session.request.requestId());
            CANCELLED_REQUESTS.remove(session.request.requestId());
        }
        QUEUE.clear();
        if (active != null) {
            REQUEST_MAIDS.remove(active.request.requestId());
            CANCELLED_REQUESTS.remove(active.request.requestId());
            active = null;
        }
        releaseOffscreenResources();
    }

    /**
     * Renders one face into a private framebuffer before Minecraft renders the player's normal
     * frame. Six displayed frames complete a request without ever exposing the capture camera to
     * the display or doing six full world renders in one frame. With an active Oculus shader pack,
     * a separately-owned Iris pipeline is installed for the duration of this method.
     */
    public static void captureOffscreenFrame(float partialTick, long finishTimeNano) {
        CaptureSession session = active;
        if (session == null || session.startTick < 0 || offscreenRendering) return;

        Minecraft minecraft = Minecraft.getInstance();
        Entity entity = minecraft.level == null ? null : minecraft.level.getEntity(session.request.maidId());
        if (!(entity instanceof EntityMaid maid) || !maid.getUUID().equals(session.request.maidUuid())) {
            finishFailure(session, "maid_not_tracked", null);
            return;
        }

        RenderTarget playerTarget = minecraft.getMainRenderTarget();
        Entity playerCamera = minecraft.getCameraEntity();
        CameraType playerCameraType = minecraft.options.getCameraType();
        TextureTarget captureTarget = ensureOffscreenTarget();
        ArmorStand captureCamera = createCaptureCamera(minecraft, maid, session);
        MinecraftRenderTargetAccessor targetAccessor = (MinecraftRenderTargetAccessor) minecraft;
        Throwable failure = null;

        offscreenRendering = true;
        cubemapProjectionActive = true;
        try {
            targetAccessor.touhouAIFun$setMainRenderTarget(captureTarget);
            minecraft.options.setCameraType(CameraType.FIRST_PERSON);
            minecraft.setCameraEntity(captureCamera);

            int face = session.faceIndex;
            Runnable renderFace = () -> {
                if (face == 0) {
                    LOGGER.info("Starting off-screen cubemap capture request={} maid={} framebuffer={}x{} oculus={}",
                            session.request.requestId(), session.request.maidId(), captureTarget.width,
                            captureTarget.height, ModList.get().isLoaded("oculus"));
                }
                orientCaptureCamera(captureCamera, session.captureYaw, face);
                captureTarget.setClearColor(0.0F, 0.0F, 0.0F, 1.0F);
                captureTarget.clear(Minecraft.ON_OSX);
                captureTarget.bindWrite(true);
                minecraft.gameRenderer.renderLevel(partialTick, finishTimeNano, new PoseStack());
                NativeImage screenshot = Screenshot.takeScreenshot(captureTarget);
                try {
                    session.pixels.put(FACES[face], centerCropSquare(
                            screenshot.getPixelsRGBA(), screenshot.getWidth(), screenshot.getHeight(), SIZE));
                } finally {
                    screenshot.close();
                }
                session.faceIndex = face + 1;
            };

            if (ModList.get().isLoaded("oculus") && OculusOffscreenCapture.isShaderPackActive()) {
                OculusOffscreenCapture.renderWithIsolatedPipeline(renderFace);
            } else {
                renderFace.run();
            }
            if (minecraft.level != null) session.endTick = minecraft.level.getGameTime();
        } catch (Throwable throwable) {
            failure = throwable;
        } finally {
            minecraft.setCameraEntity(playerCamera);
            minecraft.options.setCameraType(playerCameraType);
            targetAccessor.touhouAIFun$setMainRenderTarget(playerTarget);
            playerTarget.bindWrite(true);
            cubemapProjectionActive = false;
            offscreenRendering = false;
        }

        if (failure != null) {
            finishFailure(session, "client_offscreen_capture_failed", failure);
            return;
        }
        if (session.faceIndex >= FACES.length) {
            LOGGER.info("Finished off-screen cubemap capture request={} maid={} faces={} gameTicks={}-{}",
                    session.request.requestId(), session.request.maidId(), session.faceIndex,
                    session.startTick, session.endTick);
            finishCapture(minecraft, session);
        }
    }

    private static TextureTarget ensureOffscreenTarget() {
        if (offscreenTarget == null || offscreenTarget.width != SIZE || offscreenTarget.height != SIZE) {
            if (offscreenTarget != null) offscreenTarget.destroyBuffers();
            offscreenTarget = new TextureTarget(SIZE, SIZE, true, Minecraft.ON_OSX);
        }
        return offscreenTarget;
    }

    private static ArmorStand createCaptureCamera(Minecraft minecraft, EntityMaid maid,
                                                   CaptureSession session) {
        ArmorStand camera = new ArmorStand(minecraft.level, session.captureX, session.captureY, session.captureZ);
        camera.setInvisible(true);
        double eyeY = maid.getEyeY();
        camera.setPos(session.captureX, eyeY - camera.getEyeHeight(), session.captureZ);
        camera.xo = camera.xOld = camera.getX();
        camera.yo = camera.yOld = camera.getY();
        camera.zo = camera.zOld = camera.getZ();
        return camera;
    }

    private static void orientCaptureCamera(ArmorStand camera, float baseYaw, int faceIndex) {
        float[] yawOffsets = {0.0F, 90.0F, 180.0F, -90.0F, 0.0F, 0.0F};
        float[] pitches = {0.0F, 0.0F, 0.0F, 0.0F, -90.0F, 90.0F};
        float yaw = baseYaw + yawOffsets[faceIndex];
        float pitch = pitches[faceIndex];
        camera.setYRot(yaw);
        camera.setXRot(pitch);
        camera.setYHeadRot(yaw);
        camera.yBodyRot = yaw;
        camera.yRotO = yaw;
        camera.xRotO = pitch;
        camera.yHeadRotO = yaw;
        camera.yBodyRotO = yaw;
    }

    private static void releaseOffscreenResources() {
        if (offscreenTarget != null) {
            offscreenTarget.destroyBuffers();
            offscreenTarget = null;
        }
        if (ModList.get().isLoaded("oculus")) {
            OculusOffscreenCapture.release();
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (active == null) active = QUEUE.pollFirst();
        if (active == null) return;
        CaptureSession session = active;
        try {
            Entity entity = minecraft.level == null ? null : minecraft.level.getEntity(session.request.maidId());
            if (minecraft.player == null || minecraft.level == null
                    || !(entity instanceof EntityMaid maid)
                    || !maid.getUUID().equals(session.request.maidUuid())) {
                finishFailure(session, "maid_not_tracked", null);
                return;
            }
            if (session.startTick < 0) {
                session.captureYaw = maid.getYRot();
                session.captureX = maid.getX();
                session.captureY = maid.getY();
                session.captureZ = maid.getZ();
                session.startTick = minecraft.level.getGameTime();
            }
            // The next normal GameRenderer.renderLevel call captures one face. Never invoke
            // renderLevel from a client tick: Oculus/Iris pipelines require their begin/composite
            // lifecycle to be owned by Minecraft's regular frame and remain corrupted otherwise.
        } catch (Throwable throwable) {
            finishFailure(session, "client_capture_failed", throwable);
        }
    }

    private static void fail(AIFunVisionCaptureRequestMessage request, String reason) {
        REQUEST_MAIDS.remove(request.requestId());
        CANCELLED_REQUESTS.remove(request.requestId());
        AIFunNetwork.sendVisionCaptureFailure(new AIFunVisionCaptureFailureMessage(
                request.requestId(), request.maidId(), request.maidUuid(), reason));
    }

    private static void finishCapture(Minecraft minecraft, CaptureSession session) {
        active = null;
        LOGGER.info("Submitting cubemap JPEG encoding request={} maid={} faces={} gameTicks={}-{}",
                session.request.requestId(), session.request.maidId(), session.faceIndex,
                session.startTick, session.endTick);
        CompletableFuture.supplyAsync(() -> encodeFaces(session.pixels), ENCODER)
                .whenComplete((faces, error) -> minecraft.execute(() -> {
                    if (CANCELLED_REQUESTS.remove(session.request.requestId())) {
                        REQUEST_MAIDS.remove(session.request.requestId());
                        return;
                    }
                    if (error != null || faces == null) {
                        LOGGER.error("Failed to encode visual cubemap request {} for maid {}",
                                session.request.requestId(), session.request.maidId(), error);
                        fail(session.request, "client_encode_failed");
                        return;
                    }
                    sendFaces(session.request, faces, session.captureYaw,
                            session.startTick, session.endTick);
                }));
    }

    /**
     * Extract the centered square from a correctly proportioned framebuffer and resample it. The
     * projection renders extra field of view only along the framebuffer's longer axis, so this crop
     * removes that deliberate margin while retaining the exact 90 by 90 degree cubemap face.
     */
    static int[] centerCropSquare(int[] source, int sourceWidth, int sourceHeight, int targetSize) {
        if (source == null || sourceWidth <= 0 || sourceHeight <= 0 || targetSize <= 0
                || source.length != sourceWidth * sourceHeight) {
            throw new IllegalArgumentException("invalid visual framebuffer dimensions");
        }
        int sourceSize = Math.min(sourceWidth, sourceHeight);
        int sourceLeft = (sourceWidth - sourceSize) / 2;
        int sourceTop = (sourceHeight - sourceSize) / 2;
        int[] target = new int[targetSize * targetSize];
        for (int y = 0; y < targetSize; y++) {
            int sourceY = sourceTop + Math.min(sourceSize - 1,
                    (int) (((2L * y + 1L) * sourceSize) / (2L * targetSize)));
            int targetOffset = y * targetSize;
            int sourceOffset = sourceY * sourceWidth;
            for (int x = 0; x < targetSize; x++) {
                int sourceX = sourceLeft + Math.min(sourceSize - 1,
                        (int) (((2L * x + 1L) * sourceSize) / (2L * targetSize)));
                target[targetOffset + x] = source[sourceOffset + sourceX];
            }
        }
        return target;
    }

    private static void finishFailure(CaptureSession session, String reason, Throwable throwable) {
        if (throwable != null) {
            LOGGER.error("Failed to capture visual cubemap request {} for maid {}",
                    session.request.requestId(), session.request.maidId(), throwable);
        }
        active = null;
        fail(session.request, reason);
    }

    private static Map<String, byte[]> encodeFaces(Map<String, int[]> pixels) {
        try {
            Map<String, byte[]> encoded = new java.util.LinkedHashMap<>();
            int blankWhiteFaces = 0;
            for (String face : FACES) {
                int[] values = pixels.get(face);
                if (values == null || values.length != SIZE * SIZE) throw new IOException("missing face " + face);
                if (isBlankWhite(values)) blankWhiteFaces++;
                encoded.put(face, encodeJpeg(values));
            }
            if (blankWhiteFaces == FACES.length) {
                throw new IOException("all cubemap faces are blank white");
            }
            return encoded;
        } catch (IOException exception) {
            throw new java.util.concurrent.CompletionException(exception);
        }
    }

    private static boolean isBlankWhite(int[] pixels) {
        for (int index = 0; index < pixels.length; index += 64) {
            int rgba = pixels[index];
            if ((rgba & 0xFF) < 248 || ((rgba >>> 8) & 0xFF) < 248
                    || ((rgba >>> 16) & 0xFF) < 248) {
                return false;
            }
        }
        return true;
    }

    private static byte[] encodeJpeg(int[] pixels) throws IOException {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int rgba = pixels[y * SIZE + x];
                int red = rgba & 0xFF;
                int green = (rgba >>> 8) & 0xFF;
                int blue = (rgba >>> 16) & 0xFF;
                image.setRGB(x, y, (red << 16) | (green << 8) | blue);
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(64 * 1024);
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            if (parameters.canWriteCompressed()) {
                parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                parameters.setCompressionQuality(JPEG_QUALITY);
            }
            writer.write(null, new IIOImage(image, null, null), parameters);
        } finally {
            writer.dispose();
        }
        return output.toByteArray();
    }

    private static void sendFaces(AIFunVisionCaptureRequestMessage request, Map<String, byte[]> faces,
                                  float captureYaw, long startTick, long endTick) {
        long totalBytes = 0;
        for (String face : FACES) {
            byte[] data = faces.get(face);
            if (data == null || data.length == 0 || data.length > 900 * 1024) {
                fail(request, "invalid_or_oversized_face");
                return;
            }
            totalBytes += data.length;
        }
        if (totalBytes > 900 * 1024) {
            fail(request, "capture_total_too_large");
            return;
        }
        for (String face : FACES) {
            byte[] data = faces.get(face);
            int chunks = Math.max(1, (data.length + CHUNK_SIZE - 1) / CHUNK_SIZE);
            for (int index = 0; index < chunks; index++) {
                int start = index * CHUNK_SIZE;
                int end = Math.min(data.length, start + CHUNK_SIZE);
                byte[] part = java.util.Arrays.copyOfRange(data, start, end);
                AIFunNetwork.sendVisionCaptureChunk(new AIFunVisionCaptureChunkMessage(
                        request.requestId(), request.maidId(), request.maidUuid(), face, index, chunks, part,
                        captureYaw, startTick, endTick));
            }
        }
        REQUEST_MAIDS.remove(request.requestId());
        CANCELLED_REQUESTS.remove(request.requestId());
    }

    private static final class CaptureSession {
        private final AIFunVisionCaptureRequestMessage request;
        private final Map<String, int[]> pixels = new java.util.LinkedHashMap<>();
        private int faceIndex;
        private float captureYaw;
        private double captureX;
        private double captureY;
        private double captureZ;
        private long startTick = -1;
        private long endTick = -1;

        private CaptureSession(AIFunVisionCaptureRequestMessage request) {
            this.request = request;
        }

    }
}
