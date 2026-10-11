package com.willwinder.ugs.nbp.pcb.gcode;

import com.willwinder.ugs.nbp.pcb.geometry.Point2D;
import com.willwinder.ugs.nbp.pcb.geometry.RigidTransform2D;
import com.willwinder.universalgcodesender.gcode.util.Code;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UnitUtils.Units;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

public class AlignedGcodeExporterTest {
    private static final RigidTransform2D TRANSFORM = new RigidTransform2D(Math.toRadians(30), 10, 5);
    private static final RigidTransform2D OTHER_TRANSFORM = new RigidTransform2D(Math.toRadians(-2), -3, 4);

    private static final List<String> PROGRAM = List.of(
            "(A program with a comment)",
            "G21 G90 G17",
            "G0 Z5",
            "G0 X5 Y5",
            "G1 Z-0.1 F50",
            "G1 X25 F100",
            "G1 Y20",
            "G2 X15 Y30 I-10 J0",
            "G0 Z5",
            "M5");

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private final AlignedGcodeExporter exporter = new AlignedGcodeExporter();
    private File source;
    private File target;

    @Before
    public void setUp() throws IOException {
        source = writeProgram("board.nc", PROGRAM);
        target = new File(folder.getRoot(), "board_aligned.nc");
    }

    private File writeProgram(String name, List<String> lines) throws IOException {
        File file = new File(folder.getRoot(), name);
        Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
        return file;
    }

    private static List<String> readLines(File file) throws IOException {
        return Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
    }

    private static Position aligned(RigidTransform2D transform, double x, double y, double z) {
        Point2D point = transform.apply(new Point2D(x, y));
        return new Position(point.x(), point.y(), z, Units.MM);
    }

    /**
     * The corners of the tool path of {@link #PROGRAM}, without the arc
     */
    private static List<Position> expectedLinearPath(RigidTransform2D transform) {
        return List.of(
                aligned(transform, 5, 5, 5),
                aligned(transform, 5, 5, -0.1),
                aligned(transform, 25, 5, -0.1),
                aligned(transform, 25, 20, -0.1));
    }

    private static void assertFollowsProgram(RigidTransform2D transform, File alignedFile) throws Exception {
        List<Position> positions = ToolPathValidator.trace(readLines(alignedFile));
        List<Position> expected = expectedLinearPath(transform);

        // The lines up to the arc
        ToolPathValidator.validate(expected, positions.subList(0, expected.size()), 0.001, 0);

        // The arc has radius 10 around (15, 20) and is followed by a retract
        Position center = aligned(transform, 15, 20, -0.1);
        List<Position> arc = positions.subList(expected.size(), positions.size() - 1);
        assertTrue(arc.size() > 10);
        for (Position position : arc) {
            assertEquals(10, Math.hypot(position.getX() - center.getX(), position.getY() - center.getY()), 0.001);
            assertEquals(-0.1, position.getZ(), 0.001);
        }

        Position end = aligned(transform, 15, 30, 5);
        Position last = positions.get(positions.size() - 1);
        assertEquals(end.getX(), last.getX(), 0.001);
        assertEquals(end.getY(), last.getY(), 0.001);
        assertEquals(end.getZ(), last.getZ(), 0.001);
    }

    private static String sha256(File file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath())));
    }

    private List<String> filesInFolder() {
        return Arrays.stream(folder.getRoot().list()).sorted().toList();
    }

    @Test
    public void exportShouldWriteAlignedProgram() throws Exception {
        AlignedGcodeFile result = exporter.export(source, TRANSFORM, Code.G54, target);

        assertEquals(target, result.file());
        assertFollowsProgram(TRANSFORM, target);
        assertEquals(List.of("board.nc", "board_aligned.nc"), filesInFolder());
    }

    @Test
    public void exportShouldNotChangeOriginalProgram() throws Exception {
        byte[] before = Files.readAllBytes(source.toPath());

        exporter.export(source, TRANSFORM, Code.G54, target);

        assertArrayEquals(before, Files.readAllBytes(source.toPath()));
    }

    @Test
    public void exportShouldWriteMetadataThatIdentifiesTheOriginalProgram() throws Exception {
        AlignedGcodeFile result = exporter.export(source, TRANSFORM, Code.G55, target);

        assertEquals(AlignmentMetadata.MARKER, readLines(target).get(0));
        Optional<AlignmentMetadata> metadata = AlignmentMetadata.read(target);
        assertEquals(Optional.of(result.metadata()), metadata);
        assertEquals("board.nc", metadata.get().sourceName());
        assertEquals(sha256(source), metadata.get().sourceSha256());
        assertEquals(TRANSFORM, metadata.get().transform());
        assertEquals(Code.G55, metadata.get().workCoordinateSystem());
        assertEquals(Optional.of(source.getAbsoluteFile()), metadata.get().findSource(target).map(File::getAbsoluteFile));
    }

    @Test
    public void metadataShouldSupportFileNamesThatCanNotBeWrittenInComments() throws Exception {
        source = writeProgram("board (rev 2).nc", PROGRAM);

        AlignedGcodeFile result = exporter.export(source, TRANSFORM, Code.G54, target);

        assertEquals("board (rev 2).nc", AlignmentMetadata.read(target).get().sourceName());
        assertTrue(result.metadata().findSource(target).isPresent());
    }

    @Test
    public void changedOriginalProgramShouldNotBeFoundAsSource() throws Exception {
        AlignedGcodeFile result = exporter.export(source, TRANSFORM, Code.G54, target);

        Files.writeString(source.toPath(), "G0 X99 Y99\n", StandardCharsets.UTF_8);

        assertEquals(Optional.empty(), result.metadata().findSource(target));
    }

    @Test
    public void changedAlignedProgramShouldNotHaveValidMetadata() throws Exception {
        exporter.export(source, TRANSFORM, Code.G54, target);
        List<String> lines = readLines(target);
        lines.set(lines.size() - 3, "G1 X99 Y99");

        Files.write(target.toPath(), lines, StandardCharsets.UTF_8);

        assertEquals(Optional.empty(), AlignmentMetadata.read(target));
        assertTrue(AlignmentMetadata.isAligned(target));
    }

    @Test
    public void truncatedAlignedProgramShouldNotHaveValidMetadata() throws Exception {
        exporter.export(source, TRANSFORM, Code.G54, target);
        List<String> lines = readLines(target);

        Files.write(target.toPath(), lines.subList(0, lines.size() / 2), StandardCharsets.UTF_8);

        assertEquals(Optional.empty(), AlignmentMetadata.read(target));
        assertTrue(AlignmentMetadata.isAligned(target));
    }

    @Test
    public void alignedProgramSavedWithOtherLineEndingsShouldStillHaveValidMetadata() throws Exception {
        AlignedGcodeFile result = exporter.export(source, TRANSFORM, Code.G54, target);

        Files.writeString(target.toPath(), String.join("\r\n", readLines(target)) + "\r\n", StandardCharsets.UTF_8);

        assertEquals(Optional.of(result.metadata()), AlignmentMetadata.read(target));
    }

    @Test
    public void exportOfAlignedProgramShouldBeRejected() throws Exception {
        exporter.export(source, TRANSFORM, Code.G54, target);
        File second = new File(folder.getRoot(), "board_aligned_aligned.nc");

        AlignmentException exception = assertThrows(AlignmentException.class,
                () -> exporter.export(target, TRANSFORM, Code.G54, second));

        assertEquals(AlignedGcodeExporter.ERROR_ALREADY_ALIGNED + target, exception.getMessage());
        assertFalse(second.exists());
    }

    @Test
    public void exportOfChangedAlignedProgramShouldBeRejected() throws Exception {
        exporter.export(source, TRANSFORM, Code.G54, target);
        List<String> lines = readLines(target);
        lines.remove(3);
        Files.write(target.toPath(), lines, StandardCharsets.UTF_8);
        File second = new File(folder.getRoot(), "board_aligned_aligned.nc");

        AlignmentException exception = assertThrows(AlignmentException.class,
                () -> exporter.export(target, TRANSFORM, Code.G54, second));

        assertEquals(AlignedGcodeExporter.ERROR_ALREADY_ALIGNED + target, exception.getMessage());
        assertFalse(second.exists());
    }

    @Test
    public void exportShouldReplaceEarlierAlignedProgramWithoutAddingTheAlignments() throws Exception {
        exporter.export(source, TRANSFORM, Code.G54, target);

        AlignedGcodeFile result = exporter.export(source, OTHER_TRANSFORM, Code.G54, target);

        assertEquals(OTHER_TRANSFORM, result.metadata().transform());
        assertEquals(Optional.of(result.metadata()), AlignmentMetadata.read(target));
        assertFollowsProgram(OTHER_TRANSFORM, target);
    }

    @Test
    public void exportOverOriginalProgramShouldBeRejected() throws Exception {
        byte[] before = Files.readAllBytes(source.toPath());

        AlignmentException exception = assertThrows(AlignmentException.class,
                () -> exporter.export(source, TRANSFORM, Code.G54, source));

        assertEquals(AlignedGcodeExporter.ERROR_TARGET_IS_SOURCE + source, exception.getMessage());
        assertArrayEquals(before, Files.readAllBytes(source.toPath()));
    }

    @Test
    public void exportOverFileThatIsNotAnAlignedProgramShouldBeRejected() throws Exception {
        File other = writeProgram("other.nc", List.of("G0 X1 Y1"));

        AlignmentException exception = assertThrows(AlignmentException.class,
                () -> exporter.export(source, TRANSFORM, Code.G54, other));

        assertEquals(AlignedGcodeExporter.ERROR_TARGET_NOT_ALIGNED + other, exception.getMessage());
        assertEquals(List.of("G0 X1 Y1"), readLines(other));
    }

    @Test
    public void failedExportShouldNotLeaveAnyFile() throws Exception {
        source = writeProgram("relative.nc", List.of("G21 G90", "G0 X5 Y5", "G1 X10", "G91", "G1 X1"));

        AlignmentException exception = assertThrows(AlignmentException.class,
                () -> exporter.export(source, TRANSFORM, Code.G54, target));

        assertEquals(AlignedGcodeExporter.ERROR_LINE + "4: " + AlignmentProcessor.ERROR_RELATIVE_MODE + "G91", exception.getMessage());
        assertEquals(List.of("board.nc", "relative.nc"), filesInFolder());
    }

    @Test
    public void failedExportShouldKeepEarlierAlignedProgram() throws Exception {
        AlignedGcodeFile earlier = exporter.export(source, TRANSFORM, Code.G54, target);
        byte[] before = Files.readAllBytes(target.toPath());
        Files.write(source.toPath(), List.of("G21 G90", "G0 X5 Y5", "G28"), StandardCharsets.UTF_8);

        assertThrows(AlignmentException.class, () -> exporter.export(source, OTHER_TRANSFORM, Code.G54, target));

        assertArrayEquals(before, Files.readAllBytes(target.toPath()));
        assertEquals(Optional.of(earlier.metadata()), AlignmentMetadata.read(target));
        assertEquals(List.of("board.nc", "board_aligned.nc"), filesInFolder());
    }

    @Test
    public void programThatSelectsTheMeasuredWorkCoordinateSystemShouldBeExported() throws Exception {
        source = writeProgram("wcs.nc", List.of("G21 G90 G55", "G0 X5 Y5"));

        exporter.export(source, TRANSFORM, Code.G55, target);

        assertEquals(Code.G55, AlignmentMetadata.read(target).get().workCoordinateSystem());
    }

    @Test
    public void programThatSelectsAnotherWorkCoordinateSystemShouldBeRejected() throws Exception {
        source = writeProgram("wcs.nc", List.of("G21 G90 G54", "G0 X5 Y5", "G55", "G0 X5 Y5"));

        AlignmentException exception = assertThrows(AlignmentException.class,
                () -> exporter.export(source, TRANSFORM, Code.G54, target));

        assertEquals(AlignedGcodeExporter.ERROR_LINE + "3: " + AlignmentProcessor.ERROR_WORK_COORDINATE_SYSTEM + "G55", exception.getMessage());
        assertFalse(target.exists());
    }

    @Test
    public void invalidArgumentsShouldBeRejected() {
        assertThrows(AlignmentException.class, () -> exporter.export(source, new RigidTransform2D(Double.NaN, 0, 0), Code.G54, target));
        assertThrows(AlignmentException.class, () -> exporter.export(source, new RigidTransform2D(0, Double.POSITIVE_INFINITY, 0), Code.G54, target));
        assertThrows(AlignmentException.class, () -> exporter.export(source, TRANSFORM, Code.G0, target));
        assertThrows(AlignmentException.class, () -> exporter.export(new File(folder.getRoot(), "missing.nc"), TRANSFORM, Code.G54, target));
        assertFalse(target.exists());
    }
}
