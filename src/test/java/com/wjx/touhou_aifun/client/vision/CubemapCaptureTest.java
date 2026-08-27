package com.wjx.touhou_aifun.client.vision;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CubemapCaptureTest {
    @Test
    void wideFramebufferIsCenterCroppedWithoutStretching() {
        int[] wide = {
                1, 2, 3, 4,
                5, 6, 7, 8
        };

        assertArrayEquals(new int[]{2, 3, 6, 7},
                CubemapCapture.centerCropSquare(wide, 4, 2, 2));
    }

    @Test
    void tallFramebufferIsCenterCroppedWithoutStretching() {
        int[] tall = {
                1, 2,
                3, 4,
                5, 6,
                7, 8
        };

        assertArrayEquals(new int[]{3, 4, 5, 6},
                CubemapCapture.centerCropSquare(tall, 2, 4, 2));
    }

    @Test
    void rejectsInconsistentFramebufferDimensions() {
        assertThrows(IllegalArgumentException.class,
                () -> CubemapCapture.centerCropSquare(new int[3], 2, 2, 2));
    }
}
