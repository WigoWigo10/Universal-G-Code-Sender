package com.willwinder.ugs.platform.surfacescanner.renderable;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;

public class AutoLevelPreviewColorTest {
    private static final float[] LOW = {0.2f, 0.3f, 0.8f, 0.6f};
    private static final float[] HIGH = {0.7f, 0f, 0.1f, 0.8f};
    private static final float[] MIDDLE = {0.91f, 0.91f, 0.91f, 0.7f};

    @Test
    public void colorShouldGoFromLowThroughNeutralToHigh() {
        assertArrayEquals(LOW, AutoLevelPreview.getColorForZ(-0.2, -0.2, 0.4, LOW, HIGH), 1e-6f);
        assertArrayEquals(MIDDLE, AutoLevelPreview.getColorForZ(0.1, -0.2, 0.4, LOW, HIGH), 1e-6f);
        assertArrayEquals(HIGH, AutoLevelPreview.getColorForZ(0.4, -0.2, 0.4, LOW, HIGH), 1e-6f);
        assertArrayEquals(new float[]{0.555f, 0.605f, 0.855f, 0.65f}, AutoLevelPreview.getColorForZ(-0.05, -0.2, 0.4, LOW, HIGH), 1e-6f);
    }

    @Test
    public void colorShouldStayWithinTheRange() {
        assertArrayEquals(LOW, AutoLevelPreview.getColorForZ(-5, -0.2, 0.4, LOW, HIGH), 1e-6f);
        assertArrayEquals(HIGH, AutoLevelPreview.getColorForZ(5, -0.2, 0.4, LOW, HIGH), 1e-6f);
    }

    @Test
    public void flatSurfaceShouldHaveTheNeutralColor() {
        // All heights the same used to give a color that is not a number
        assertArrayEquals(MIDDLE, AutoLevelPreview.getColorForZ(0.1, 0.1, 0.1, LOW, HIGH), 1e-6f);
    }
}
