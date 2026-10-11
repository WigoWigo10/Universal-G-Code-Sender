package com.willwinder.ugs.nbp.pcb.gcode;

import com.willwinder.ugs.nbp.pcb.geometry.RigidTransform2D;
import com.willwinder.universalgcodesender.gcode.GcodeParser;
import com.willwinder.universalgcodesender.gcode.GcodeState;
import com.willwinder.universalgcodesender.gcode.util.Code;
import com.willwinder.universalgcodesender.gcode.util.GcodeParserException;
import com.willwinder.universalgcodesender.gcode.util.Plane;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UnitUtils.Units;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class AlignmentProcessorTest {
    private static final RigidTransform2D TRANSLATION = new RigidTransform2D(0, 10, 5);
    private static final RigidTransform2D QUARTER_TURN = new RigidTransform2D(Math.PI / 2, 0, 0);

    private static GcodeState stateAt(double x, double y, double z) {
        GcodeState state = new GcodeState();
        state.currentPoint = new Position(x, y, z, Units.MM);
        return state;
    }

    private static List<String> process(RigidTransform2D transform, String command, GcodeState state) throws GcodeParserException {
        return new AlignmentProcessor(transform, Units.MM).processCommand(command, state);
    }

    private static void assertRejected(String expectedError, String command, GcodeState state) {
        GcodeParserException exception = assertThrows(GcodeParserException.class,
                () -> process(TRANSLATION, command, state));
        assertEquals(expectedError + command, exception.getMessage());
    }

    @Test
    public void identityTransformShouldNotChangeCommand() throws GcodeParserException {
        List<String> commands = process(RigidTransform2D.identity(), "G91 G1 X1 Y2", stateAt(0, 0, 0));

        assertEquals(List.of("G91 G1 X1 Y2"), commands);
    }

    @Test
    public void translationShouldMoveXAndYAndKeepOtherWords() throws GcodeParserException {
        List<String> commands = process(TRANSLATION, "G1 X1 Y2 Z-0.1 F100", stateAt(0, 0, 0));

        assertEquals(List.of("G1 X11 Y7 Z-0.1 F100"), commands);
    }

    @Test
    public void rotationShouldBeCounterClockwise() throws GcodeParserException {
        List<String> commands = process(QUARTER_TURN, "G0 X10 Y0", stateAt(0, 0, 0));

        assertEquals(List.of("G0 X0 Y10"), commands);
    }

    @Test
    public void rotationShouldBeAppliedBeforeTranslation() throws GcodeParserException {
        List<String> commands = process(new RigidTransform2D(Math.PI / 2, 100, 50), "G0 X10 Y0", stateAt(0, 0, 0));

        assertEquals(List.of("G0 X100 Y60"), commands);
    }

    @Test
    public void moveWithOnlyXShouldGetBothCoordinates() throws GcodeParserException {
        List<String> commands = process(QUARTER_TURN, "G1 X10", stateAt(0, 5, 0));

        assertEquals(List.of("G1 X-5Y10"), commands);
    }

    @Test
    public void moveWithoutMotionCodeShouldUseModalState() throws GcodeParserException {
        GcodeState state = stateAt(0, 0, 0);
        state.currentMotionMode = Code.G1;

        List<String> commands = process(TRANSLATION, "X5 Y5", state);

        assertEquals(List.of("X15 Y10"), commands);
    }

    @Test
    public void moveWithOnlyZShouldGetAlignedXYForFollowingProcessors() throws GcodeParserException {
        List<String> commands = process(TRANSLATION, "G1 Z-1 F50", stateAt(3, 4, 0));

        assertEquals(List.of("G1 Z-1 F50X13Y9"), commands);
    }

    @Test
    public void moveWithOnlyZAndUnknownPositionShouldNotChange() throws GcodeParserException {
        List<String> commands = process(TRANSLATION, "G0 Z5", new GcodeState());

        assertEquals(List.of("G0 Z5"), commands);
    }

    @Test
    public void commandWithoutMotionShouldNotChange() throws GcodeParserException {
        assertEquals(List.of("M3 S10000"), process(TRANSLATION, "M3 S10000", stateAt(0, 0, 0)));
        assertEquals(List.of("G21 G90 G17"), process(TRANSLATION, "G21 G90 G17", stateAt(0, 0, 0)));
        assertEquals(List.of("(a comment with X1 Y2)"), process(TRANSLATION, "(a comment with X1 Y2)", stateAt(0, 0, 0)));
    }

    @Test
    public void machineCoordinateMoveInZShouldNotChange() throws GcodeParserException {
        List<String> commands = process(TRANSLATION, "G53 G0 Z0", stateAt(0, 0, 0));

        assertEquals(List.of("G53 G0 Z0"), commands);
    }

    @Test
    public void translationShouldBeConvertedToProgramUnits() throws GcodeParserException {
        GcodeState state = new GcodeState();
        state.units = Code.G20;
        state.isMetric = false;
        state.currentPoint = new Position(0, 0, 0, Units.INCH);

        List<String> commands = process(new RigidTransform2D(0, 25.4, 0), "G0 X1 Y1", state);

        assertEquals(List.of("G0 X2 Y1"), commands);
    }

    @Test
    public void commentShouldNotBeTransformed() throws GcodeParserException {
        List<String> commands = process(TRANSLATION, "G1 X1 Y2 (to X5 Y5)", stateAt(0, 0, 0));

        assertEquals(List.of("G1 X11 Y7"), commands);
    }

    @Test
    public void arcShouldBeExpandedToAlignedLines() throws GcodeParserException {
        // A clockwise quarter circle with radius 10 around the origin
        List<String> commands = process(new RigidTransform2D(0, 100, 0), "G2 X0 Y-10 I-10 J0", stateAt(10, 0, 0));

        assertTrue(commands.size() > 10);
        assertTrue(commands.stream().allMatch(c -> c.startsWith("G1")));
        assertEquals("G1X100Y-10Z0", commands.get(commands.size() - 1));
    }

    @Test
    public void fullCircleWithEndPointShouldBeExpanded() throws GcodeParserException {
        List<String> commands = process(new RigidTransform2D(0, 100, 0), "G2 X10 Y0 I-10", stateAt(10, 0, 0));

        assertTrue(commands.toString(), commands.size() > 10);
        assertEquals("G1X110Y0Z0", commands.get(commands.size() - 1));
    }

    @Test
    public void arcWithoutEndPointShouldBeRejected() {
        // The arc expander in ugs-core does not recognize an arc without axis words
        assertRejected(AlignmentProcessor.ERROR_UNEXPANDED_ARC, "G2 I-10", stateAt(10, 0, 0));
    }

    @Test
    public void relativeModeShouldBeRejected() {
        assertRejected(AlignmentProcessor.ERROR_RELATIVE_MODE, "G91", stateAt(0, 0, 0));

        GcodeState relativeState = stateAt(0, 0, 0);
        relativeState.inAbsoluteMode = false;
        relativeState.distanceMode = Code.G91;
        assertRejected(AlignmentProcessor.ERROR_RELATIVE_MODE, "G1 X1", relativeState);
    }

    @Test
    public void otherPlanesShouldBeRejected() {
        assertRejected(AlignmentProcessor.ERROR_UNSUPPORTED_PLANE, "G18", stateAt(0, 0, 0));

        GcodeState zxState = stateAt(0, 0, 0);
        zxState.plane = Plane.ZX;
        assertRejected(AlignmentProcessor.ERROR_UNSUPPORTED_PLANE, "G1 X1", zxState);
    }

    @Test
    public void machineCoordinateMoveInXYShouldBeRejected() {
        assertRejected(AlignmentProcessor.ERROR_MACHINE_COORDINATES, "G53 G0 X0 Y0", stateAt(0, 0, 0));
    }

    @Test
    public void commandsThatChangeCoordinatesShouldBeRejected() {
        for (String command : List.of("G28", "G28 X0 Y0", "G30", "G92 X0 Y0", "G92.1", "G10 L20 P1 X0 Y0",
                "G38.2 Z-10 F50", "G81 X1 Y1 Z-2 R1 F100", "G51 X2", "G52 X10 Y10", "G68 X0 Y0 R45")) {
            assertRejected(AlignmentProcessor.ERROR_UNSUPPORTED_COMMAND, command, stateAt(0, 0, 0));
        }
    }

    @Test
    public void moveWithUnknownPositionShouldBeRejected() {
        // A fresh state has no known position, as at the start of a file
        assertRejected(AlignmentProcessor.ERROR_UNKNOWN_POSITION, "G0 X10", new GcodeState());
    }
    @Test
    public void programShouldBeAlignedUsingPositionsFromTheOriginalProgram() throws GcodeParserException {
        GcodeParser parser = new GcodeParser();
        parser.addCommandProcessor(new AlignmentProcessor(new RigidTransform2D(Math.PI / 2, 100, 0), Units.MM));

        List<String> result = new ArrayList<>();
        for (String command : List.of("G21 G90", "G0 Z5", "G0 X0 Y0", "G1 Z-0.1 F100", "G1 X10", "G1 Y10", "G0 Z5", "M5")) {
            result.addAll(parser.preprocessCommand(command, parser.getCurrentState()));
            parser.addCommand(command);
        }

        assertEquals(List.of(
                "G21 G90",
                "G0 Z5",
                "G0 X100 Y0",
                "G1 Z-0.1 F100X100Y0",
                "G1 X100Y10",
                "G1 Y10X90",
                "G0 Z5X90Y10",
                "M5"), result);
    }
}
