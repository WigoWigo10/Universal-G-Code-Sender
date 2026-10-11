package com.willwinder.ugs.nbp.pcb.gcode;

import com.willwinder.ugs.nbp.pcb.geometry.Point2D;
import com.willwinder.ugs.nbp.pcb.geometry.RigidTransform2D;
import com.willwinder.universalgcodesender.gcode.GcodeParser;
import com.willwinder.universalgcodesender.gcode.GcodeState;
import com.willwinder.universalgcodesender.gcode.processors.ArcExpander;
import com.willwinder.universalgcodesender.gcode.processors.CommandProcessor;
import com.willwinder.universalgcodesender.gcode.processors.CommandProcessorList;
import com.willwinder.universalgcodesender.gcode.processors.LineSplitter;
import com.willwinder.universalgcodesender.gcode.processors.MeshLeveler;
import com.willwinder.universalgcodesender.gcode.processors.TranslateProcessor;
import com.willwinder.universalgcodesender.gcode.util.GcodeParserException;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UnitUtils.Units;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.Ignore;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleBinaryOperator;

/**
 * Tests the alignment processor together with the processors used by the auto leveler, in the
 * same way as they are combined by the backend. The expected tool paths are calculated from the
 * original program, the transform and a synthetic surface without using the processors.
 */
public class AlignmentPipelineIntegrationTest {
    private static final String KNOWN_ISSUE_STATE = "Fails: CommandProcessorList gives every processor the position of the original program, "
            + "so a processor after one that moves the program starts its moves from the position before it was moved";
    private static final String KNOWN_ISSUE_INCHES = "Fails: LineSplitter compares its length in millimeters to distances in the units of the program";
    private static final String KNOWN_ISSUE_WORK_COORDINATES = "Fails: AlignmentProcessor does not reject programs that change work coordinate system";

    private static final double TOLERANCE = 0.001;
    private static final double RESOLUTION = 10;
    private static final double MAX_SEGMENT_LENGTH = RESOLUTION / 4;

    private static final RigidTransform2D TRANSLATION = new RigidTransform2D(0, 10, 5);
    private static final RigidTransform2D ROTATION = new RigidTransform2D(Math.toRadians(30), 0, 0);
    private static final RigidTransform2D ROTATION_AND_TRANSLATION = new RigidTransform2D(Math.toRadians(30), 10, 5);

    private static final DoubleBinaryOperator FLAT = (x, y) -> 0;
    private static final DoubleBinaryOperator SURFACE = AlignmentPipelineIntegrationTest::surfaceHeight;

    private static final List<String> PROGRAM = List.of(
            "G21 G90 G17",
            "G0 Z5",
            "G0 X5 Y5",
            "G1 Z-0.1 F50",
            "G1 X25 F100",
            "G1 Y20",
            "G1 X10 Y10",
            "G0 Z5",
            "G0 X30 Y30",
            "G1 Z-0.1 F50",
            "G0 Z5",
            "M5");

    private record Vertex(double x, double y, double z) {
    }

    /**
     * The tool path of {@link #PROGRAM} from its first fully known position
     */
    private static List<Vertex> programPath(RigidTransform2D transform) {
        return List.of(
                vertex(transform, 5, 5, 5),
                vertex(transform, 5, 5, -0.1),
                vertex(transform, 25, 5, -0.1),
                vertex(transform, 25, 20, -0.1),
                vertex(transform, 10, 10, -0.1),
                vertex(transform, 10, 10, 5),
                vertex(transform, 30, 30, 5),
                vertex(transform, 30, 30, -0.1),
                vertex(transform, 30, 30, 5));
    }

    private static Vertex vertex(RigidTransform2D transform, double x, double y, double z) {
        Point2D aligned = transform.apply(new Point2D(x, y));
        return new Vertex(aligned.x(), aligned.y(), z);
    }

    /**
     * A tilted plane, which the bilinear interpolation of the mesh leveler reproduces exactly
     */
    private static double surfaceHeight(double x, double y) {
        return 0.002 * x + 0.003 * y + 0.05;
    }

    private static Position[][] createMesh() {
        int points = 13;
        Position[][] mesh = new Position[points][points];
        for (int xi = 0; xi < points; xi++) {
            for (int yi = 0; yi < points; yi++) {
                double x = -60 + xi * RESOLUTION;
                double y = -60 + yi * RESOLUTION;
                mesh[xi][yi] = new Position(x, y, surfaceHeight(x, y), Units.MM);
            }
        }
        return mesh;
    }

    /**
     * The same processors in the same order as the auto leveler applies to the backend
     */
    private static CommandProcessorList createLevelerProcessors() {
        CommandProcessorList result = new CommandProcessorList();
        result.add(new ArcExpander(true, 0.1));
        result.add(new LineSplitter(MAX_SEGMENT_LENGTH));
        result.add(new MeshLeveler(0, createMesh()));
        return result;
    }

    /**
     * Processes a program the same way as the backend does when it loads a file
     */
    private static List<String> process(List<String> program, CommandProcessor... processors) throws GcodeParserException {
        GcodeParser parser = new GcodeParser();
        for (CommandProcessor processor : processors) {
            parser.addCommandProcessor(processor);
        }

        List<String> result = new ArrayList<>();
        for (String command : program) {
            result.addAll(parser.preprocessCommand(command, parser.getCurrentState()));
            parser.addCommand(command);
        }
        return result;
    }

    /**
     * Processes one command with processors chained in a list, given the state of the original program
     */
    private static List<String> processCommand(String command, Vertex originalStart, CommandProcessor... processors) throws GcodeParserException {
        CommandProcessorList list = new CommandProcessorList();
        for (CommandProcessor processor : processors) {
            list.add(processor);
        }

        GcodeState state = new GcodeState();
        state.currentPoint = new Position(originalStart.x(), originalStart.y(), originalStart.z(), Units.MM);
        return list.processCommand(command, state);
    }

    /**
     * Returns every fully known position in millimeters that the program moves to
     */
    private static List<Position> trace(List<String> program) throws GcodeParserException {
        GcodeParser parser = new GcodeParser();
        List<Position> result = new ArrayList<>();
        for (String command : program) {
            parser.addCommand(command);
            Position position = parser.getCurrentState().currentPoint.getPositionIn(Units.MM);
            if (Double.isFinite(position.getX()) && Double.isFinite(position.getY()) && Double.isFinite(position.getZ())) {
                result.add(position);
            }
        }
        return result;
    }

    private static List<Position> traceFrom(Vertex start, List<String> commands) throws GcodeParserException {
        List<String> program = new ArrayList<>();
        program.add(String.format(Locale.ROOT, "G0 X%.6f Y%.6f Z%.6f", start.x(), start.y(), start.z()));
        program.addAll(commands);
        return trace(program);
    }

    /**
     * Asserts that all positions follow the path in order, that no position is outside of it and that
     * it ends in the last vertex. The Z of the path is relative to the surface.
     */
    private static void assertOnPath(List<Vertex> path, List<Position> positions, DoubleBinaryOperator surface) {
        assertFalse("No positions to check", positions.isEmpty());

        int segment = 0;
        for (int i = 0; i < positions.size(); i++) {
            Position position = positions.get(i);
            int match = findSegment(path, segment, position, surface);
            if (match < 0) {
                fail(String.format(Locale.ROOT, "Position %d of %d (%.4f, %.4f, %.4f) is not on the expected path at or after segment %d of %s",
                        i, positions.size(), position.getX(), position.getY(), position.getZ(), segment, path));
            }
            segment = match;
        }

        Vertex end = path.get(path.size() - 1);
        Position last = positions.get(positions.size() - 1);
        assertEquals(end.x(), last.getX(), TOLERANCE);
        assertEquals(end.y(), last.getY(), TOLERANCE);
        assertEquals(end.z() + surface.applyAsDouble(end.x(), end.y()), last.getZ(), TOLERANCE);
    }

    private static int findSegment(List<Vertex> path, int from, Position position, DoubleBinaryOperator surface) {
        double z = position.getZ() - surface.applyAsDouble(position.getX(), position.getY());
        for (int i = from; i < path.size() - 1; i++) {
            Vertex a = path.get(i);
            Vertex b = path.get(i + 1);
            double dx = b.x() - a.x();
            double dy = b.y() - a.y();
            double dz = b.z() - a.z();
            double lengthSquared = dx * dx + dy * dy + dz * dz;
            double t = lengthSquared == 0 ? 0 :
                    ((position.getX() - a.x()) * dx + (position.getY() - a.y()) * dy + (z - a.z()) * dz) / lengthSquared;
            t = Math.max(0, Math.min(1, t));

            double distance = Math.sqrt(
                    Math.pow(position.getX() - (a.x() + t * dx), 2) +
                            Math.pow(position.getY() - (a.y() + t * dy), 2) +
                            Math.pow(z - (a.z() + t * dz), 2));
            if (distance <= TOLERANCE) {
                return i;
            }
        }
        return -1;
    }

    private static void assertOnArc(Vertex center, double radius, List<Position> positions) {
        assertFalse("No positions to check", positions.isEmpty());
        for (int i = 0; i < positions.size(); i++) {
            Position position = positions.get(i);
            double distance = Math.hypot(position.getX() - center.x(), position.getY() - center.y());
            if (Math.abs(distance - radius) > 0.01 || Math.abs(position.getZ() - center.z()) > TOLERANCE) {
                fail(String.format(Locale.ROOT, "Position %d of %d (%.4f, %.4f, %.4f) is not on the arc with radius %.1f around %s",
                        i, positions.size(), position.getX(), position.getY(), position.getZ(), radius, center));
            }
        }
    }

    private static void assertLongestMove(double maxLength, List<Position> positions) {
        for (int i = 1; i < positions.size(); i++) {
            double length = positions.get(i - 1).distanceXYZ(positions.get(i));
            assertTrue("Move " + i + " is " + length + " long", length <= maxLength + TOLERANCE);
        }
    }

    private void assertSplitMoveStaysOnAlignedPath(RigidTransform2D transform, Vertex start, String command, Vertex end) throws GcodeParserException {
        List<String> commands = processCommand(command, start,
                new AlignmentProcessor(transform, Units.MM), new LineSplitter(MAX_SEGMENT_LENGTH));

        Vertex alignedStart = vertex(transform, start.x(), start.y(), start.z());
        Vertex alignedEnd = vertex(transform, end.x(), end.y(), end.z());
        List<Position> positions = traceFrom(alignedStart, commands);
        assertOnPath(List.of(alignedStart, alignedEnd), positions, FLAT);
        assertLongestMove(MAX_SEGMENT_LENGTH, positions);
    }

    // Verifies that the test helpers and the leveler processors work on their own

    @Test
    public void levelerWithoutAlignmentShouldFollowPath() throws GcodeParserException {
        List<String> result = process(PROGRAM, createLevelerProcessors());

        assertOnPath(programPath(RigidTransform2D.identity()), trace(result), SURFACE);
    }

    @Test
    public void alignmentWithoutLevelerShouldFollowAlignedPath() throws GcodeParserException {
        for (RigidTransform2D transform : List.of(TRANSLATION, ROTATION, ROTATION_AND_TRANSLATION)) {
            List<String> result = process(PROGRAM, new AlignmentProcessor(transform, Units.MM));

            assertOnPath(programPath(transform), trace(result), FLAT);
        }
    }

    // The alignment processor followed by the line splitter in the same processor list

    @Ignore(KNOWN_ISSUE_STATE)
    @Test
    public void splitMoveFromX0ToX1TranslatedBy10ShouldStayBetweenX10AndX11() throws GcodeParserException {
        List<String> commands = processCommand("G1 X1", new Vertex(0, 0, 0),
                new AlignmentProcessor(new RigidTransform2D(0, 10, 0), Units.MM), new LineSplitter(0.25));

        List<Position> positions = traceFrom(new Vertex(10, 0, 0), commands);
        for (Position position : positions) {
            assertTrue("The tool moves to X" + position.getX(), position.getX() >= 10 - TOLERANCE && position.getX() <= 11 + TOLERANCE);
        }
        assertEquals(11, positions.get(positions.size() - 1).getX(), TOLERANCE);
    }

    @Ignore(KNOWN_ISSUE_STATE)
    @Test
    public void splitMoveInXShouldStayOnAlignedPath() throws GcodeParserException {
        for (RigidTransform2D transform : List.of(TRANSLATION, ROTATION, ROTATION_AND_TRANSLATION)) {
            assertSplitMoveStaysOnAlignedPath(transform, new Vertex(5, 5, -0.1), "G1 X25", new Vertex(25, 5, -0.1));
        }
    }

    @Ignore(KNOWN_ISSUE_STATE)
    @Test
    public void splitMoveInYShouldStayOnAlignedPath() throws GcodeParserException {
        for (RigidTransform2D transform : List.of(TRANSLATION, ROTATION, ROTATION_AND_TRANSLATION)) {
            assertSplitMoveStaysOnAlignedPath(transform, new Vertex(5, 5, -0.1), "G1 Y25", new Vertex(5, 25, -0.1));
        }
    }

    @Ignore(KNOWN_ISSUE_STATE)
    @Test
    public void splitMoveInZShouldStayOnAlignedPath() throws GcodeParserException {
        for (RigidTransform2D transform : List.of(TRANSLATION, ROTATION, ROTATION_AND_TRANSLATION)) {
            assertSplitMoveStaysOnAlignedPath(transform, new Vertex(5, 5, 10), "G1 Z-0.1", new Vertex(5, 5, -0.1));
        }
    }

    @Ignore(KNOWN_ISSUE_STATE)
    @Test
    public void splitRapidMoveShouldStayOnAlignedPath() throws GcodeParserException {
        for (RigidTransform2D transform : List.of(TRANSLATION, ROTATION, ROTATION_AND_TRANSLATION)) {
            assertSplitMoveStaysOnAlignedPath(transform, new Vertex(5, 5, 5), "G0 X30 Y30", new Vertex(30, 30, 5));
        }
    }

    @Ignore(KNOWN_ISSUE_STATE)
    @Test
    public void splitCuttingMoveShouldStayOnAlignedPath() throws GcodeParserException {
        for (RigidTransform2D transform : List.of(TRANSLATION, ROTATION, ROTATION_AND_TRANSLATION)) {
            assertSplitMoveStaysOnAlignedPath(transform, new Vertex(25, 20, -0.1), "G1 X10 Y10 F100", new Vertex(10, 10, -0.1));
        }
    }

    @Ignore(KNOWN_ISSUE_STATE)
    @Test
    public void splitArcsShouldStayOnAlignedArc() throws GcodeParserException {
        // Quarter circles with radius 10 around (20, 20), clockwise from (30, 20) and counter-clockwise back
        for (RigidTransform2D transform : List.of(TRANSLATION, ROTATION, ROTATION_AND_TRANSLATION)) {
            Vertex center = vertex(transform, 20, 20, -0.1);

            List<String> clockwise = processCommand("G2 X20 Y10 I-10 J0", new Vertex(30, 20, -0.1),
                    new AlignmentProcessor(transform, Units.MM), new LineSplitter(MAX_SEGMENT_LENGTH));
            List<Position> positions = traceFrom(vertex(transform, 30, 20, -0.1), clockwise);
            assertOnArc(center, 10, positions);
            Vertex end = vertex(transform, 20, 10, -0.1);
            assertEquals(end.x(), positions.get(positions.size() - 1).getX(), TOLERANCE);
            assertEquals(end.y(), positions.get(positions.size() - 1).getY(), TOLERANCE);

            List<String> counterClockwise = processCommand("G3 X30 Y20 I0 J10", new Vertex(20, 10, -0.1),
                    new AlignmentProcessor(transform, Units.MM), new LineSplitter(MAX_SEGMENT_LENGTH));
            positions = traceFrom(end, counterClockwise);
            assertOnArc(center, 10, positions);
            assertEquals(vertex(transform, 30, 20, -0.1).x(), positions.get(positions.size() - 1).getX(), TOLERANCE);
            assertEquals(vertex(transform, 30, 20, -0.1).y(), positions.get(positions.size() - 1).getY(), TOLERANCE);
        }
    }

    // The alignment processor followed by the leveler processors, as applied to the backend

    @Ignore(KNOWN_ISSUE_STATE)
    @Test
    public void translatedProgramWithLevelerShouldFollowAlignedPathOnSurface() throws GcodeParserException {
        List<String> result = process(PROGRAM, new AlignmentProcessor(TRANSLATION, Units.MM), createLevelerProcessors());

        assertOnPath(programPath(TRANSLATION), trace(result), SURFACE);
    }

    @Ignore(KNOWN_ISSUE_STATE)
    @Test
    public void rotatedProgramWithLevelerShouldFollowAlignedPathOnSurface() throws GcodeParserException {
        List<String> result = process(PROGRAM, new AlignmentProcessor(ROTATION, Units.MM), createLevelerProcessors());

        assertOnPath(programPath(ROTATION), trace(result), SURFACE);
    }

    @Ignore(KNOWN_ISSUE_STATE)
    @Test
    public void rotatedAndTranslatedProgramWithLevelerShouldFollowAlignedPathOnSurface() throws GcodeParserException {
        List<String> result = process(PROGRAM, new AlignmentProcessor(ROTATION_AND_TRANSLATION, Units.MM), createLevelerProcessors());

        assertOnPath(programPath(ROTATION_AND_TRANSLATION), trace(result), SURFACE);
    }

    @Ignore(KNOWN_ISSUE_STATE)
    @Test
    public void programWithArcsAndLevelerShouldFollowAlignedArcOnSurface() throws GcodeParserException {
        List<String> program = List.of("G21 G90 G17", "G0 X30 Y20", "G0 Z-0.1", "G2 X20 Y10 I-10 J0 F100", "G3 X30 Y20 I0 J10");
        Vertex center = vertex(ROTATION_AND_TRANSLATION, 20, 20, -0.1);

        List<String> result = process(program, new AlignmentProcessor(ROTATION_AND_TRANSLATION, Units.MM), createLevelerProcessors());

        List<Position> positions = trace(result);
        assertFalse(positions.isEmpty());
        for (Position position : positions) {
            double radius = Math.hypot(position.getX() - center.x(), position.getY() - center.y());
            assertEquals("Radius at " + position, 10, radius, 0.01);
            assertEquals("Z at " + position, -0.1 + surfaceHeight(position.getX(), position.getY()), position.getZ(), TOLERANCE);
        }
    }

    @Test
    public void programInInchesWithLevelerShouldFollowAlignedPathOnSurface() throws GcodeParserException {
        // The translation is 0.2 inches along Y
        RigidTransform2D transform = new RigidTransform2D(0, 0, 5.08);
        List<String> program = List.of("G20 G90 G17", "G0 Z0.2", "G0 X0.2 Y0.2", "G1 Z-0.004 F2", "G1 X1.2 F4", "G0 Z0.2");
        List<Vertex> path = List.of(
                vertex(transform, 5.08, 5.08, 5.08),
                vertex(transform, 5.08, 5.08, -0.1016),
                vertex(transform, 30.48, 5.08, -0.1016),
                vertex(transform, 30.48, 5.08, 5.08));

        List<String> result = process(program, new AlignmentProcessor(transform, Units.MM), createLevelerProcessors());

        assertOnPath(path, trace(result), SURFACE);
    }

    @Ignore(KNOWN_ISSUE_INCHES)
    @Test
    public void levelerOnProgramInInchesShouldSplitMovesToMeshResolution() throws GcodeParserException {
        List<String> program = List.of("G20 G90 G17", "G0 X0.2 Y0.2", "G0 Z0", "G1 X1.2 F4");

        List<String> result = process(program, createLevelerProcessors());

        assertLongestMove(MAX_SEGMENT_LENGTH, trace(result));
    }

    // Solution A: align the whole program first and run the leveler on the aligned program

    @Test
    public void levelerOnAlignedProgramShouldFollowAlignedPathOnSurface() throws GcodeParserException {
        for (RigidTransform2D transform : List.of(TRANSLATION, ROTATION, ROTATION_AND_TRANSLATION)) {
            List<String> alignedProgram = process(PROGRAM, new AlignmentProcessor(transform, Units.MM));
            List<String> result = process(alignedProgram, createLevelerProcessors());

            List<Position> positions = trace(result);
            assertOnPath(programPath(transform), positions, SURFACE);
            assertLongestMove(MAX_SEGMENT_LENGTH, positions);
        }
    }

    @Test
    public void levelerOnAlignedProgramWithArcsShouldFollowAlignedArcOnSurface() throws GcodeParserException {
        List<String> program = List.of("G21 G90 G17", "G0 X30 Y20", "G0 Z-0.1", "G2 X20 Y10 I-10 J0 F100", "G3 X30 Y20 I0 J10");
        Vertex center = vertex(ROTATION_AND_TRANSLATION, 20, 20, -0.1);

        List<String> alignedProgram = process(program, new AlignmentProcessor(ROTATION_AND_TRANSLATION, Units.MM));
        List<String> result = process(alignedProgram, createLevelerProcessors());

        List<Position> positions = trace(result);
        assertFalse(positions.isEmpty());
        for (Position position : positions) {
            double radius = Math.hypot(position.getX() - center.x(), position.getY() - center.y());
            assertEquals("Radius at " + position, 10, radius, 0.01);
            assertEquals("Z at " + position, -0.1 + surfaceHeight(position.getX(), position.getY()), position.getZ(), TOLERANCE);
        }
    }

    // Other processors and states

    @Ignore(KNOWN_ISSUE_STATE)
    @Test
    public void translateProcessorFromCoreFollowedByLineSplitterShouldStayOnTranslatedPath() throws GcodeParserException {
        List<String> commands = processCommand("G1 X1", new Vertex(0, 0, 0),
                new TranslateProcessor(new Position(10, 0, 0, Units.MM)), new LineSplitter(0.25));

        List<Position> positions = traceFrom(new Vertex(10, 0, 0), commands);
        for (Position position : positions) {
            assertTrue("The tool moves to X" + position.getX(), position.getX() >= 10 - TOLERANCE && position.getX() <= 11 + TOLERANCE);
        }
    }

    @Ignore(KNOWN_ISSUE_WORK_COORDINATES)
    @Test
    public void workCoordinateSystemChangeShouldBeRejected() {
        // The transform is measured in one work coordinate system and is not valid in another
        List<String> program = List.of("G21 G90 G17", "G54", "G0 X5 Y5", "G55", "G0 X5 Y5");

        try {
            List<String> result = process(program, new AlignmentProcessor(TRANSLATION, Units.MM));
            fail("The program was aligned with the same transform in both work coordinate systems: " + result);
        } catch (GcodeParserException e) {
            // Expected
        }
    }
}
