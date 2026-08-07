package com.wjx.touhou_aifun.client.vision;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import com.wjx.touhou_aifun.network.AIFunNetwork;
import com.wjx.touhou_aifun.network.message.AIFunVisionCaptureChunkMessage;
import com.wjx.touhou_aifun.network.message.AIFunVisionCaptureRequestMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.entity.Entity;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;

/**
 * Client-side capture bridge. The renderer is sampled on the render thread and data stays in memory.
 * A later renderer-specific implementation can replace {@link #captureCurrentView}; the wire
 * contract already carries six named cubemap faces and enforces a bounded JPEG payload.
 */
public final class CubemapCapture {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String[] FACES = {"front", "right", "back", "left", "up", "down"};
    private static final int SIZE = 256;
    private static final int CHUNK_SIZE = 24 * 1024;
    private static boolean cubemapProjectionActive;

    private CubemapCapture() {
    }

    public static boolean isCubemapProjectionActive() {
        return cubemapProjectionActive;
    }

    public static void captureAndSend(AIFunVisionCaptureRequestMessage request) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            try {
                // Resolve the maid before capture so a stale request cannot leak the player's screen.
                Entity maid = minecraft.level == null ? null : minecraft.level.getEntity(request.maidId());
                if (maid == null || minecraft.player == null) return;
                if (!(maid instanceof EntityMaid maidEntity)) return;
                Map<String, byte[]> faces = captureCurrentView(minecraft, maidEntity);
                for (String face : FACES) {
                    byte[] data = faces.get(face);
                    if (data == null || data.length == 0 || data.length > 900 * 1024) continue;
                    int chunks = Math.max(1, (data.length + CHUNK_SIZE - 1) / CHUNK_SIZE);
                    for (int index = 0; index < chunks; index++) {
                        int start = index * CHUNK_SIZE;
                        int end = Math.min(data.length, start + CHUNK_SIZE);
                        byte[] part = java.util.Arrays.copyOfRange(data, start, end);
                        AIFunNetwork.sendVisionCaptureChunk(new AIFunVisionCaptureChunkMessage(
                                request.requestId(), request.maidId(), face, index, chunks, part));
                    }
                }
            } catch (Throwable throwable) {
                // The server-side timeout returns an explicit scan-only/failure envelope. Keep the
                // render client alive, but never hide why the image path failed.
                LOGGER.error("Failed to capture visual cubemap request {} for maid {}",
                        request.requestId(), request.maidId(), throwable);
            }
        });
    }

    private static Map<String, byte[]> captureCurrentView(Minecraft minecraft, EntityMaid maid) throws IOException {
        TextureTarget target = new TextureTarget(SIZE, SIZE, true, true);
        Map<String, byte[]> result = new java.util.LinkedHashMap<>();
        float oldYaw = maid.getYRot();
        float oldPitch = maid.getXRot();
        float oldHead = maid.getYHeadRot();
        float oldBody = maid.yBodyRot;
        Entity oldCamera = minecraft.getCameraEntity();
        float[] yaws = {oldYaw, oldYaw + 90.0F, oldYaw + 180.0F, oldYaw - 90.0F, oldYaw, oldYaw};
        float[] pitches = {0.0F, 0.0F, 0.0F, 0.0F, -90.0F, 90.0F};
        try {
            minecraft.setCameraEntity(maid);
            cubemapProjectionActive = true;
            target.bindWrite(true);
            RenderSystem.viewport(0, 0, SIZE, SIZE);
            float partialTick = minecraft.getFrameTime();
            for (int index = 0; index < FACES.length; index++) {
                maid.setYRot(yaws[index]);
                maid.setXRot(pitches[index]);
                maid.setYHeadRot(yaws[index]);
                maid.yBodyRot = yaws[index];
                target.clear(true);
                minecraft.gameRenderer.renderLevel(partialTick, System.nanoTime(), new PoseStack());
                NativeImage screenshot = Screenshot.takeScreenshot(target);
                try {
                    result.put(FACES[index], encodeResized(screenshot));
                } finally {
                    screenshot.close();
                }
            }
        } finally {
            cubemapProjectionActive = false;
            maid.setYRot(oldYaw);
            maid.setXRot(oldPitch);
            maid.setYHeadRot(oldHead);
            maid.yBodyRot = oldBody;
            minecraft.setCameraEntity(oldCamera);
            target.unbindWrite();
            target.destroyBuffers();
            minecraft.getMainRenderTarget().bindWrite(true);
            RenderSystem.viewport(0, 0, minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight());
        }
        return result;
    }

    private static byte[] encodeResized(NativeImage source) throws IOException {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < SIZE; y++) {
            int sourceY = y * source.getHeight() / SIZE;
            for (int x = 0; x < SIZE; x++) {
                int sourceX = x * source.getWidth() / SIZE;
                int rgba = source.getPixelRGBA(sourceX, sourceY);
                int red = rgba & 0xFF;
                int green = (rgba >>> 8) & 0xFF;
                int blue = (rgba >>> 16) & 0xFF;
                image.setRGB(x, y, (red << 16) | (green << 8) | blue);
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(64 * 1024);
        ImageIO.write(image, "jpg", output);
        return output.toByteArray();
    }
}
