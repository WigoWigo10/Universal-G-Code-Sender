package com.willwinder.ugs.nbp.pcb.service;

import com.willwinder.ugs.nbp.pcb.gcode.AlignedGcodeExporter;
import com.willwinder.ugs.nbp.pcb.gcode.AlignedGcodeFile;
import com.willwinder.ugs.nbp.pcb.gcode.AlignmentException;
import com.willwinder.ugs.nbp.pcb.gcode.AlignmentMetadata;
import com.willwinder.ugs.nbp.pcb.gcode.ToolPathValidator;
import com.willwinder.ugs.nbp.pcb.geometry.Point2D;
import com.willwinder.ugs.nbp.pcb.geometry.RigidTransform2D;
import com.willwinder.ugs.nbp.pcb.service.PcbAlignmentService.AlignmentPlan;
import com.willwinder.ugs.nbp.pcb.service.PcbAlignmentService.ValidationState;
import com.willwinder.universalgcodesender.gcode.DefaultCommandCreator;
import com.willwinder.universalgcodesender.gcode.processors.ArcExpander;
import com.willwinder.universalgcodesender.gcode.processors.CommandProcessorList;
import com.willwinder.universalgcodesender.gcode.processors.LineSplitter;
import com.willwinder.universalgcodesender.gcode.processors.MeshLeveler;
import com.willwinder.universalgcodesender.gcode.util.Code;
import com.willwinder.universalgcodesender.model.GUIBackend;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UnitUtils.Units;
import com.willwinder.universalgcodesender.services.FileLoader;
import com.willwinder.universalgcodesender.utils.GcodeStreamReader;
import com.willwinder.universalgcodesender.utils.IGcodeStreamReader;
import com.willwinder.universalgcodesender.utils.Settings;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Tests the alignment service with a loader that opens the program later, as the platform does:
 * its loader asks the editor to open the file, which closes the open program and loads the new
 * one some time after the service has returned.
 */
public class PcbAlignmentServiceDeferredLoadTest {
    private static final RigidTransform2D TRANSFORM = new RigidTransform2D(Math.toRadians(30), 10, 5);
    private static final RigidTransform2D OTHER_TRANSFORM = new RigidTransform2D(Math.toRadians(-2), -3, 4);

    /**
     * An alignment that differs so little from the first that the tool paths are the same within the tolerance
     */
    private static final RigidTransform2D ALMOST_THE_SAME_TRANSFORM = new RigidTransform2D(Math.toRadians(30), 10.002, 5);

    private static final List<String> PROGRAM = List.of(
            "G21 G90 G17",
            "G0 Z5",
            "G0 X5 Y5",
            "G1 Z-0.1 F50",
            "G1 X25 F100",
            "G1 Y20",
            "G1 X10 Y10",
            "G0 Z5",
            "M5");

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private GUIBackend backend;
    private PcbAlignmentService service;
    private File source;
    private File alignedFile;
    private final List<AlignmentException> errors = new ArrayList<>();
    private final Deque<File> filesToOpen = new ArrayDeque<>();

    @Before
    public void setUp() throws Exception {
        backend = new GUIBackend();
        backend.applySettings(new Settings());
        FileLoader editorLoader = filesToOpen::add;
        service = new PcbAlignmentService(backend, editorLoader, errors::add);

        source = new File(folder.getRoot(), "board.nc");
        Files.write(source.toPath(), PROGRAM, StandardCharsets.UTF_8);
        alignedFile = new File(folder.getRoot(), "board_aligned.nc");
        backend.setGcodeFile(source);
    }

    /**
     * Does what the editor does when it gets to open the file: closes the open program and loads the new one
     */
    private void letEditorOpenFile() throws Exception {
        File file = filesToOpen.remove();
        backend.unsetGcodeFile();
        backend.setGcodeFile(file);
    }

    private static Position aligned(RigidTransform2D transform, double x, double y, double z) {
        Point2D point = transform.apply(new Point2D(x, y));
        return new Position(point.x(), point.y(), z, Units.MM);
    }

    private static List<Position> expectedPath(RigidTransform2D transform) {
        return List.of(
                aligned(transform, 5, 5, 5),
                aligned(transform, 5, 5, -0.1),
                aligned(transform, 25, 5, -0.1),
                aligned(transform, 25, 20, -0.1),
                aligned(transform, 10, 10, -0.1),
                aligned(transform, 10, 10, 5));
    }

    private List<Position> processedPath() throws Exception {
        List<String> commands = new ArrayList<>();
        try (IGcodeStreamReader reader = new GcodeStreamReader(backend.getProcessedGcodeFile(), new DefaultCommandCreator())) {
            while (reader.getNumRowsRemaining() > 0) {
                commands.add(reader.getNextCommand().getCommandString());
            }
        }
        return ToolPathValidator.trace(commands);
    }

    private void assertNotValidatedYet() {
        assertEquals(ValidationState.PENDING, service.getValidationState());
        assertTrue(service.getApproval().isEmpty());
        assertTrue(errors.toString(), errors.isEmpty());
    }

    private void assertValidated(RigidTransform2D transform) throws Exception {
        assertEquals(ValidationState.VALID, service.getValidationState());
        assertEquals(alignedFile, backend.getGcodeFile());
        assertEquals(transform, service.getApproval().orElseThrow().metadata().transform());
        ToolPathValidator.validate(expectedPath(transform), processedPath(), 0.001, 0);
        assertTrue(errors.toString(), errors.isEmpty());
    }

    private static CommandProcessorList createLevelerProcessors() {
        int points = 13;
        Position[][] mesh = new Position[points][points];
        for (int xi = 0; xi < points; xi++) {
            for (int yi = 0; yi < points; yi++) {
                double x = -60 + xi * 10;
                double y = -60 + yi * 10;
                mesh[xi][yi] = new Position(x, y, 0.002 * x + 0.003 * y + 0.05, Units.MM);
            }
        }

        CommandProcessorList result = new CommandProcessorList();
        result.add(new ArcExpander(true, 0.1));
        result.add(new LineSplitter(2.5));
        result.add(new MeshLeveler(0, mesh));
        return result;
    }

    // Aligning an original program

    @Test
    public void alignedProgramShouldNotBeValidatedBeforeTheEditorHasLoadedIt() throws Exception {
        service.alignLoadedFile(TRANSFORM, Code.G54);

        assertNotValidatedYet();
        assertEquals(source, backend.getGcodeFile());
    }

    @Test
    public void alignedProgramShouldBeValidatedWhenTheEditorHasLoadedIt() throws Exception {
        service.alignLoadedFile(TRANSFORM, Code.G54);

        letEditorOpenFile();

        assertValidated(TRANSFORM);
    }

    @Test
    public void alignedProgramShouldStayPendingIfTheEditorNeverLoadsIt() throws Exception {
        service.alignLoadedFile(TRANSFORM, Code.G54);

        backend.reloadGcodeFile();

        assertNotValidatedYet();
        assertEquals(source, backend.getGcodeFile());
    }

    // Aligning again while the aligned program is open, which is written to the same file

    @Test
    public void programAlignedAgainShouldNotBeValidatedAgainstTheEarlierProgramThatIsStillLoaded() throws Exception {
        service.alignLoadedFile(TRANSFORM, Code.G54);
        letEditorOpenFile();

        // The backend still has the earlier aligned program, which has the same path as the new one
        service.alignLoadedFile(OTHER_TRANSFORM, Code.G54);

        assertEquals(alignedFile, backend.getGcodeFile());
        assertNotValidatedYet();
        assertEquals(OTHER_TRANSFORM, AlignmentMetadata.read(alignedFile).orElseThrow().transform());
    }

    @Test
    public void programAlignedAgainShouldBeValidatedWhenTheEditorHasLoadedTheNewOne() throws Exception {
        service.alignLoadedFile(TRANSFORM, Code.G54);
        letEditorOpenFile();
        service.alignLoadedFile(OTHER_TRANSFORM, Code.G54);

        letEditorOpenFile();

        assertValidated(OTHER_TRANSFORM);
    }

    @Test
    public void programAlignedAgainWithAlmostTheSameAlignmentShouldNotBeValidatedBeforeItIsLoaded() throws Exception {
        service.alignLoadedFile(TRANSFORM, Code.G54);
        letEditorOpenFile();

        service.alignLoadedFile(ALMOST_THE_SAME_TRANSFORM, Code.G54);

        assertNotValidatedYet();

        letEditorOpenFile();
        assertValidated(ALMOST_THE_SAME_TRANSFORM);
    }

    @Test
    public void earlierProgramThatIsStillLoadedShouldNotBeValidatedDeliberatelyEither() throws Exception {
        service.alignLoadedFile(TRANSFORM, Code.G54);
        letEditorOpenFile();
        service.alignLoadedFile(ALMOST_THE_SAME_TRANSFORM, Code.G54);

        // The tool paths are the same within the tolerance, but the program that would be sent is the earlier one
        AlignmentException exception = assertThrows(AlignmentException.class, () -> service.validateProcessedFile(0));

        assertEquals(PcbAlignmentService.ERROR_PROCESSED_FROM_OTHER_PROGRAM, exception.getMessage());
        assertEquals(ValidationState.INVALID, service.getValidationState());
        assertTrue(service.getApproval().isEmpty());
    }

    @Test
    public void programAlignedAgainWithTheSameAlignmentShouldBeValidatedWhenItIsLoaded() throws Exception {
        service.alignLoadedFile(TRANSFORM, Code.G54);
        letEditorOpenFile();

        service.alignLoadedFile(TRANSFORM, Code.G54);
        assertNotValidatedYet();

        letEditorOpenFile();
        assertValidated(TRANSFORM);
    }

    // Other programs being opened

    @Test
    public void openingAnotherProgramBeforeTheEditorLoadsTheAlignedOneShouldNotValidateAnything() throws Exception {
        File other = new File(folder.getRoot(), "other.nc");
        Files.write(other.toPath(), PROGRAM, StandardCharsets.UTF_8);
        service.alignLoadedFile(TRANSFORM, Code.G54);

        backend.setGcodeFile(other);

        assertNotValidatedYet();
        assertEquals(other, backend.getGcodeFile());

        letEditorOpenFile();
        assertValidated(TRANSFORM);
    }

    @Test
    public void openingAnotherProgramAfterwardsShouldLeaveNothingValidated() throws Exception {
        File other = new File(folder.getRoot(), "other.nc");
        Files.write(other.toPath(), PROGRAM, StandardCharsets.UTF_8);
        service.alignLoadedFile(TRANSFORM, Code.G54);
        letEditorOpenFile();

        backend.setGcodeFile(other);

        assertEquals(ValidationState.NOT_VALIDATED, service.getValidationState());
        assertTrue(service.getApproval().isEmpty());
    }

    @Test
    public void openingTheAlignedProgramAgainShouldValidateItAgain() throws Exception {
        service.alignLoadedFile(TRANSFORM, Code.G54);
        letEditorOpenFile();
        backend.setGcodeFile(source);

        backend.setGcodeFile(alignedFile);

        assertValidated(TRANSFORM);
    }

    @Test
    public void openingTheAlignedProgramAgainAfterItWasChangedShouldNotValidateIt() throws Exception {
        service.alignLoadedFile(TRANSFORM, Code.G54);
        letEditorOpenFile();
        backend.setGcodeFile(source);
        List<String> lines = Files.readAllLines(alignedFile.toPath(), StandardCharsets.UTF_8);
        lines.add("G0 X0 Y0");
        Files.write(alignedFile.toPath(), lines, StandardCharsets.UTF_8);

        backend.setGcodeFile(alignedFile);

        assertEquals(ValidationState.NOT_VALIDATED, service.getValidationState());
        assertTrue(service.getApproval().isEmpty());
    }

    @Test
    public void processingTheAlignedProgramAgainShouldOnlyKeepItValidatedIfNothingChangedIt() throws Exception {
        service.alignLoadedFile(TRANSFORM, Code.G54);
        letEditorOpenFile();

        backend.reloadGcodeFile();
        assertValidated(TRANSFORM);

        backend.applyCommandProcessor(createLevelerProcessors());
        assertEquals(ValidationState.NOT_VALIDATED, service.getValidationState());
        assertTrue(service.getApproval().isEmpty());
    }

    // What is applied is what was planned

    @Test
    public void planShouldDescribeWhatWillBeDoneWithoutDoingIt() throws Exception {
        AlignmentPlan plan = service.plan(TRANSFORM, Code.G54);

        assertEquals(source.getAbsoluteFile(), plan.loadedFile());
        assertEquals(source, plan.source());
        assertEquals(alignedFile, plan.target());
        assertEquals(AlignmentMetadata.sha256(Files.readAllBytes(source.toPath())), plan.sourceSha256());
        assertFalse(alignedFile.exists());
        assertTrue(filesToOpen.isEmpty());
        assertEquals(ValidationState.NOT_VALIDATED, service.getValidationState());
    }

    @Test
    public void planOfAlignedProgramShouldBeForItsOriginal() throws Exception {
        service.alignLoadedFile(TRANSFORM, Code.G54);
        letEditorOpenFile();

        AlignmentPlan plan = service.plan(OTHER_TRANSFORM, Code.G54);

        assertEquals(alignedFile.getAbsoluteFile(), plan.loadedFile());
        assertEquals(source.getAbsoluteFile(), plan.source().getAbsoluteFile());
        assertEquals(alignedFile, plan.target());
    }

    @Test
    public void planShouldNotBeAppliedWhenAnotherProgramHasBeenLoaded() throws Exception {
        File other = new File(folder.getRoot(), "other.nc");
        Files.write(other.toPath(), PROGRAM, StandardCharsets.UTF_8);
        AlignmentPlan plan = service.plan(TRANSFORM, Code.G54);

        backend.setGcodeFile(other);
        AlignmentException exception = assertThrows(AlignmentException.class, () -> service.apply(plan));

        assertEquals(PcbAlignmentService.ERROR_PLAN_OUTDATED, exception.getMessage());
        assertFalse(alignedFile.exists());
        assertFalse(new File(folder.getRoot(), "other_aligned.nc").exists());
        assertTrue(filesToOpen.isEmpty());
    }

    @Test
    public void planShouldNotBeAppliedWhenTheOriginalProgramHasBeenChanged() throws Exception {
        AlignmentPlan plan = service.plan(TRANSFORM, Code.G54);

        Files.write(source.toPath(), List.of("G21 G90", "G0 X1 Y1"), StandardCharsets.UTF_8);
        AlignmentException exception = assertThrows(AlignmentException.class, () -> service.apply(plan));

        assertEquals(AlignedGcodeExporter.ERROR_SOURCE_CHANGED + source, exception.getMessage());
        assertFalse(alignedFile.exists());
        assertTrue(filesToOpen.isEmpty());
    }

    @Test
    public void planShouldNotReplaceEarlierAlignedProgramWhenTheOriginalHasBeenChanged() throws Exception {
        AlignedGcodeFile earlier = service.alignLoadedFile(TRANSFORM, Code.G54);
        letEditorOpenFile();
        byte[] before = Files.readAllBytes(alignedFile.toPath());
        AlignmentPlan plan = service.plan(OTHER_TRANSFORM, Code.G54);

        Files.write(source.toPath(), List.of("G21 G90", "G0 X1 Y1"), StandardCharsets.UTF_8);
        assertThrows(AlignmentException.class, () -> service.apply(plan));

        assertArrayEquals(before, Files.readAllBytes(alignedFile.toPath()));
        assertEquals(earlier.metadata(), service.getApproval().orElseThrow().metadata());
    }

    @Test
    public void planShouldBeAppliedAsItWasMade() throws Exception {
        AlignmentPlan plan = service.plan(TRANSFORM, Code.G54);

        AlignedGcodeFile result = service.apply(plan);
        letEditorOpenFile();

        assertEquals(plan.target(), result.file());
        assertEquals(plan.sourceSha256(), result.metadata().sourceSha256());
        assertValidated(TRANSFORM);
    }
}
