package com.wjx.touhou_aifun.vision.scan;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisionDirectionTest {
    private static final int CENTER_PIXEL = 19;

    @Test
    void yawZeroMatchesMinecraftSouthFacingCoordinateSystem() {
        assertDominant(ScanDirection.FRONT, 0.0F, 0, 1);
        assertDominant(ScanDirection.RIGHT, 0.0F, -1, 0);
        assertDominant(ScanDirection.BACK, 0.0F, 0, -1);
        assertDominant(ScanDirection.LEFT, 0.0F, 1, 0);

        assertEquals("front", classify(0.0F, 0, 10));
        assertEquals("right", classify(0.0F, -10, 0));
        assertEquals("back", classify(0.0F, 0, -10));
        assertEquals("left", classify(0.0F, 10, 0));
    }

    @Test
    void allHorizontalCubemapFacesRoundTripAtEveryCardinalYaw() {
        List<ScanDirection> faces = List.of(ScanDirection.FRONT, ScanDirection.RIGHT,
                ScanDirection.BACK, ScanDirection.LEFT);
        for (float yaw : new float[]{0.0F, 90.0F, 180.0F, 270.0F}) {
            for (ScanDirection face : faces) {
                Vec3 ray = VisionDirectionMath.worldRayDirection(
                        face, CENTER_PIXEL, CENTER_PIXEL, 40, yaw).scale(10.0);
                String classified = VisionDirectionMath.relativeDirection(
                        0, 0, 0, yaw, ray);
                assertEquals(face.name().toLowerCase(), classified,
                        () -> "face=" + face + ", yaw=" + yaw + ", ray=" + ray);
            }
        }
    }

    @Test
    void verticalClassificationUsesFrozenEyeOrigin() {
        assertEquals("up", VisionDirectionMath.relativeDirection(
                4, 20, -7, 135.0F, new Vec3(4, 30, -7)));
        assertEquals("down", VisionDirectionMath.relativeDirection(
                4, 20, -7, 135.0F, new Vec3(4, 10, -7)));
    }

    private static String classify(float yaw, double x, double z) {
        return VisionDirectionMath.relativeDirection(0, 0, 0, yaw, new Vec3(x, 0, z));
    }

    private static void assertDominant(ScanDirection face, float yaw, int expectedXSign, int expectedZSign) {
        Vec3 ray = VisionDirectionMath.worldRayDirection(face, CENTER_PIXEL, CENTER_PIXEL, 40, yaw);
        if (expectedXSign != 0) {
            assertTrue(Math.signum(ray.x) == expectedXSign && Math.abs(ray.x) > Math.abs(ray.z), ray::toString);
        }
        if (expectedZSign != 0) {
            assertTrue(Math.signum(ray.z) == expectedZSign && Math.abs(ray.z) > Math.abs(ray.x), ray::toString);
        }
    }
}
