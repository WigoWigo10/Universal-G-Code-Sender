package com.willwinder.ugs.nbp.pcb.ui;

import com.willwinder.ugs.nbp.pcb.geometry.Point2D;
import com.willwinder.ugs.nbp.pcb.geometry.RigidTransform2D;
import com.willwinder.ugs.nbp.pcb.ui.AlignmentModel.ReferencePoint;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

public class AlignmentPreviewPanelTest {
    private static final Color VECTOR_COLOR = new Color(230, 130, 0);

    private static BufferedImage paint(AlignmentPreviewPanel panel) {
        panel.setSize(360, 320);
        BufferedImage image = new BufferedImage(360, 320, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        panel.paint(graphics);
        graphics.dispose();
        return image;
    }

    private static boolean hasColor(BufferedImage image, Color color) {
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                Color pixel = new Color(image.getRGB(x, y));
                if (Math.abs(pixel.getRed() - color.getRed()) < 12 && Math.abs(pixel.getGreen() - color.getGreen()) < 12 && Math.abs(pixel.getBlue() - color.getBlue()) < 12) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    public void magnificationShouldMakeTheLongestArrowClearlyVisible() {
        // A displacement of 0.3 mm on a board of 100 mm is drawn 20 times as long, which is 6 mm
        assertEquals(20, AlignmentPreviewPanel.getMagnification(100, 0.3));
        assertEquals(50, AlignmentPreviewPanel.getMagnification(100, 0.2));
        assertEquals(100, AlignmentPreviewPanel.getMagnification(100, 0.1));
        assertEquals(1000, AlignmentPreviewPanel.getMagnification(100, 0.012));
    }

    @Test
    public void magnificationShouldBeARoundNumber() {
        for (double displacement = 0.003; displacement < 20; displacement *= 1.37) {
            int magnification = AlignmentPreviewPanel.getMagnification(92, displacement);
            String digits = String.valueOf(magnification).replace("0", "");

            assertTrue("Magnification " + magnification, digits.equals("1") || digits.equals("2") || digits.equals("5"));
            assertTrue("The arrow is longer than a part of the drawing", magnification == 1 || magnification * displacement <= 0.12 * 92 + 1e-9);
        }
    }

    @Test
    public void magnificationShouldNotMakeArrowsShorterThanTheDisplacement() {
        assertEquals(1, AlignmentPreviewPanel.getMagnification(100, 12));
        assertEquals(1, AlignmentPreviewPanel.getMagnification(100, 500));
    }

    @Test
    public void magnificationShouldBeOneWhenThereIsNothingToMagnify() {
        assertEquals(1, AlignmentPreviewPanel.getMagnification(100, 0));
        assertEquals(1, AlignmentPreviewPanel.getMagnification(0, 0.1));
        assertEquals(1, AlignmentPreviewPanel.getMagnification(100, Double.NaN));
    }

    @Test
    public void detailSizeShouldHaveRoomForTheLargestDistance() {
        assertEquals(0.01, AlignmentPreviewPanel.getDetailSize(0.0062), 1e-12);
        assertEquals(0.05, AlignmentPreviewPanel.getDetailSize(0.03), 1e-12);
        assertEquals(0.2, AlignmentPreviewPanel.getDetailSize(0.1), 1e-12);
        assertEquals(1, AlignmentPreviewPanel.getDetailSize(0.5), 1e-12);
    }

    @Test
    public void detailSizeShouldNotBeSmallerThanWhatCanBeMeasured() {
        assertEquals(0.01, AlignmentPreviewPanel.getDetailSize(0), 1e-12);
        assertEquals(0.01, AlignmentPreviewPanel.getDetailSize(0.0001), 1e-12);
        assertEquals(0.01, AlignmentPreviewPanel.getDetailSize(Double.NaN), 1e-12);
    }

    @Test
    public void roundingShouldGiveOneTwoOrFiveTimesAPowerOfTen() {
        assertEquals(20, AlignmentPreviewPanel.roundDown(49), 1e-9);
        assertEquals(50, AlignmentPreviewPanel.roundDown(50), 1e-9);
        assertEquals(0.1, AlignmentPreviewPanel.roundDown(0.19), 1e-12);
        assertEquals(50, AlignmentPreviewPanel.roundUp(21), 1e-9);
        assertEquals(100, AlignmentPreviewPanel.roundUp(51), 1e-9);
        assertEquals(0.02, AlignmentPreviewPanel.roundUp(0.02), 1e-12);
    }

    @Test
    public void emptyPreviewShouldBePainted() {
        paint(new AlignmentPreviewPanel());
    }

    @Test
    public void previewWithIncompletePointsShouldBePaintedWithoutArrows() {
        AlignmentPreviewPanel panel = new AlignmentPreviewPanel();
        panel.setAlignment(List.of(
                new ReferencePoint(0.0, 0.0, null, null, false),
                new ReferencePoint(80.0, null, 80.3, Double.NaN, false),
                new ReferencePoint(null, null, null, null, false)), null);

        assertTrue(!hasColor(paint(panel), VECTOR_COLOR));
    }

    @Test
    public void previewWithAlignmentShouldBePaintedWithArrows() {
        AlignmentPreviewPanel panel = new AlignmentPreviewPanel();
        panel.setProgramPath(List.of(new Point2D(10, 10), new Point2D(70, 10), new Point2D(70, 82), new Point2D(10, 82), new Point2D(10, 10)));
        panel.setAlignment(List.of(
                new ReferencePoint(0.0, 0.0, 0.31, -0.22, false),
                new ReferencePoint(80.0, 92.0, 79.51, 92.47, false)), new RigidTransform2D(Math.toRadians(0.5), 0.3, -0.2));

        assertTrue(hasColor(paint(panel), VECTOR_COLOR));
    }

    @Test
    public void previewWithManyPointsShouldBePainted() {
        AlignmentPreviewPanel panel = new AlignmentPreviewPanel();
        List<ReferencePoint> points = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            points.add(new ReferencePoint(i * 10.0, i * 5.0, i * 10.0 + 0.1, i * 5.0 - 0.1, false));
        }
        panel.setAlignment(points, new RigidTransform2D(0, 0.1, -0.1));

        paint(panel);
    }
}
