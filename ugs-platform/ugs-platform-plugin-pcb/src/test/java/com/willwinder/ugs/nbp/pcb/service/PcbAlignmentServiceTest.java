package com.willwinder.ugs.nbp.pcb.service;

import com.willwinder.ugs.nbp.pcb.gcode.AlignedGcodeExporter;
import com.willwinder.ugs.nbp.pcb.gcode.AlignedGcodeFile;
import com.willwinder.ugs.nbp.pcb.gcode.AlignmentException;
import com.willwinder.ugs.nbp.pcb.gcode.AlignmentMetadata;
import com.willwinder.ugs.nbp.pcb.gcode.ToolPathValidator;
import com.willwinder.ugs.nbp.pcb.geometry.Point2D;
import com.willwinder.ugs.nbp.pcb.geometry.RigidTransform2D;
import com.willwinder.ugs.nbp.pcb.service.PcbAlignmentService.ValidationState;
import com.willwinder.universalgcodesender.gcode.DefaultCommandCreator;
import com.willwinder.universalgcodesender.gcode.GcodeState;
import com.willwinder.universalgcodesender.gcode.processors.ArcExpander;
import com.willwinder.universalgcodesender.gcode.processors.CommandProcessor;
import com.willwinder.universalgcodesender.gcode.processors.CommandProcessorList;
import com.willwinder.universalgcodesender.gcode.processors.LineSplitter;
import com.willwinder.universalgcodesender.gcode.processors.MeshLeveler;
import com.willwinder.universalgcodesender.gcode.processors.TranslateProcessor;
import com.willwinder.universalgcodesender.gcode.util.Code;
import com.willwinder.universalgcodesender.gcode.util.GcodeParserException;
import com.willwinder.universalgcodesender.model.GUIBackend;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UnitUtils.Units;
import com.willwinder.universalgcodesender.services.BackendFileLoader;
import com.willwinder.universalgcodesender.utils.AutoLevelSettings;
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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class PcbAlignmentServiceTest {
    private static final RigidTransform2D TRANSFORM = new RigidTransform2D(Math.toRadians(30), 10, 5);
    private static final RigidTransform2D OTHER_TRANSFORM = new RigidTransform2D(Math.toRadians(-2), -3, 4);

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
    private final List<AlignmentException> errors = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        backend = spy(new GUIBackend());
        backend.applySettings(new Settings());
        service = new PcbAlignmentService(backend, new BackendFileLoader(backend), errors::add);

        source = new File(folder.getRoot(), "board.nc");
        Files.write(source.toPath(), PROGRAM, StandardCharsets.UTF_8);
        backend.setGcodeFile(source);
    }

    private static Position aligned(RigidTransform2D transform, double x, double y, double z) {
        Point2D point = transform.apply(new Point2D(x, y));
        return new Position(point.x(), point.y(), z, Units.MM);
    }

    /**
     * The tool path of {@link #PROGRAM}
     */
    private static List<Position> expectedPath(RigidTransform2D transform) {
        return List.of(
                aligned(transform, 5, 5, 5),
                aligned(transform, 5, 5, -0.1),
                aligned(transform, 25, 5, -0.1),
                aligned(transform, 25, 20, -0.1),
                aligned(transform, 10, 10, -0.1),
                aligned(transform, 10, 10, 5));
    }

    /**
     * The positions of the program that the backend would send
     */
    private List<Position> processedPath() throws Exception {
        List<String> commands = new ArrayList<>();
        try (IGcodeStreamReader reader = new GcodeStreamReader(backend.getProcessedGcodeFile(), new DefaultCommandCreator())) {
            while (reader.getNumRowsRemaining() > 0) {
                commands.add(reader.getNextCommand().getCommandString());
            }
        }
        return ToolPathValidator.trace(commands);
    }

    private static double surfaceHeight(double x, double y) {
        return 0.002 * x + 0.003 * y + 0.05;
    }

    /**
     * The same processors in the same order as the auto leveler applies to the backend
     */
    private static CommandProcessorList createLevelerProcessors() {
        int points = 13;
        Position[][] mesh = new Position[points][points];
        for (int xi = 0; xi < points; xi++) {
            for (int yi = 0; yi < points; yi++) {
                double x = -60 + xi * 10;
                double y = -60 + yi * 10;
                mesh[xi][yi] = new Position(x, y, surfaceHeight(x, y), Units.MM);
            }
        }

        CommandProcessorList result = new CommandProcessorList();
        result.add(new ArcExpander(true, 0.1));
        result.add(new LineSplitter(2.5));
        result.add(new MeshLeveler(0, mesh));
        return result;
    }

    private static CommandProcessor failsOn(String text) {
        return new CommandProcessor() {
            @Override
            public List<String> processCommand(String command, GcodeState state) throws GcodeParserException {
                if (command.contains(text)) {
                    throw new GcodeParserException("Can not process " + command);
                }
                return List.of(command);
            }

            @Override
            public String getHelp() {
                return "";
            }
        };
    }

    @Test
    public void alignLoadedFileShouldLoadAlignedProgramNextToOriginal() throws Exception {
        AlignedGcodeFile result = service.alignLoadedFile(TRANSFORM, Code.G54);

        assertEquals(new File(folder.getRoot(), "board_aligned.nc"), result.file());
        assertEquals(result.file(), backend.getGcodeFile());
        assertEquals(ValidationState.VALID, service.getValidationState());
        ToolPathValidator.validate(expectedPath(TRANSFORM), processedPath(), 0.001, 0);
        assertTrue(errors.isEmpty());
    }

    @Test
    public void serviceWithDefaultLoaderShouldLoadAlignedProgram() throws Exception {
        service = new PcbAlignmentService(backend);

        AlignedGcodeFile result = service.alignLoadedFile(TRANSFORM, Code.G54);

        assertEquals(result.file(), backend.getGcodeFile());
        assertEquals(ValidationState.VALID, service.getValidationState());
    }

    @Test
    public void alignLoadedFileShouldNotChangeOriginalProgram() throws Exception {
        byte[] before = Files.readAllBytes(source.toPath());

        service.alignLoadedFile(TRANSFORM, Code.G54);

        assertArrayEquals(before, Files.readAllBytes(source.toPath()));
    }

    @Test
    public void alignLoadedFileShouldTurnOffApplyingTheHeightMap() throws Exception {
        backend.getSettings().getAutoLevelSettings().setApplyToGcode(true);

        service.alignLoadedFile(TRANSFORM, Code.G54);

        assertFalse(backend.getSettings().getAutoLevelSettings().getApplyToGcode());
    }

    @Test
    public void heightMapShouldStayTurnedOffWhenAnotherSettingOfTheAutoLevelerIsChanged() throws Exception {
        AutoLevelSettings settings = backend.getSettings().getAutoLevelSettings();
        settings.setApplyToGcode(true);

        // A panel of the auto leveler keeps a copy of the settings that it writes back when one is changed
        AutoLevelSettings panelSettings = new AutoLevelSettings(settings);
        service.alignLoadedFile(TRANSFORM, Code.G54);
        panelSettings.apply(settings);
        panelSettings.setStepResolution(5);
        settings.apply(panelSettings);

        assertFalse(settings.getApplyToGcode());
        assertEquals(5, settings.getStepResolution(), 0);
    }

    @Test
    public void heightMapShouldBeTurnedOffInCopiesOfTheSettings() throws Exception {
        backend.getSettings().getAutoLevelSettings().setApplyToGcode(true);

        service.alignLoadedFile(TRANSFORM, Code.G54);

        assertFalse(new AutoLevelSettings(backend.getSettings().getAutoLevelSettings()).getApplyToGcode());
    }

    @Test
    public void aligningAnAlignedProgramShouldAlignItsOriginalAgain() throws Exception {
        service.alignLoadedFile(TRANSFORM, Code.G54);

        AlignedGcodeFile result = service.alignLoadedFile(OTHER_TRANSFORM, Code.G54);

        assertEquals(OTHER_TRANSFORM, result.metadata().transform());
        assertEquals(new File(folder.getRoot(), "board_aligned.nc"), backend.getGcodeFile());
        ToolPathValidator.validate(expectedPath(OTHER_TRANSFORM), processedPath(), 0.001, 0);
    }

    @Test
    public void aligningAnAlignedProgramWithoutItsOriginalShouldFail() throws Exception {
        AlignedGcodeFile alignedFile = service.alignLoadedFile(TRANSFORM, Code.G54);
        assertTrue(source.delete());

        AlignmentException exception = assertThrows(AlignmentException.class, () -> service.alignLoadedFile(OTHER_TRANSFORM, Code.G54));

        assertEquals(PcbAlignmentService.ERROR_SOURCE_MISSING + alignedFile.file(), exception.getMessage());
        assertEquals(TRANSFORM, AlignmentMetadata.read(alignedFile.file()).get().transform());
    }

    @Test
    public void aligningAnAlignedProgramWithChangedOriginalShouldFail() throws Exception {
        AlignedGcodeFile alignedFile = service.alignLoadedFile(TRANSFORM, Code.G54);
        Files.writeString(source.toPath(), "G0 X1 Y1\n", StandardCharsets.UTF_8);

        AlignmentException exception = assertThrows(AlignmentException.class, () -> service.alignLoadedFile(OTHER_TRANSFORM, Code.G54));

        assertEquals(PcbAlignmentService.ERROR_SOURCE_MISSING + alignedFile.file(), exception.getMessage());
    }

    @Test
    public void failedAlignmentShouldKeepLoadedProgram() throws Exception {
        Files.write(source.toPath(), List.of("G21 G90", "G0 X5 Y5", "G91", "G1 X1"), StandardCharsets.UTF_8);
        backend.setGcodeFile(source);

        assertThrows(AlignmentException.class, () -> service.alignLoadedFile(TRANSFORM, Code.G54));

        assertEquals(source, backend.getGcodeFile());
        assertFalse(new File(folder.getRoot(), "board_aligned.nc").exists());
    }

    @Test
    public void alignLoadedFileWithoutProgramShouldFail() throws Exception {
        backend.unsetGcodeFile();

        AlignmentException exception = assertThrows(AlignmentException.class, () -> service.alignLoadedFile(TRANSFORM, Code.G54));

        assertEquals(PcbAlignmentService.ERROR_NO_FILE, exception.getMessage());
    }

    @Test
    public void alignLoadedFileShouldFailWhenMachineUsesAnotherWorkCoordinateSystem() {
        GcodeState machineState = new GcodeState();
        machineState.offset = Code.G55;
        doReturn(true).when(backend).isConnected();
        doReturn(machineState).when(backend).getGcodeState();

        AlignmentException exception = assertThrows(AlignmentException.class, () -> service.alignLoadedFile(TRANSFORM, Code.G54));

        assertTrue(exception.getMessage(), exception.getMessage().startsWith(PcbAlignmentService.ERROR_WORK_COORDINATE_SYSTEM));
        assertEquals(source, backend.getGcodeFile());
        assertFalse(new File(folder.getRoot(), "board_aligned.nc").exists());
    }

    @Test
    public void alignLoadedFileShouldWorkWhenMachineUsesTheMeasuredWorkCoordinateSystem() throws Exception {
        GcodeState machineState = new GcodeState();
        machineState.offset = Code.G55;
        doReturn(true).when(backend).isConnected();
        doReturn(machineState).when(backend).getGcodeState();

        AlignedGcodeFile result = service.alignLoadedFile(TRANSFORM, Code.G55);

        assertEquals(result.file(), backend.getGcodeFile());
        assertEquals(ValidationState.VALID, service.getValidationState());
    }

    @Test
    public void validationShouldFailWhenMachineChangedWorkCoordinateSystemAfterAlignment() throws Exception {
        service.alignLoadedFile(TRANSFORM, Code.G54);
        GcodeState machineState = new GcodeState();
        machineState.offset = Code.G55;
        doReturn(true).when(backend).isConnected();
        doReturn(machineState).when(backend).getGcodeState();

        AlignmentException exception = assertThrows(AlignmentException.class, () -> service.validateProcessedFile(0));

        assertTrue(exception.getMessage(), exception.getMessage().startsWith(PcbAlignmentService.ERROR_WORK_COORDINATE_SYSTEM));
        assertEquals(ValidationState.INVALID, service.getValidationState());
    }

    @Test
    public void loadShouldRejectChangedAlignedProgram() throws Exception {
        File target = new File(folder.getRoot(), "other_aligned.nc");
        AlignedGcodeFile alignedFile = new AlignedGcodeExporter().export(source, TRANSFORM, Code.G54, target);
        List<String> lines = Files.readAllLines(target.toPath(), StandardCharsets.UTF_8);
        lines.add("G0 X0 Y0");
        Files.write(target.toPath(), lines, StandardCharsets.UTF_8);

        AlignmentException exception = assertThrows(AlignmentException.class, () -> service.load(alignedFile));

        assertEquals(PcbAlignmentService.ERROR_NOT_ALIGNED + target, exception.getMessage());
        assertEquals(source, backend.getGcodeFile());
    }

    @Test
    public void programLoadedLaterShouldBeValidatedWhenBackendHasLoadedIt() throws Exception {
        service = new PcbAlignmentService(backend, file -> {
            // Opens the file later, like the loader of the platform does
        }, errors::add);
        AlignedGcodeFile alignedFile = new AlignedGcodeExporter().export(source, TRANSFORM, Code.G54, new File(folder.getRoot(), "board_aligned.nc"));

        service.load(alignedFile);
        assertEquals(ValidationState.PENDING, service.getValidationState());

        backend.setGcodeFile(alignedFile.file());
        assertEquals(ValidationState.VALID, service.getValidationState());
        assertTrue(errors.isEmpty());
    }

    @Test
    public void validationShouldBeRequiredAgainAfterProgramIsProcessedAgain() throws Exception {
        service.alignLoadedFile(TRANSFORM, Code.G54);

        backend.applyCommandProcessor(createLevelerProcessors());

        assertEquals(ValidationState.NOT_VALIDATED, service.getValidationState());
    }

    @Test
    public void leveledAlignedProgramShouldBeValidWithinZLimit() throws Exception {
        service.alignLoadedFile(TRANSFORM, Code.G54);
        backend.applyCommandProcessor(createLevelerProcessors());

        assertThrows(AlignmentException.class, () -> service.validateProcessedFile(0));
        assertEquals(ValidationState.INVALID, service.getValidationState());

        service.validateProcessedFile(0.5);
        assertEquals(ValidationState.VALID, service.getValidationState());
    }

    @Test
    public void leveledAlignedProgramShouldFollowAlignedPathOnSurface() throws Exception {
        service.alignLoadedFile(TRANSFORM, Code.G54);
        backend.applyCommandProcessor(createLevelerProcessors());

        List<Position> positions = processedPath();
        ToolPathValidator.validate(expectedPath(TRANSFORM), positions, 0.001, 0.5);
        for (int i = 1; i < positions.size(); i++) {
            assertTrue(positions.get(i - 1).distanceXYZ(positions.get(i)) <= 2.5 + 0.001);
        }

        // The heights are those of the surface at the aligned positions
        Position start = aligned(TRANSFORM, 25, 5, -0.1);
        Position end = aligned(TRANSFORM, 25, 20, -0.1);
        for (Position position : positions) {
            if (Math.abs(position.getZ()) < 1) {
                assertEquals(-0.1 + surfaceHeight(position.getX(), position.getY()), position.getZ(), 0.001);
            }
        }
        assertTrue(positions.stream().anyMatch(p -> Math.hypot(p.getX() - start.getX(), p.getY() - start.getY()) < 0.001));
        assertTrue(positions.stream().anyMatch(p -> Math.hypot(p.getX() - end.getX(), p.getY() - end.getY()) < 0.001));
    }

    @Test
    public void programThatCouldNotBeProcessedShouldBeInvalid() throws Exception {
        service.alignLoadedFile(TRANSFORM, Code.G54);

        // The backend does not keep any of the program when processing fails
        assertThrows(Exception.class, () -> backend.applyCommandProcessor(failsOn("F100")));
        assertEquals(ValidationState.NOT_VALIDATED, service.getValidationState());
        assertTrue(service.getApproval().isEmpty());

        AlignmentException exception = assertThrows(AlignmentException.class, () -> service.validateProcessedFile(0));
        assertEquals(PcbAlignmentService.ERROR_NOT_PROCESSED, exception.getMessage());
        assertEquals(ValidationState.INVALID, service.getValidationState());
    }

    @Test
    public void programShouldBeValidAgainWhenFailingProcessorIsRemoved() throws Exception {
        service.alignLoadedFile(TRANSFORM, Code.G54);
        CommandProcessor failing = failsOn("F100");
        assertThrows(Exception.class, () -> backend.applyCommandProcessor(failing));

        backend.removeCommandProcessor(failing);
        service.validateProcessedFile(0);

        assertEquals(ValidationState.VALID, service.getValidationState());
        ToolPathValidator.validate(expectedPath(TRANSFORM), processedPath(), 0.001, 0);
    }

    @Test
    public void programMovedByAnotherProcessorShouldBeInvalid() throws Exception {
        service.alignLoadedFile(TRANSFORM, Code.G54);
        backend.applyCommandProcessor(new TranslateProcessor(new Position(1, 0, 0, Units.MM)));

        AlignmentException exception = assertThrows(AlignmentException.class, () -> service.validateProcessedFile(0));

        assertTrue(exception.getMessage(), exception.getMessage().startsWith(ToolPathValidator.ERROR_OFF_PATH));
    }

    @Test
    public void validationShouldFailForProgramThatIsNotAligned() {
        AlignmentException exception = assertThrows(AlignmentException.class, () -> service.validateProcessedFile(0));

        assertEquals(PcbAlignmentService.ERROR_NOT_ALIGNED + source, exception.getMessage());
        assertEquals(ValidationState.INVALID, service.getValidationState());
    }

    @Test
    public void openingAnotherProgramShouldClearValidation() throws Exception {
        service.alignLoadedFile(TRANSFORM, Code.G54);

        backend.setGcodeFile(source);

        assertEquals(ValidationState.NOT_VALIDATED, service.getValidationState());
    }
}
