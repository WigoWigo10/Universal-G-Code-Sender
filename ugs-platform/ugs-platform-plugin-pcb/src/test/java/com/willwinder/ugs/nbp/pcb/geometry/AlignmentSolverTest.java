package com.willwinder.ugs.nbp.pcb.geometry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import java.util.List;

public class AlignmentSolverTest {
    private static final double DELTA = 1e-9;

    private static final List<Point2D> TWO_HOLES = List.of(new Point2D(0, 0), new Point2D(100, 0));
    private static final List<Point2D> FOUR_HOLES = List.of(
            new Point2D(5, 5), new Point2D(95, 5), new Point2D(95, 75), new Point2D(5, 75));

    private static List<Point2D> transform(List<Point2D> points, RigidTransform2D transform) {
        return points.stream().map(transform::apply).toList();
    }

    private static void assertTransform(RigidTransform2D expected, RigidTransform2D actual) {
        assertEquals(expected.rotation(), actual.rotation(), DELTA);
        assertEquals(expected.translationX(), actual.translationX(), DELTA);
        assertEquals(expected.translationY(), actual.translationY(), DELTA);
    }

    @Test
    public void solveWithIdenticalPointsShouldGiveIdentity() {
        AlignmentResult result = AlignmentSolver.solve(TWO_HOLES, TWO_HOLES);

        assertTransform(RigidTransform2D.identity(), result.transform());
        assertEquals(0, result.maxResidual(), DELTA);
        assertEquals(0, result.scaleError(), DELTA);
    }

    @Test
    public void solveWithTranslatedPointsShouldGiveTranslation() {
        RigidTransform2D expected = new RigidTransform2D(0, 12.5, -3.25);

        AlignmentResult result = AlignmentSolver.solve(TWO_HOLES, transform(TWO_HOLES, expected));

        assertTransform(expected, result.transform());
        assertEquals(0, result.maxResidual(), DELTA);
    }

    @Test
    public void solveWithRotatedPointsShouldGiveCounterClockwiseRotation() {
        List<Point2D> measured = List.of(new Point2D(0, 0), new Point2D(0, 100));

        AlignmentResult result = AlignmentSolver.solve(TWO_HOLES, measured);

        assertTransform(new RigidTransform2D(Math.PI / 2, 0, 0), result.transform());
    }

    @Test
    public void solveWithRotatedAndTranslatedPointsShouldRecoverTransform() {
        RigidTransform2D expected = new RigidTransform2D(Math.toRadians(-1.7), 42, 17);

        AlignmentResult result = AlignmentSolver.solve(FOUR_HOLES, transform(FOUR_HOLES, expected));

        assertTransform(expected, result.transform());
        assertEquals(0, result.maxResidual(), DELTA);
        assertEquals(0, result.rmsResidual(), DELTA);
        assertEquals(0, result.scaleError(), DELTA);
    }

    @Test
    public void solveWithPointsNotAroundOriginShouldRecoverTransform() {
        List<Point2D> nominal = List.of(new Point2D(210, 150), new Point2D(290, 180));
        RigidTransform2D expected = new RigidTransform2D(Math.toRadians(3), -5, 8);

        AlignmentResult result = AlignmentSolver.solve(nominal, transform(nominal, expected));

        assertTransform(expected, result.transform());
    }

    @Test
    public void solveWithHalfTurnShouldRecoverTransform() {
        RigidTransform2D expected = new RigidTransform2D(Math.PI, 100, 80);

        AlignmentResult result = AlignmentSolver.solve(FOUR_HOLES, transform(FOUR_HOLES, expected));

        assertEquals(Math.PI, Math.abs(result.transform().rotation()), DELTA);
        assertEquals(0, result.maxResidual(), DELTA);
    }

    @Test
    public void solveWithTwoPointsAtWrongDistanceShouldSplitTheErrorAndReportScale() {
        List<Point2D> measured = List.of(new Point2D(0, 0), new Point2D(101, 0));

        AlignmentResult result = AlignmentSolver.solve(TWO_HOLES, measured);

        assertTransform(new RigidTransform2D(0, 0.5, 0), result.transform());
        assertEquals(List.of(0.5, 0.5), result.residuals());
        assertEquals(0.5, result.maxResidual(), DELTA);
        assertEquals(0.01, result.scaleError(), DELTA);
    }

    @Test
    public void solveWithOneOutlierShouldReportItAsLargestResidual() {
        List<Point2D> measured = List.of(
                new Point2D(5, 5), new Point2D(95, 5), new Point2D(95, 75), new Point2D(5.4, 75.3));

        AlignmentResult result = AlignmentSolver.solve(FOUR_HOLES, measured);

        assertEquals(result.maxResidual(), result.residuals().get(3), DELTA);
        // The fit spreads the 0.5 error of the outlier over all points
        assertTrue(result.maxResidual() > 0);
        assertTrue(result.maxResidual() < 0.5);
        assertTrue(result.rmsResidual() > 0);
        assertTrue(result.rmsResidual() < result.maxResidual());
    }

    @Test
    public void solveWithMirroredPointsShouldNotHideTheMirroring() {
        List<Point2D> mirrored = FOUR_HOLES.stream().map(p -> new Point2D(-p.x(), p.y())).toList();

        AlignmentResult result = AlignmentSolver.solve(FOUR_HOLES, mirrored);

        assertTrue(result.maxResidual() > 10);
    }

    @Test
    public void solveWithDifferentNumberOfPointsShouldThrow() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> AlignmentSolver.solve(TWO_HOLES, FOUR_HOLES));

        assertEquals(AlignmentSolver.ERROR_POINT_COUNT_MISMATCH, exception.getMessage());
    }

    @Test
    public void solveWithOnePointShouldThrow() {
        List<Point2D> onePoint = List.of(new Point2D(1, 1));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> AlignmentSolver.solve(onePoint, onePoint));

        assertEquals(AlignmentSolver.ERROR_NOT_ENOUGH_POINTS, exception.getMessage());
    }

    @Test
    public void solveWithCoincidentNominalPointsShouldThrow() {
        List<Point2D> coincident = List.of(new Point2D(10, 10), new Point2D(10, 10));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> AlignmentSolver.solve(coincident, TWO_HOLES));

        assertEquals(AlignmentSolver.ERROR_COINCIDENT_POINTS, exception.getMessage());
    }

    @Test
    public void solveWithCoincidentMeasuredPointsShouldThrow() {
        List<Point2D> coincident = List.of(new Point2D(10, 10), new Point2D(10, 10));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> AlignmentSolver.solve(TWO_HOLES, coincident));

        assertEquals(AlignmentSolver.ERROR_COINCIDENT_POINTS, exception.getMessage());
    }

    @Test
    public void solveWithNonFinitePointShouldThrow() {
        List<Point2D> invalid = List.of(new Point2D(0, 0), new Point2D(Double.NaN, 0));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> AlignmentSolver.solve(TWO_HOLES, invalid));

        assertEquals(AlignmentSolver.ERROR_INVALID_POINT, exception.getMessage());
    }
}
