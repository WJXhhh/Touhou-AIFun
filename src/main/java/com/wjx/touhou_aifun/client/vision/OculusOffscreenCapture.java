package com.wjx.touhou_aifun.client.vision;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.PipelineManager;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Oculus 1.7+ integration for rendering a second view without borrowing the player's frame.
 *
 * <p>Oculus has no public secondary-camera API. Its LevelRenderer mixins resolve the active
 * pipeline through {@link PipelineManager} on every world render, so the capture installs a
 * separately-owned pipeline for the current dimension while the off-screen framebuffer is active.
 * Both manager fields are restored in a finally block before Minecraft renders the player's frame.
 * This class is only loaded after Forge confirms that Oculus is present.</p>
 */
final class OculusOffscreenCapture {
    private static final Field PIPELINES_FIELD = field("pipelinesPerDimension");
    private static final Field PIPELINE_FIELD = field("pipeline");

    private static IrisRenderingPipeline cachedPipeline;
    private static ShaderPack cachedPack;
    private static NamespacedId cachedDimension;
    private static WorldRenderingPipeline cachedMainPipeline;

    private OculusOffscreenCapture() {
    }

    static boolean isShaderPackActive() {
        return IrisApi.getInstance().isShaderPackInUse() && Iris.getCurrentPack().isPresent();
    }

    static void renderWithIsolatedPipeline(Runnable renderer) {
        if (!isShaderPackActive()) {
            renderer.run();
            return;
        }

        PipelineManager manager = Iris.getPipelineManager();
        NamespacedId dimension = Iris.getCurrentDimension();
        ShaderPack pack = Iris.getCurrentPack().orElseThrow();
        WorldRenderingPipeline mainPipeline = manager.getPipelineNullable();
        IrisRenderingPipeline offscreen = pipelineFor(pack, dimension, mainPipeline);

        try {
            @SuppressWarnings("unchecked")
            Map<NamespacedId, WorldRenderingPipeline> pipelines =
                    (Map<NamespacedId, WorldRenderingPipeline>) PIPELINES_FIELD.get(manager);
            boolean hadDimensionPipeline = pipelines.containsKey(dimension);
            WorldRenderingPipeline previousDimensionPipeline = pipelines.get(dimension);
            WorldRenderingPipeline previousCurrentPipeline =
                    (WorldRenderingPipeline) PIPELINE_FIELD.get(manager);
            try {
                pipelines.put(dimension, offscreen);
                PIPELINE_FIELD.set(manager, offscreen);
                renderer.run();
            } finally {
                if (hadDimensionPipeline) {
                    pipelines.put(dimension, previousDimensionPipeline);
                } else {
                    pipelines.remove(dimension);
                }
                PIPELINE_FIELD.set(manager, previousCurrentPipeline);
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to switch the Oculus off-screen pipeline", exception);
        }
    }

    static void release() {
        if (cachedPipeline != null) {
            cachedPipeline.destroy();
        }
        cachedPipeline = null;
        cachedPack = null;
        cachedDimension = null;
        cachedMainPipeline = null;
    }

    private static IrisRenderingPipeline pipelineFor(ShaderPack pack, NamespacedId dimension,
                                                       WorldRenderingPipeline mainPipeline) {
        if (cachedPipeline == null || cachedPack != pack || !dimension.equals(cachedDimension)
                || cachedMainPipeline != mainPipeline) {
            release();
            cachedPipeline = new IrisRenderingPipeline(pack.getProgramSet(dimension));
            cachedPack = pack;
            cachedDimension = dimension;
            cachedMainPipeline = mainPipeline;
        }
        return cachedPipeline;
    }

    private static Field field(String name) {
        try {
            Field field = PipelineManager.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
