package com.willwinder.universalgcodesender.gcode.processors;

import com.willwinder.universalgcodesender.gcode.GcodeState;
import com.willwinder.universalgcodesender.gcode.util.Code;
import com.willwinder.universalgcodesender.gcode.util.GcodeParserException;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UnitUtils;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

import java.util.List;

/**
 * Characterization tests that pin down the current behavior of the existing transform
 * processors. They document what the code does today, not what it should do, and exist
 * to make the constraints for the PCB alignment processor explicit.
 */
public class TransformProcessorCharacterizationTest {
    private static final Position ORIGIN = new Position(0, 0, 0, UnitUtils.Units.MM);

    private static GcodeState relativeStateAt(double x, double y, double z) {
        GcodeState state = new GcodeState();
        state.inAbsoluteMode = false;
        state.distanceMode = Code.G91;
        state.currentPoint = new Position(x, y, z, UnitUtils.Units.MM);
        return state;
    }

    @Test
    public void positionRotateWithPositiveAngleRotatesClockwise() {
        Position rotated = new Position(1, 0, 0, UnitUtils.Units.MM).rotate(ORIGIN, Math.PI / 2);

        // The mathematical (counter-clockwise) convention would give (0, 1)
        assertEquals(0, rotated.getX(), 1e-9);
        assertEquals(-1, rotated.getY(), 1e-9);
    }

    @Test
    public void rotateInAbsoluteModeRotatesClockwise() throws GcodeParserException {
        RotateProcessor rotateProcessor = new RotateProcessor(ORIGIN, Math.PI / 2);
        GcodeState state = new GcodeState();
        state.currentPoint = new Position(0, 0, 0, UnitUtils.Units.MM);

        List<String> commands = rotateProcessor.processCommand("G1 X10 Y0", state);

        assertEquals(1, commands.size());
        assertEquals("F0.0S0.0G1X0Y-10Z0", commands.get(0));
    }

    @Test
    public void rotateInRelativeModeUsesUnrotatedStartPoint() throws GcodeParserException {
        RotateProcessor rotateProcessor = new RotateProcessor(ORIGIN, Math.PI / 2);
        GcodeState state = relativeStateAt(10, 0, 0);

        List<String> commands = rotateProcessor.processCommand("G1 X1", state);

        // A correct relative rotation of the (1, 0) delta would be X0Y-1. The delta is
        // instead computed from the rotated end point (0, -11) minus the original start (10, 0).
        assertEquals(1, commands.size());
        assertEquals("F0.0S0.0G1X-10Y-11Z0", commands.get(0));
    }

    @Test
    public void translateInRelativeModeAddsOffsetToEveryMove() throws GcodeParserException {
        TranslateProcessor translateProcessor = new TranslateProcessor(new Position(5, 5, 0, UnitUtils.Units.MM));
        GcodeState state = relativeStateAt(0, 0, 0);

        List<String> commands = translateProcessor.processCommand("G1 X1", state);

        // A relative move should be unaffected by a translation (X1Y0). The offset is
        // instead added to the delta, so it accumulates on every relative move.
        assertEquals(1, commands.size());
        assertEquals("F0.0S0.0G1X6Y5Z0", commands.get(0));
    }

    @Test
    public void rotateWithUnknownYDropsTheXCoordinate() throws GcodeParserException {
        RotateProcessor rotateProcessor = new RotateProcessor(ORIGIN, Math.PI / 2);

        // A fresh state has NaN in all axes, as at the start of a file
        List<String> commands = rotateProcessor.processCommand("G0 X10", new GcodeState());

        // Rotating (10, NaN) gives NaN in both axes, so the move loses its coordinates
        assertEquals(1, commands.size());
        assertEquals("F0.0S0.0G0", commands.get(0));
    }

    @Test
    public void translateWithUnknownYKeepsTheXCoordinate() throws GcodeParserException {
        TranslateProcessor translateProcessor = new TranslateProcessor(new Position(5, 5, 0, UnitUtils.Units.MM));

        List<String> commands = translateProcessor.processCommand("G0 X10", new GcodeState());

        assertEquals(1, commands.size());
        assertEquals("F0.0S0.0G0X15", commands.get(0));
    }

    @Test
    public void rotateOfZOnlyMoveEmitsAllAxes() throws GcodeParserException {
        RotateProcessor rotateProcessor = new RotateProcessor(ORIGIN, Math.PI / 2);
        GcodeState state = new GcodeState();
        state.currentPoint = new Position(10, 0, 5, UnitUtils.Units.MM);

        List<String> commands = rotateProcessor.processCommand("G1 Z-1", state);

        assertEquals(1, commands.size());
        assertEquals("F0.0S0.0G1X0Y-10Z-1", commands.get(0));
    }
}
