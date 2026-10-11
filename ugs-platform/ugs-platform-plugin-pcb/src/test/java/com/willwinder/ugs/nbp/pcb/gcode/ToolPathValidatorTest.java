package com.willwinder.ugs.nbp.pcb.gcode;

import com.willwinder.universalgcodesender.model.Position;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import java.util.List;

public class ToolPathValidatorTest {
    private static final double TOLERANCE = 0.005;

    private static final List<String> PROGRAM = List.of(
            "G21 G90",
            "G0 Z5",
            "G0 X0 Y0",
            "G1 Z-0.1 F50",
            "G1 X10",
            "G1 Y10",
            "G0 Z5");

    private static void validate(List<String> processed, double maxZChange) throws Exception {
        ToolPathValidator.validate(ToolPathValidator.trace(PROGRAM), ToolPathValidator.trace(processed), TOLERANCE, maxZChange);
    }

    private static void assertInvalid(String expectedError, List<String> processed, double maxZChange) {
        AlignmentException exception = assertThrows(AlignmentException.class, () -> validate(processed, maxZChange));
        assertTrue(exception.getMessage(), exception.getMessage().startsWith(expectedError));
    }

    @Test
    public void traceShouldReturnKnownPositionsInMillimeters() throws Exception {
        List<Position> positions = ToolPathValidator.trace(List.of("G20 G90", "G0 Z1", "G0 X1 Y2", "M3 S1000", "G1 X2"));

        assertEquals(2, positions.size());
        assertEquals(25.4, positions.get(0).getX(), 1e-9);
        assertEquals(50.8, positions.get(0).getY(), 1e-9);
        assertEquals(25.4, positions.get(0).getZ(), 1e-9);
        assertEquals(50.8, positions.get(1).getX(), 1e-9);
    }

    @Test
    public void sameProgramShouldBeValid() throws Exception {
        validate(PROGRAM, 0);
    }

    @Test
    public void programWithSplitMovesShouldBeValid() throws Exception {
        validate(List.of("G21 G90", "G0 Z5", "G0 X0 Y0", "G1 Z2.5", "G1 Z-0.1", "G1 X2.5", "G1 X5", "G1 X7.5", "G1 X10",
                "G1 Y5", "G1 Y10", "G0 Z5"), 0);
    }

    @Test
    public void programWithZChangedWithinLimitShouldBeValid() throws Exception {
        validate(List.of("G21 G90", "G0 Z5", "G0 X0 Y0 Z5.05", "G1 Z-0.05", "G1 X5 Z0", "G1 X10 Z0.05", "G1 Y10 Z0.1", "G0 Z5.2"), 0.2);
    }

    @Test
    public void programWithZChangedMoreThanLimitShouldBeInvalid() {
        assertInvalid(ToolPathValidator.ERROR_OFF_PATH,
                List.of("G21 G90", "G0 Z5", "G0 X0 Y0", "G1 Z-0.1", "G1 X10 Z0.3", "G1 Y10", "G0 Z5"), 0.2);
    }

    @Test
    public void programWithChangedZShouldBeInvalidWhenZMustNotChange() {
        assertInvalid(ToolPathValidator.ERROR_OFF_PATH,
                List.of("G21 G90", "G0 Z5", "G0 X0 Y0", "G1 Z-0.1", "G1 X10 Z-0.05", "G1 Y10", "G0 Z5"), 0);
    }

    @Test
    public void partlyProcessedProgramShouldBeInvalid() {
        assertInvalid(ToolPathValidator.ERROR_INCOMPLETE,
                List.of("G21 G90", "G0 Z5", "G0 X0 Y0", "G1 Z-0.1", "G1 X10"), 0);
        assertInvalid(ToolPathValidator.ERROR_INCOMPLETE,
                List.of("G21 G90", "G0 Z5", "G0 X0 Y0", "G1 Z-0.1", "G1 X10", "G1 Y10"), 0);
    }

    @Test
    public void programThatLeavesThePathShouldBeInvalid() {
        assertInvalid(ToolPathValidator.ERROR_OFF_PATH,
                List.of("G21 G90", "G0 Z5", "G0 X0 Y0", "G1 Z-0.1", "G1 X5 Y0.1", "G1 X10", "G1 Y10", "G0 Z5"), 0);
    }

    @Test
    public void programThatStartsMovesFromAnotherPositionShouldBeInvalid() {
        // What the line splitter makes of a move when it is given the wrong start position
        assertInvalid(ToolPathValidator.ERROR_OFF_PATH,
                List.of("G21 G90", "G0 Z5", "G0 X0 Y0", "G1 Z-0.1", "G1 X-5", "G1 X10", "G1 Y10", "G0 Z5"), 0);
    }

    @Test
    public void programThatSkipsAPositionShouldBeInvalid() {
        assertInvalid(ToolPathValidator.ERROR_OFF_PATH,
                List.of("G21 G90", "G0 Z5", "G0 X0 Y0", "G1 Z-0.1", "G1 X10 Y10", "G0 Z5"), 0);
    }

    @Test
    public void programThatMovesInAnotherOrderShouldBeInvalid() {
        assertInvalid(ToolPathValidator.ERROR_OFF_PATH,
                List.of("G21 G90", "G0 Z5", "G0 X0 Y0", "G1 Z-0.1", "G1 Y10", "G1 X10", "G0 Z5"), 0);
    }

    @Test
    public void programWithMovesShorterThanToleranceThatReturnToTheSamePositionShouldBeValid() throws Exception {
        // From a copper clearing program, the positions are closer to each other than the tolerance
        List<String> program = List.of("G21 G90", "G0 Z2", "G0 X12.3832 Y61.2343", "G1 Z-0.1", "G1 X12.3822 Y61.2316",
                "G1 X12.3792 Y61.2288", "G1 X12.3832 Y61.2343", "G1 X13.0042 Y61.985", "G0 Z2");

        ToolPathValidator.validate(ToolPathValidator.trace(program), ToolPathValidator.trace(program), TOLERANCE, 0);
    }

    @Test
    public void programWithMovesShorterThanToleranceShouldStillBeInvalidWhenIncomplete() throws Exception {
        List<String> program = List.of("G21 G90", "G0 Z2", "G0 X12.3832 Y61.2343", "G1 Z-0.1", "G1 X12.3822 Y61.2316",
                "G1 X12.3792 Y61.2288", "G1 X12.3832 Y61.2343", "G1 X13.0042 Y61.985", "G0 Z2");
        List<Position> expected = ToolPathValidator.trace(program);

        AlignmentException exception = assertThrows(AlignmentException.class,
                () -> ToolPathValidator.validate(expected, ToolPathValidator.trace(program.subList(0, 7)), TOLERANCE, 0));

        assertTrue(exception.getMessage(), exception.getMessage().startsWith(ToolPathValidator.ERROR_INCOMPLETE));
    }

    @Test
    public void programWithoutMovesShouldBeInvalid() {
        assertInvalid(ToolPathValidator.ERROR_NO_MOVES, List.of("G21 G90", "M5"), 0);
    }
}
