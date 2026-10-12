/*
    Copyright 2026 Will Winder

    This file is part of Universal Gcode Sender (UGS).

    UGS is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    UGS is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with UGS.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.willwinder.ugs.nbp.pcb.service;

import com.willwinder.ugs.nbp.pcb.gcode.AlignedGcodeFile;
import com.willwinder.ugs.nbp.pcb.gcode.AlignmentException;
import com.willwinder.ugs.nbp.pcb.gcode.ToolPathValidator;
import com.willwinder.ugs.nbp.pcb.geometry.Point2D;
import com.willwinder.ugs.nbp.pcb.geometry.RigidTransform2D;
import com.willwinder.ugs.nbp.pcb.heightmap.GridHeightMap;
import com.willwinder.ugs.nbp.pcb.heightmap.HeightMapBinding;
import com.willwinder.ugs.nbp.pcb.heightmap.HeightMapException;
import com.willwinder.ugs.nbp.pcb.heightmap.HeightMapFile;
import com.willwinder.ugs.nbp.pcb.heightmap.PcbHeightMap;
import com.willwinder.ugs.nbp.pcb.heightmap.ProbeArea;
import com.willwinder.ugs.nbp.pcb.heightmap.SyntheticSurface;
import com.willwinder.ugs.nbp.pcb.service.PcbAlignmentService.ValidationState;
import com.willwinder.ugs.nbp.pcb.service.PcbHeightMapService.State;
import com.willwinder.universalgcodesender.AbstractController;
import com.willwinder.universalgcodesender.IController;
import com.willwinder.universalgcodesender.firmware.IFirmwareSettings;
import com.willwinder.universalgcodesender.gcode.DefaultCommandCreator;
import com.willwinder.universalgcodesender.gcode.GcodeState;
import com.willwinder.universalgcodesender.gcode.processors.CommandProcessor;
import com.willwinder.universalgcodesender.gcode.processors.CommandProcessorList;
import com.willwinder.universalgcodesender.gcode.processors.LineSplitter;
import com.willwinder.universalgcodesender.gcode.processors.MeshLeveler;
import com.willwinder.universalgcodesender.gcode.util.Code;
import com.willwinder.universalgcodesender.gcode.util.GcodeParserException;
import com.willwinder.universalgcodesender.model.GUIBackend;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UnitUtils.Units;
import com.willwinder.universalgcodesender.services.BackendFileLoader;
import com.willwinder.universalgcodesender.services.jobvalidation.JobValidationException;
import com.willwinder.universalgcodesender.utils.GcodeStreamReader;
import com.willwinder.universalgcodesender.utils.IGcodeStreamReader;
import com.willwinder.universalgcodesender.utils.Settings;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the height map with the real backend and the processors of the application, connected to a
 * controller that is a mock: what the backend would send is what is verified.
 */
public class PcbHeightMapServiceTest {
    private static final RigidTransform2D TRANSFORM = new RigidTransform2D(Math.toRadians(30), 10, 5);
    private static final RigidTransform2D OTHER_TRANSFORM = new RigidTransform2D(Math.toRadians(-2), -3, 4);

    /**
     * Covers all of {@link #PROGRAM} when it is aligned with {@link #TRANSFORM}
     */
    private static final ProbeArea AREA = new ProbeArea(-5, 5, 35, 40);
    private static final double STEP = 5;

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

    private AbstractController controller;
    private GcodeState machineState;
    private GUIBackend backend;
    private PcbAlignmentService alignmentService;
    private PcbHeightMapService service;
    private File source;
    private AlignedGcodeFile alignedFile;
    private final List<AlignmentException> errors = new ArrayList<>();
    private final AtomicInteger notifications = new AtomicInteger();

    @Before
    public void setUp() throws Exception {
        controller = mock(AbstractController.class);
        machineState = new GcodeState();
        machineState.offset = Code.G54;
        backend = new GUIBackend() {
            @Override
            protected IController fetchControllerFromFirmware(String firmware) {
                return controller;
            }
        };
        doReturn(mock(IFirmwareSettings.class)).when(controller).getFirmwareSettings();
        when(controller.isCommOpen()).thenReturn(true);
        when(controller.getCommandCreator()).thenReturn(new DefaultCommandCreator());
        when(controller.getCurrentGcodeState()).thenReturn(machineState);
        backend.applySettings(new Settings());
        backend.connect("GRBL", "/dev/ttyS0", 9600);

        alignmentService = new PcbAlignmentService(backend, new BackendFileLoader(backend), errors::add);
        backend.getJobValidationService().register(new PcbJobValidator(alignmentService, () -> 0));
        service = new PcbHeightMapService(backend, alignmentService);
        service.addListener(notifications::incrementAndGet);

        source = new File(folder.getRoot(), "board.nc");
        Files.write(source.toPath(), PROGRAM, StandardCharsets.UTF_8);
        backend.setGcodeFile(source);
        alignedFile = alignmentService.alignLoadedFile(TRANSFORM, Code.G54);
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

    private static PcbHeightMap heightMap(ProbeArea area, HeightMapBinding binding) throws Exception {
        return new PcbHeightMap(SyntheticSurface.heightMap(area, STEP, SyntheticSurface.BENT_BOARD), binding, Instant.parse("2026-10-11T12:00:00Z"), "synthetic");
    }

    private PcbHeightMap heightMapForLoadedProgram() throws Exception {
        return heightMap(AREA, new HeightMapBinding(TRANSFORM, Code.G54));
    }

    private static double surfaceOffset(double x, double y) {
        return AREA.contains(x, y) ? SyntheticSurface.BENT_BOARD.applyAsDouble(x, y) : 0;
    }

    private void assertProgramIsNotCompensated(RigidTransform2D transform) throws Exception {
        ToolPathValidator.validate(expectedPath(transform), processedPath(), 0.001, 0);
        assertEquals(ValidationState.VALID, alignmentService.getValidationState());
        assertNull(alignmentService.getApproval().orElseThrow().compensationId());
    }

    private void assertCanBeSent() throws Exception {
        backend.send();
        verify(controller, times(1)).queueStream(any());
        verify(controller, times(1)).beginStreaming();
    }

    private void assertCanNotBeSent() throws Exception {
        assertThrows(JobValidationException.class, () -> backend.send());
        verify(controller, never()).queueStream(any());
        verify(controller, never()).beginStreaming();
    }

    private static void assertMessage(String expectedStart, String message) {
        assertTrue(message, message.startsWith(expectedStart));
    }

    @Test
    public void statusShouldBeAbsentWithoutHeightMap() throws Exception {
        assertEquals(State.ABSENT, service.getStatus().state());
        assertEquals(PcbHeightMapService.ERROR_NO_HEIGHT_MAP, assertThrows(HeightMapException.class, () -> service.apply()).getMessage());
        assertProgramIsNotCompensated(TRANSFORM);
    }

    @Test
    public void heightMapForTheAlignmentShouldBeAvailable() throws Exception {
        service.setHeightMap(heightMapForLoadedProgram());

        assertEquals(new PcbHeightMapService.Status(State.AVAILABLE, PcbHeightMapService.MESSAGE_AVAILABLE), service.getStatus());
        assertFalse(service.isApplied());
        assertProgramIsNotCompensated(TRANSFORM);
    }

    @Test
    public void applyShouldMoveTheProgramToTheSurface() throws Exception {
        service.setHeightMap(heightMapForLoadedProgram());

        service.apply();

        List<Position> processed = processedPath();
        ToolPathValidator.validate(expectedPath(TRANSFORM), processed, 0.001, 0, PcbHeightMapServiceTest::surfaceOffset);
        // The surface is not flat, so the program must have been changed
        assertThrows(AlignmentException.class, () -> ToolPathValidator.validate(expectedPath(TRANSFORM), processed, 0.001, 0));
        assertEquals(new PcbHeightMapService.Status(State.APPLIED, PcbHeightMapService.MESSAGE_APPLIED), service.getStatus());
        assertTrue(service.isApplied());
        assertEquals(ValidationState.VALID, alignmentService.getValidationState());
        assertTrue(alignmentService.getApproval().orElseThrow().compensationId().contains(alignedFile.metadata().contentSha256()));
        assertCanBeSent();
    }

    @Test
    public void applyShouldCutAtTheSameDepthBelowTheSurfaceEverywhere() throws Exception {
        service.setHeightMap(heightMapForLoadedProgram());

        service.apply();

        int cuts = 0;
        for (Position position : processedPath()) {
            double height = SyntheticSurface.BENT_BOARD.applyAsDouble(position.getX(), position.getY());
            if (position.getZ() < height) {
                // The surface is bent, so between its measured points it differs a little from the height map
                assertEquals(-0.1, position.getZ() - height, 0.002);
                cuts++;
            }
        }
        assertTrue("Only " + cuts + " positions in the board", cuts > 20);
    }

    @Test
    public void applyShouldSplitMovesToFollowTheSurface() throws Exception {
        service.setHeightMap(heightMapForLoadedProgram());

        service.apply();

        List<Position> processed = processedPath();
        ToolPathValidator.validateMoveLength(processed, STEP / 4 + 0.001, (x, y) -> true);
        assertTrue(processed.size() > 4 * expectedPath(TRANSFORM).size());
    }

    @Test
    public void removeShouldRestoreTheAlignedProgramAndKeepTheHeightMap() throws Exception {
        PcbHeightMap heightMap = heightMapForLoadedProgram();
        service.setHeightMap(heightMap);
        service.apply();

        service.remove();

        assertProgramIsNotCompensated(TRANSFORM);
        assertFalse(service.isApplied());
        assertEquals(heightMap, service.getHeightMap().orElseThrow());
        assertEquals(State.AVAILABLE, service.getStatus().state());
        assertCanBeSent();
    }

    @Test
    public void heightMapShouldBeAppliedAgainAfterItWasRemoved() throws Exception {
        service.setHeightMap(heightMapForLoadedProgram());
        service.apply();
        service.remove();

        service.apply();

        ToolPathValidator.validate(expectedPath(TRANSFORM), processedPath(), 0.001, 0, PcbHeightMapServiceTest::surfaceOffset);
        assertEquals(State.APPLIED, service.getStatus().state());
    }

    @Test
    public void heightMapShouldNotBeAppliedTwice() throws Exception {
        service.setHeightMap(heightMapForLoadedProgram());
        service.apply();

        assertEquals(PcbHeightMapService.ERROR_APPLIED, assertThrows(HeightMapException.class, () -> service.apply()).getMessage());

        ToolPathValidator.validate(expectedPath(TRANSFORM), processedPath(), 0.001, 0, PcbHeightMapServiceTest::surfaceOffset);
        assertEquals(State.APPLIED, service.getStatus().state());
    }

    @Test
    public void removeShouldFailWhenNothingIsApplied() throws Exception {
        service.setHeightMap(heightMapForLoadedProgram());

        assertEquals(PcbHeightMapService.ERROR_NOT_APPLIED, assertThrows(HeightMapException.class, () -> service.remove()).getMessage());
        assertProgramIsNotCompensated(TRANSFORM);
    }

    @Test
    public void heightMapOfSurfaceScannerShouldNotBeAppliedUntilItIsAssociated() throws Exception {
        StringBuilder output = new StringBuilder();
        for (Position point : SyntheticSurface.measure(AREA, STEP, SyntheticSurface.BENT_BOARD)) {
            output.append(point.getX()).append(" ").append(point.getY()).append(" ").append(point.getZ()).append("\n");
        }
        File file = new File(folder.getRoot(), "scan.xyz");
        Files.writeString(file.toPath(), output);

        service.load(file, Units.MM);

        assertEquals(new PcbHeightMapService.Status(State.INCOMPATIBLE, PcbHeightMapService.ERROR_NOT_ASSOCIATED), service.getStatus());
        assertEquals(PcbHeightMapService.ERROR_NOT_ASSOCIATED, assertThrows(HeightMapException.class, () -> service.apply()).getMessage());
        assertProgramIsNotCompensated(TRANSFORM);

        PcbHeightMap associated = service.associateWithLoadedProgram();

        assertEquals(new HeightMapBinding(TRANSFORM, Code.G54), associated.binding());
        assertEquals(State.AVAILABLE, service.getStatus().state());
        service.apply();
        assertEquals(State.APPLIED, service.getStatus().state());
    }

    @Test
    public void associatedHeightMapShouldBeTheSameWhenItIsSavedAndLoaded() throws Exception {
        service.setHeightMap(heightMap(AREA, null));
        service.associateWithLoadedProgram();
        File file = new File(folder.getRoot(), "board.pcbmap");
        service.save(file);
        service.discard();
        assertEquals(State.ABSENT, service.getStatus().state());

        service.load(file, Units.INCH);

        assertEquals(State.AVAILABLE, service.getStatus().state());
        assertEquals(HeightMapFile.read(file, Units.MM), service.getHeightMap().orElseThrow());
    }

    @Test
    public void heightMapOfAnEarlierAlignmentShouldBeIncompatible() throws Exception {
        service.setHeightMap(heightMap(AREA, new HeightMapBinding(OTHER_TRANSFORM, Code.G54)));

        PcbHeightMapService.Status status = service.getStatus();

        assertEquals(State.INCOMPATIBLE, status.state());
        assertMessage("The height map was measured for another alignment of the board", status.message());
        assertEquals(status.message(), assertThrows(HeightMapException.class, () -> service.apply()).getMessage());
        assertProgramIsNotCompensated(TRANSFORM);
        assertCanBeSent();
    }

    @Test
    public void heightMapOfAnotherWorkCoordinateSystemShouldBeIncompatible() throws Exception {
        service.setHeightMap(heightMap(AREA, new HeightMapBinding(TRANSFORM, Code.G55)));

        PcbHeightMapService.Status status = service.getStatus();

        assertEquals(new PcbHeightMapService.Status(State.INCOMPATIBLE, "The height map was measured in G55 and the program is aligned in G54."), status);
        assertThrows(HeightMapException.class, () -> service.apply());
        assertProgramIsNotCompensated(TRANSFORM);
    }

    @Test
    public void heightMapShouldBeIncompatibleWhileTheMachineUsesAnotherWorkCoordinateSystem() throws Exception {
        service.setHeightMap(heightMapForLoadedProgram());
        machineState.offset = Code.G55;

        assertEquals(new PcbHeightMapService.Status(State.INCOMPATIBLE, "The machine is using G55 and the height map was measured in G54."), service.getStatus());
        assertThrows(HeightMapException.class, () -> service.apply());

        machineState.offset = Code.G54;
        assertEquals(State.AVAILABLE, service.getStatus().state());
    }

    @Test
    public void appliedProgramShouldNotBeSentWhenTheMachineChangesWorkCoordinateSystem() throws Exception {
        service.setHeightMap(heightMapForLoadedProgram());
        service.apply();

        machineState.offset = Code.G55;

        assertCanNotBeSent();
    }

    @Test
    public void aligningAgainShouldRemoveTheCompensationAndMakeTheHeightMapIncompatible() throws Exception {
        PcbHeightMap heightMap = heightMapForLoadedProgram();
        service.setHeightMap(heightMap);
        service.apply();

        alignmentService.alignLoadedFile(OTHER_TRANSFORM, Code.G54);

        assertFalse(service.isApplied());
        assertProgramIsNotCompensated(OTHER_TRANSFORM);
        assertEquals(State.INCOMPATIBLE, service.getStatus().state());
        assertMessage("The height map was measured for another alignment of the board", service.getStatus().message());
        assertEquals(heightMap, service.getHeightMap().orElseThrow());
        assertThrows(HeightMapException.class, () -> service.apply());
        assertProgramIsNotCompensated(OTHER_TRANSFORM);
    }

    @Test
    public void heightMapShouldBeAvailableAgainWhenTheBoardIsAlignedAsItWasMeasured() throws Exception {
        service.setHeightMap(heightMapForLoadedProgram());
        alignmentService.alignLoadedFile(OTHER_TRANSFORM, Code.G54);

        alignmentService.alignLoadedFile(TRANSFORM, Code.G54);

        assertEquals(State.AVAILABLE, service.getStatus().state());
        assertProgramIsNotCompensated(TRANSFORM);
    }

    @Test
    public void loadingTheAlignedProgramAgainShouldNotApplyTheHeightMapSilently() throws Exception {
        service.setHeightMap(heightMapForLoadedProgram());
        service.apply();

        alignmentService.load(alignedFile);

        assertFalse(service.isApplied());
        assertEquals(State.AVAILABLE, service.getStatus().state());
        assertProgramIsNotCompensated(TRANSFORM);
    }

    @Test
    public void loadingAnotherProgramShouldKeepTheHeightMapWithoutApplyingIt() throws Exception {
        PcbHeightMap heightMap = heightMapForLoadedProgram();
        service.setHeightMap(heightMap);
        service.apply();

        backend.setGcodeFile(source);

        assertFalse(service.isApplied());
        assertEquals(new PcbHeightMapService.Status(State.INCOMPATIBLE, PcbHeightMapService.ERROR_NOT_ALIGNED), service.getStatus());
        assertEquals(heightMap, service.getHeightMap().orElseThrow());
        ToolPathValidator.validate(expectedPath(RigidTransform2D.identity()), processedPath(), 0.001, 0);
        assertThrows(HeightMapException.class, () -> service.apply());
    }

    @Test
    public void closingTheProgramShouldKeepTheHeightMap() throws Exception {
        PcbHeightMap heightMap = heightMapForLoadedProgram();
        service.setHeightMap(heightMap);
        service.apply();

        backend.unsetGcodeFile();

        assertFalse(service.isApplied());
        assertEquals(new PcbHeightMapService.Status(State.INCOMPATIBLE, PcbHeightMapService.ERROR_NO_PROGRAM), service.getStatus());
        assertEquals(heightMap, service.getHeightMap().orElseThrow());
    }

    @Test
    public void heightMapThatDoesNotCoverAllCutsShouldBeIncompatible() throws Exception {
        ProbeArea small = new ProbeArea(-5, 5, 15, 40);
        service.setHeightMap(heightMap(small, new HeightMapBinding(TRANSFORM, Code.G54)));

        PcbHeightMapService.Status status = service.getStatus();

        assertEquals(State.INCOMPATIBLE, status.state());
        assertMessage(PcbHeightMapService.ERROR_COVERAGE, status.message());
        assertFalse(service.findUncoveredCuts().isEmpty());
        assertTrue(service.findUncoveredCuts().stream().allMatch(position -> position.getZ() <= 0 && !small.contains(position.getX(), position.getY())));
        assertThrows(HeightMapException.class, () -> service.apply());
        assertProgramIsNotCompensated(TRANSFORM);
    }

    @Test
    public void heightMapShouldOnlyNeedToCoverWhatIsCut() throws Exception {
        ProbeArea cuts = service.getAreaToMeasure(0.5).orElseThrow();
        // The program moves above the board to where it starts cutting, which no height is needed for
        backend.unsetGcodeFile();
        List<String> program = new ArrayList<>(PROGRAM);
        program.add(2, "G0 X-40 Y-40");
        Files.write(source.toPath(), program, StandardCharsets.UTF_8);
        backend.setGcodeFile(source);
        alignmentService.alignLoadedFile(TRANSFORM, Code.G54);
        service.setHeightMap(heightMap(cuts, new HeightMapBinding(TRANSFORM, Code.G54)));

        assertEquals(cuts, service.getAreaToMeasure(0.5).orElseThrow());
        assertEquals(State.AVAILABLE, service.getStatus().state());
        service.apply();

        assertEquals(State.APPLIED, service.getStatus().state());
        assertCanBeSent();
    }

    @Test
    public void applyShouldBeUndoneWhenTheProgramCanNotBeProcessed() throws Exception {
        AtomicBoolean isFailing = new AtomicBoolean();
        backend.applyCommandProcessor(new CommandProcessor() {
            @Override
            public List<String> processCommand(String command, GcodeState state) throws GcodeParserException {
                if (isFailing.get() && command.contains("F100")) {
                    throw new GcodeParserException("Can not process " + command);
                }
                return List.of(command);
            }

            @Override
            public String getHelp() {
                return "";
            }
        });
        alignmentService.validateProcessedFile(0);
        PcbHeightMap heightMap = heightMapForLoadedProgram();
        service.setHeightMap(heightMap);
        isFailing.set(true);

        HeightMapException exception = assertThrows(HeightMapException.class, () -> service.apply());

        assertMessage(PcbHeightMapService.ERROR_APPLY, exception.getMessage());
        assertFalse(service.isApplied());
        assertEquals(heightMap, service.getHeightMap().orElseThrow());
        assertNull(backend.getProcessedGcodeFile());
        assertFalse(alignmentService.getValidationState() == ValidationState.VALID);
        assertFalse(backend.canSend());

        // Once the program can be processed again, it is the aligned program without the height map
        isFailing.set(false);
        alignmentService.load(alignedFile);
        assertProgramIsNotCompensated(TRANSFORM);
    }

    @Test
    public void applyShouldBeRefusedWhenTheAlignedProgramIsNotValidated() throws Exception {
        service.setHeightMap(heightMapForLoadedProgram());
        backend.applyCommandProcessor(new LineSplitter(1) {
            @Override
            public List<String> processCommand(String command, GcodeState state) throws GcodeParserException {
                return command.contains("X") ? List.of() : List.of(command);
            }
        });
        assertFalse(alignmentService.getValidationState() == ValidationState.VALID);

        assertMessage(PcbHeightMapService.ERROR_PROGRAM_NOT_VALIDATED, assertThrows(HeightMapException.class, () -> service.apply()).getMessage());

        assertFalse(service.isApplied());
        assertCanNotBeSent();
    }

    @Test
    public void heightMapShouldBeAppliedToAnAlignedProgramThatWasOpenedAsAnyOtherFile() throws Exception {
        // As when the application is started again: nothing remembers that the program was aligned here
        PcbAlignmentService newAlignmentService = new PcbAlignmentService(backend, new BackendFileLoader(backend), errors::add);
        PcbHeightMapService newService = new PcbHeightMapService(backend, newAlignmentService);
        newService.setHeightMap(heightMapForLoadedProgram());
        backend.setGcodeFile(alignedFile.file());
        assertEquals(ValidationState.NOT_VALIDATED, newAlignmentService.getValidationState());
        assertEquals(State.AVAILABLE, newService.getStatus().state());

        newService.apply();

        ToolPathValidator.validate(expectedPath(TRANSFORM), processedPath(), 0.001, 0, PcbHeightMapServiceTest::surfaceOffset);
        assertEquals(State.APPLIED, newService.getStatus().state());
        assertEquals(ValidationState.VALID, newAlignmentService.getValidationState());
    }

    @Test
    public void programShouldNotBeSentWhenAnotherHeightMapIsAppliedOnTopOfIt() throws Exception {
        service.setHeightMap(heightMapForLoadedProgram());
        service.apply();

        // The auto leveler of the application applies its own height map to the same program
        CommandProcessorList autoLeveler = new CommandProcessorList();
        autoLeveler.add(new MeshLeveler(0, SyntheticSurface.heightMap(new ProbeArea(-60, -60, 60, 60), 10, (x, y) -> 0.2 + 0.001 * x).toMesh()));
        backend.applyCommandProcessor(autoLeveler);

        assertEquals(new PcbHeightMapService.Status(State.INCOMPATIBLE, PcbHeightMapService.ERROR_APPLIED_NOT_VALID), service.getStatus());
        assertFalse(alignmentService.getValidationState() == ValidationState.VALID);
        assertCanNotBeSent();

        // Removing the compensation of the PCB tools does not make the program valid either
        assertMessage(PcbHeightMapService.ERROR_REMOVE, assertThrows(HeightMapException.class, () -> service.remove()).getMessage());
        assertFalse(service.isApplied());
        assertCanNotBeSent();
    }

    @Test
    public void heightMapShouldNotBeReplacedOrDiscardedWhileItIsApplied() throws Exception {
        PcbHeightMap heightMap = heightMapForLoadedProgram();
        service.setHeightMap(heightMap);
        File file = new File(folder.getRoot(), "board.pcbmap");
        service.save(file);
        service.apply();

        assertEquals(PcbHeightMapService.ERROR_APPLIED, assertThrows(HeightMapException.class, () -> service.load(file, Units.MM)).getMessage());
        assertEquals(PcbHeightMapService.ERROR_APPLIED, assertThrows(HeightMapException.class, () -> service.setHeightMap(heightMap(AREA, null))).getMessage());
        assertEquals(PcbHeightMapService.ERROR_APPLIED, assertThrows(HeightMapException.class, () -> service.discard()).getMessage());
        assertEquals(PcbHeightMapService.ERROR_APPLIED, assertThrows(HeightMapException.class, () -> service.associateWithLoadedProgram()).getMessage());

        assertEquals(heightMap, service.getHeightMap().orElseThrow());
        assertEquals(State.APPLIED, service.getStatus().state());
    }

    @Test
    public void incompleteFileShouldNotReplaceTheHeightMap() throws Exception {
        PcbHeightMap heightMap = heightMapForLoadedProgram();
        service.setHeightMap(heightMap);
        File file = new File(folder.getRoot(), "board.pcbmap");
        service.save(file);
        List<String> lines = Files.readAllLines(file.toPath());
        Files.write(file.toPath(), lines.subList(0, lines.size() / 2));

        assertThrows(HeightMapException.class, () -> service.load(file, Units.MM));

        assertEquals(heightMap, service.getHeightMap().orElseThrow());
        assertEquals(State.AVAILABLE, service.getStatus().state());
    }

    @Test
    public void heightMapShouldNotBeChangedWhileTheProgramIsBeingSent() throws Exception {
        service.setHeightMap(heightMapForLoadedProgram());
        when(controller.isStreaming()).thenReturn(true);

        assertEquals(PcbHeightMapService.ERROR_SENDING, assertThrows(HeightMapException.class, () -> service.apply()).getMessage());
        assertFalse(service.isApplied());
    }

    @Test
    public void listenersShouldBeToldWhenTheHeightMapOrTheProgramChanges() throws Exception {
        notifications.set(0);
        service.setHeightMap(heightMapForLoadedProgram());
        assertEquals(1, notifications.get());

        service.apply();
        assertTrue(notifications.get() > 1);

        int before = notifications.get();
        backend.setGcodeFile(source);
        assertTrue(notifications.get() > before);
    }
}
