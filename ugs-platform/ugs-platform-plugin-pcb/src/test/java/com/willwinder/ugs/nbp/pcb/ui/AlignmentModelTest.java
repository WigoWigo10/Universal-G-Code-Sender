package com.willwinder.ugs.nbp.pcb.ui;

import com.willwinder.ugs.nbp.pcb.geometry.AlignmentResult;
import com.willwinder.ugs.nbp.pcb.ui.AlignmentModel.Criteria;
import com.willwinder.ugs.nbp.pcb.ui.AlignmentModel.Evaluation;
import com.willwinder.ugs.nbp.pcb.ui.AlignmentModel.Status;
import com.willwinder.universalgcodesender.gcode.util.Code;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class AlignmentModelTest {
    private final AlignmentModel model = new AlignmentModel();

    private void setPoint(int index, double nominalX, double nominalY, double measuredX, double measuredY) {
        model.setNominal(index, nominalX, nominalY);
        model.setMeasured(index, measuredX, measuredY);
    }

    /**
     * Two holes 80 mm apart that are measured where they are expected, moved by (1, 2)
     */
    private void setTwoHoles() {
        setPoint(0, 0, 0, 1, 2);
        setPoint(1, 80, 0, 81, 2);
    }

    @Test
    public void newModelShouldHaveTwoEmptyPointsAndBeIncomplete() {
        assertEquals(2, model.getPoints().size());
        assertNull(model.getPoints().get(0).nominalX());
        assertEquals(Status.INCOMPLETE, model.evaluate().status());
        assertTrue(model.evaluate().result().isEmpty());
        assertEquals(Code.G54, model.getWorkCoordinateSystem());
    }

    @Test
    public void completePointsShouldGiveAlignmentFromTheSolver() {
        setTwoHoles();

        Evaluation evaluation = model.evaluate();

        assertEquals(Status.VALID, evaluation.status());
        AlignmentResult result = evaluation.result().orElseThrow();
        assertEquals(0, result.transform().rotation(), 1e-9);
        assertEquals(1, result.transform().translationX(), 1e-9);
        assertEquals(2, result.transform().translationY(), 1e-9);
        assertEquals(0, result.maxResidual(), 1e-9);
    }

    @Test
    public void pointWithMissingCoordinateShouldBeIncomplete() {
        setTwoHoles();

        model.setMeasured(1, 81.0, null);

        assertEquals(Status.INCOMPLETE, model.evaluate().status());
    }

    @Test
    public void coordinateThatIsNotANumberShouldBeInvalid() {
        setTwoHoles();

        model.setNominal(0, Double.NaN, 0.0);

        assertEquals(Status.INVALID_COORDINATES, model.evaluate().status());
        assertFalse(model.evaluate().isValid());
    }

    @Test
    public void pointsInTheSamePositionShouldBeCoincident() {
        setPoint(0, 10, 10, 11, 12);
        setPoint(1, 10, 10, 11, 12);

        assertEquals(Status.COINCIDENT_POINTS, model.evaluate().status());
    }

    @Test
    public void pointsThatAreAlmostInTheSamePositionShouldBeTooClose() {
        setPoint(0, 0, 0, 1, 2);
        setPoint(1, 2, 0, 3, 2);

        Evaluation evaluation = model.evaluate();

        assertEquals(Status.POINTS_TOO_CLOSE, evaluation.status());
        assertTrue(evaluation.result().isPresent());
    }

    @Test
    public void measuredDistanceThatDoesNotMatchTheNominalShouldBeAMismatch() {
        setPoint(0, 0, 0, 0, 0);
        setPoint(1, 80, 0, 80.5, 0);

        Evaluation evaluation = model.evaluate();

        assertEquals(Status.DISTANCE_MISMATCH, evaluation.status());
        assertEquals(0.00625, evaluation.result().orElseThrow().scaleError(), 1e-9);
    }

    @Test
    public void pointThatDoesNotFitTheOthersShouldGiveResidualThatIsTooLarge() {
        // The third hole is measured 0.3 mm from where the other two put it, without changing the size much
        setPoint(0, 0, 0, 0, 0);
        setPoint(1, 80, 0, 80, 0);
        model.addPoint();
        setPoint(2, 40, 60, 40.3, 60);
        model.setCriteria(new Criteria(0.05, 0.01, 10));

        Evaluation evaluation = model.evaluate();

        assertEquals(Status.RESIDUAL_TOO_LARGE, evaluation.status());
        assertTrue(evaluation.result().orElseThrow().maxResidual() > 0.05);
    }

    @Test
    public void alignmentShouldBeValidOrNotDependingOnTheCriteria() {
        setPoint(0, 0, 0, 0, 0);
        setPoint(1, 80, 0, 80.08, 0);

        model.setCriteria(new Criteria(0.05, 0.002, 10));
        assertEquals(Status.VALID, model.evaluate().status());

        model.setCriteria(new Criteria(0.05, 0.0005, 10));
        assertEquals(Status.DISTANCE_MISMATCH, model.evaluate().status());

        model.setCriteria(new Criteria(0.01, 0.002, 10));
        assertEquals(Status.RESIDUAL_TOO_LARGE, model.evaluate().status());

        model.setCriteria(new Criteria(0.05, 0.002, 100));
        assertEquals(Status.POINTS_TOO_CLOSE, model.evaluate().status());
    }

    @Test
    public void criteriaThatAreNotValidShouldNotAcceptAnything() {
        setTwoHoles();

        model.setCriteria(new Criteria(Double.NaN, 0.002, 10));
        assertEquals(Status.INVALID_CRITERIA, model.evaluate().status());

        model.setCriteria(new Criteria(0.05, -1, 10));
        assertEquals(Status.INVALID_CRITERIA, model.evaluate().status());
    }

    @Test
    public void pointsShouldBeAddedAndRemovedDownToTwo() {
        model.addPoint();
        model.addPoint();
        assertEquals(4, model.getPoints().size());

        model.removePoint(1);
        model.removePoint(0);
        assertEquals(2, model.getPoints().size());

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> model.removePoint(0));
        assertEquals(AlignmentModel.ERROR_MIN_POINTS, exception.getMessage());
        assertEquals(2, model.getPoints().size());
    }

    @Test
    public void removingPointShouldKeepTheOthers() {
        setTwoHoles();
        model.addPoint();
        setPoint(2, 80, 92, 81, 94);

        model.removePoint(0);

        assertEquals(80, model.getPoints().get(0).nominalX(), 0);
        assertEquals(92, model.getPoints().get(1).nominalY(), 0);
        assertEquals(Status.VALID, model.evaluate().status());
    }

    @Test
    public void capturedPositionShouldSetWorkCoordinateSystem() {
        model.setCaptured(0, 1, 2, Code.G55);

        assertEquals(Code.G55, model.getWorkCoordinateSystem());
        assertEquals(1, model.getPoints().get(0).measuredX(), 0);
        assertEquals(2, model.getPoints().get(0).measuredY(), 0);
        assertTrue(model.getPoints().get(0).measuredOnMachine());
    }

    @Test
    public void capturedPositionInAnotherWorkCoordinateSystemShouldBeRejected() {
        model.setCaptured(0, 1, 2, Code.G54);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> model.setCaptured(1, 81, 2, Code.G55));

        assertTrue(exception.getMessage(), exception.getMessage().startsWith(AlignmentModel.ERROR_MIXED_WORK_COORDINATE_SYSTEMS));
        assertNull(model.getPoints().get(1).measuredX());
        assertEquals(Code.G54, model.getWorkCoordinateSystem());
    }

    @Test
    public void capturedPositionInAnotherWorkCoordinateSystemThanPositionEnteredByHandShouldBeRejected() {
        model.setMeasured(0, 1.0, 2.0);

        assertThrows(IllegalArgumentException.class, () -> model.setCaptured(1, 81, 2, Code.G55));
    }

    @Test
    public void capturingTheOnlyMeasuredPointAgainShouldChangeWorkCoordinateSystem() {
        model.setCaptured(0, 1, 2, Code.G54);

        model.setCaptured(0, 5, 6, Code.G55);

        assertEquals(Code.G55, model.getWorkCoordinateSystem());
        assertEquals(5, model.getPoints().get(0).measuredX(), 0);
    }

    @Test
    public void capturedPositionShouldBeAcceptedInAnotherWorkCoordinateSystemAfterMeasuredAreCleared() {
        setTwoHoles();
        model.setCaptured(0, 1, 2, Code.G54);

        model.clearMeasured();
        model.setCaptured(1, 81, 2, Code.G56);

        assertEquals(Code.G56, model.getWorkCoordinateSystem());
        assertNull(model.getPoints().get(0).measuredX());
        assertEquals(0, model.getPoints().get(0).nominalX(), 0);
    }

    @Test
    public void capturedPositionThatIsNotKnownShouldBeRejected() {
        assertThrows(IllegalArgumentException.class, () -> model.setCaptured(0, Double.NaN, 2, Code.G54));
        assertThrows(IllegalArgumentException.class, () -> model.setCaptured(0, 1, Double.POSITIVE_INFINITY, Code.G54));
        assertThrows(IllegalArgumentException.class, () -> model.setCaptured(0, 1, 2, null));
        assertNull(model.getPoints().get(0).measuredX());
    }

    @Test
    public void workCoordinateSystemShouldBeChangedByHandWhenNothingWasCaptured() {
        model.setMeasured(0, 1.0, 2.0);

        model.setWorkCoordinateSystem(Code.G57);

        assertEquals(Code.G57, model.getWorkCoordinateSystem());
    }

    @Test
    public void workCoordinateSystemShouldNotBeChangedByHandWhilePositionsWereCaptured() {
        model.setCaptured(0, 1, 2, Code.G54);

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> model.setWorkCoordinateSystem(Code.G55));

        assertEquals(AlignmentModel.ERROR_WORK_COORDINATE_SYSTEM_IN_USE, exception.getMessage());
        assertEquals(Code.G54, model.getWorkCoordinateSystem());
    }

    @Test
    public void workCoordinateSystemShouldOnlyBeAWorkCoordinateSystem() {
        assertThrows(IllegalArgumentException.class, () -> model.setWorkCoordinateSystem(Code.G0));
    }

    @Test
    public void editingMeasuredPositionByHandShouldNoLongerCountAsCaptured() {
        model.setCaptured(0, 1, 2, Code.G54);

        model.setMeasured(0, 1.5, 2.0);
        model.setWorkCoordinateSystem(Code.G55);

        assertFalse(model.getPoints().get(0).measuredOnMachine());
        assertEquals(Code.G55, model.getWorkCoordinateSystem());
    }

    @Test
    public void listenersShouldBeToldAboutEveryChange() {
        List<String> changes = new ArrayList<>();
        model.addListener(() -> changes.add("changed"));

        model.setNominal(0, 1.0, 2.0);
        model.setMeasured(0, 1.0, 2.0);
        model.setCaptured(1, 1, 2, Code.G54);
        model.addPoint();
        model.removePoint(2);
        model.clearMeasured();
        model.setCriteria(new Criteria(0.1, 0.01, 5));
        model.setWorkCoordinateSystem(Code.G55);

        assertEquals(8, changes.size());
    }
}
