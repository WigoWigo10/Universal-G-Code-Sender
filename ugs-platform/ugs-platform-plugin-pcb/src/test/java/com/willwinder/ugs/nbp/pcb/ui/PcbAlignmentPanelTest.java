package com.willwinder.ugs.nbp.pcb.ui;

import com.willwinder.ugs.nbp.pcb.gcode.AlignmentMetadata;
import com.willwinder.ugs.nbp.pcb.service.PcbAlignmentService;
import com.willwinder.ugs.nbp.pcb.service.PcbAlignmentService.ValidationState;
import com.willwinder.ugs.nbp.pcb.ui.AlignmentModel.Status;
import com.willwinder.universalgcodesender.AbstractController;
import com.willwinder.universalgcodesender.IController;
import com.willwinder.universalgcodesender.firmware.IFirmwareSettings;
import com.willwinder.universalgcodesender.gcode.DefaultCommandCreator;
import com.willwinder.universalgcodesender.gcode.GcodeState;
import com.willwinder.universalgcodesender.gcode.processors.ArcExpander;
import com.willwinder.universalgcodesender.gcode.processors.CommandProcessorList;
import com.willwinder.universalgcodesender.gcode.processors.LineSplitter;
import com.willwinder.universalgcodesender.gcode.processors.MeshLeveler;
import com.willwinder.universalgcodesender.gcode.util.Code;
import com.willwinder.universalgcodesender.listeners.ControllerState;
import com.willwinder.universalgcodesender.listeners.ControllerStatus;
import com.willwinder.universalgcodesender.model.GUIBackend;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UnitUtils.Units;
import com.willwinder.universalgcodesender.services.BackendFileLoader;
import com.willwinder.universalgcodesender.utils.Settings;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import javax.swing.SwingUtilities;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Tests the alignment window without the platform around it: the panel is used as the operator
 * would, with a backend that has a simulated controller.
 */
public class PcbAlignmentPanelTest {
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
    private PcbAlignmentService service;
    private PcbAlignmentPanel panel;
    private File source;
    private File alignedFile;
    private final List<String> errors = new ArrayList<>();
    private final List<String> confirmations = new ArrayList<>();
    private boolean confirm = true;

    private interface UserInterfaceAction {
        void run() throws Exception;
    }

    @Before
    public void setUp() throws Exception {
        controller = mock(AbstractController.class);
        machineState = new GcodeState();
        machineState.offset = Code.G54;
        backend = createBackend(controller);
        backend.connect("GRBL", "/dev/ttyS0", 9600);
        when(controller.getCurrentGcodeState()).thenReturn(machineState);
        setMachinePosition(ControllerState.IDLE, new Position(0, 0, 0, Units.MM));

        service = new PcbAlignmentService(backend, new BackendFileLoader(backend), e -> errors.add(e.getMessage()));
        source = new File(folder.getRoot(), "board.nc");
        Files.write(source.toPath(), PROGRAM, StandardCharsets.UTF_8);
        alignedFile = new File(folder.getRoot(), "board_aligned.nc");
        backend.setGcodeFile(source);

        panel = createPanel(backend, Optional.of(service));
    }

    private static GUIBackend createBackend(AbstractController controller) throws Exception {
        GUIBackend backend = new GUIBackend() {
            @Override
            protected IController fetchControllerFromFirmware(String firmware) {
                return controller;
            }
        };
        doReturn(mock(IFirmwareSettings.class)).when(controller).getFirmwareSettings();
        when(controller.isCommOpen()).thenReturn(true);
        when(controller.getCommandCreator()).thenReturn(new DefaultCommandCreator());
        backend.applySettings(new Settings());
        return backend;
    }

    /**
     * Creates the panel as the window does, except that what the window does in the background is done at once
     */
    private PcbAlignmentPanel createPanel(GUIBackend panelBackend, Optional<PcbAlignmentService> panelService) throws Exception {
        PcbAlignmentPanel[] result = new PcbAlignmentPanel[1];
        onUserInterface(() -> {
            result[0] = new PcbAlignmentPanel(panelBackend, () -> panelService, Runnable::run, message -> {
                confirmations.add(message);
                return confirm;
            }, errors::add);
            panelBackend.addUGSEventListener(result[0]);
        });
        return result[0];
    }

    private void setMachinePosition(ControllerState state, Position workPosition) {
        when(controller.getControllerStatus()).thenReturn(new ControllerStatus(state, new Position(-100, -100, -10, Units.MM), workPosition));
    }

    private static void onUserInterface(UserInterfaceAction action) throws Exception {
        Exception[] failure = new Exception[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                action.run();
            } catch (Exception e) {
                failure[0] = e;
            }
        });
        if (failure[0] != null) {
            throw failure[0];
        }
    }

    /**
     * Waits for what the panel does in response to events of the backend
     */
    private static void waitForUserInterface() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
        });
    }

    private void enter(int row, String nominalX, String nominalY, String measuredX, String measuredY) throws Exception {
        onUserInterface(() -> {
            panel.getTable().setValueAt(nominalX, row, AlignmentTableModel.COLUMN_NOMINAL_X);
            panel.getTable().setValueAt(nominalY, row, AlignmentTableModel.COLUMN_NOMINAL_Y);
            panel.getTable().setValueAt(measuredX, row, AlignmentTableModel.COLUMN_MEASURED_X);
            panel.getTable().setValueAt(measuredY, row, AlignmentTableModel.COLUMN_MEASURED_Y);
        });
    }

    /**
     * Two holes that are measured where a board that is moved by (10, 5) has them
     */
    private void enterValidAlignment() throws Exception {
        enter(0, "5", "5", "15", "10");
        enter(1, "25", "20", "35", "25");
    }

    private Status status() {
        return panel.getModel().evaluate().status();
    }

    private void assertNothingWasSentToTheController() {
        for (Invocation invocation : Mockito.mockingDetails(controller).getInvocations()) {
            String method = invocation.getMethod().getName();
            assertTrue("The controller was asked to " + method,
                    method.startsWith("get") || method.startsWith("is") || method.startsWith("set")
                            || method.startsWith("add") || method.startsWith("remove") || method.equals("openCommPort"));
        }
    }

    // The window when it is opened

    @Test
    public void panelShouldDescribeTheLoadedProgramAndHaveNothingToApply() {
        assertEquals("board.nc (original program)", panel.getProgramText());
        assertEquals(2, panel.getTable().getRowCount());
        assertEquals(Status.INCOMPLETE.getDescription(), panel.getStatusText());
        assertEquals("-", panel.getRotationText());
        assertFalse(panel.isApplyEnabled());
        assertTrue(panel.getValidationText(), panel.getValidationText().startsWith("This program is not aligned"));
    }

    @Test
    public void panelWithoutProgramShouldSaySo() throws Exception {
        GUIBackend emptyBackend = createBackend(mock(AbstractController.class));

        PcbAlignmentPanel emptyPanel = createPanel(emptyBackend, Optional.of(service));

        assertEquals(PcbAlignmentPanel.NO_PROGRAM, emptyPanel.getProgramText());
        assertEquals(PcbAlignmentPanel.NOT_CONNECTED, emptyPanel.getMachineText());
        assertFalse(emptyPanel.isApplyEnabled());
    }

    // Entering positions by hand

    @Test
    public void positionsEnteredByHandShouldGiveAlignment() throws Exception {
        enterValidAlignment();

        assertEquals(Status.VALID, status());
        assertEquals(Status.VALID.getDescription(), panel.getStatusText());
        assertEquals("0°", panel.getRotationText());
        assertEquals("X10 Y5 mm", panel.getTranslationText());
        assertEquals("0", panel.getTable().getValueAt(0, AlignmentTableModel.COLUMN_RESIDUAL));
        assertTrue(panel.isApplyEnabled());
    }

    @Test
    public void positionsShouldBeEnteredWithDecimalCommaOrPoint() throws Exception {
        enter(0, "5,5", "5.25", "15,5", "10.25");

        assertEquals(5.5, panel.getModel().getPoints().get(0).nominalX(), 0);
        assertEquals(5.25, panel.getModel().getPoints().get(0).nominalY(), 0);
        assertEquals("15.5", panel.getTable().getValueAt(0, AlignmentTableModel.COLUMN_MEASURED_X));
    }

    @Test
    public void alignmentShouldBeUpdatedWhenAPositionIsEdited() throws Exception {
        enterValidAlignment();

        onUserInterface(() -> panel.getTable().setValueAt("36", 1, AlignmentTableModel.COLUMN_MEASURED_X));

        assertEquals(Status.DISTANCE_MISMATCH, status());
        assertEquals(Status.DISTANCE_MISMATCH.getDescription(), panel.getStatusText());
        assertFalse(panel.isApplyEnabled());
        assertFalse(panel.getRotationText().equals("0°"));
    }

    @Test
    public void emptyAndInvalidEntriesShouldNotGiveAlignment() throws Exception {
        enterValidAlignment();

        onUserInterface(() -> panel.getTable().setValueAt("", 0, AlignmentTableModel.COLUMN_MEASURED_Y));
        assertEquals(Status.INCOMPLETE, status());
        assertNull(panel.getModel().getPoints().get(0).measuredY());

        onUserInterface(() -> panel.getTable().setValueAt("abc", 0, AlignmentTableModel.COLUMN_MEASURED_Y));
        assertEquals(Status.INVALID_COORDINATES, status());
        assertEquals(AlignmentTableModel.INVALID, panel.getTable().getValueAt(0, AlignmentTableModel.COLUMN_MEASURED_Y));
        assertFalse(panel.isApplyEnabled());
    }

    // Capturing positions from the machine

    @Test
    public void captureShouldSetMeasuredPositionToWorkPositionOfTheMachine() throws Exception {
        setMachinePosition(ControllerState.IDLE, new Position(15.125, 10.5, 2, Units.MM));

        onUserInterface(() -> panel.capturePoint(0));

        assertEquals(15.125, panel.getModel().getPoints().get(0).measuredX(), 1e-9);
        assertEquals(10.5, panel.getModel().getPoints().get(0).measuredY(), 1e-9);
        assertTrue(panel.getModel().getPoints().get(0).measuredOnMachine());
        assertNull(panel.getModel().getPoints().get(1).measuredX());
        assertEquals(Code.G54, panel.getModel().getWorkCoordinateSystem());
        assertTrue(errors.isEmpty());
        assertNothingWasSentToTheController();
    }

    @Test
    public void captureShouldConvertPositionOfMachineThatReportsInches() throws Exception {
        setMachinePosition(ControllerState.IDLE, new Position(1, 2, 0, Units.INCH));

        onUserInterface(() -> panel.capturePoint(1));

        assertEquals(25.4, panel.getModel().getPoints().get(1).measuredX(), 1e-9);
        assertEquals(50.8, panel.getModel().getPoints().get(1).measuredY(), 1e-9);
    }

    @Test
    public void captureOfEveryPointShouldGiveAlignmentInTheWorkCoordinateSystemOfTheMachine() throws Exception {
        machineState.offset = Code.G55;
        enter(0, "5", "5", "", "");
        enter(1, "25", "20", "", "");

        setMachinePosition(ControllerState.IDLE, new Position(15, 10, 2, Units.MM));
        onUserInterface(() -> panel.capturePoint(0));
        setMachinePosition(ControllerState.IDLE, new Position(35, 25, 2, Units.MM));
        onUserInterface(() -> panel.capturePoint(1));

        assertEquals(Status.VALID, status());
        assertEquals(Code.G55, panel.getModel().getWorkCoordinateSystem());
        assertEquals("X10 Y5 mm", panel.getTranslationText());
        assertNothingWasSentToTheController();
    }

    @Test
    public void captureShouldFailWhenNotConnected() throws Exception {
        GUIBackend disconnectedBackend = createBackend(mock(AbstractController.class));
        disconnectedBackend.setGcodeFile(source);
        PcbAlignmentPanel disconnectedPanel = createPanel(disconnectedBackend, Optional.of(service));

        onUserInterface(() -> disconnectedPanel.capturePoint(0));

        assertEquals(List.of(PositionCapture.ERROR_NOT_CONNECTED), errors);
        assertNull(disconnectedPanel.getModel().getPoints().get(0).measuredX());
    }

    @Test
    public void captureShouldFailWhileMachineIsMoving() throws Exception {
        setMachinePosition(ControllerState.JOG, new Position(15, 10, 2, Units.MM));

        onUserInterface(() -> panel.capturePoint(0));

        assertEquals(List.of(PositionCapture.ERROR_NOT_IDLE + ControllerState.JOG), errors);
        assertNull(panel.getModel().getPoints().get(0).measuredX());
    }

    @Test
    public void captureShouldFailWhenWorkCoordinateSystemIsNotKnown() throws Exception {
        machineState.offset = null;

        onUserInterface(() -> panel.capturePoint(0));

        assertEquals(List.of(PositionCapture.ERROR_WORK_COORDINATE_SYSTEM_UNKNOWN), errors);
        assertNull(panel.getModel().getPoints().get(0).measuredX());
    }

    @Test
    public void captureShouldFailWhenPositionIsNotKnown() throws Exception {
        setMachinePosition(ControllerState.IDLE, new Position(Double.NaN, 10, 2, Units.MM));

        onUserInterface(() -> panel.capturePoint(0));

        assertEquals(List.of(PositionCapture.ERROR_POSITION_UNKNOWN), errors);
        assertNull(panel.getModel().getPoints().get(0).measuredX());
    }

    @Test
    public void captureInAnotherWorkCoordinateSystemShouldBeRejected() throws Exception {
        setMachinePosition(ControllerState.IDLE, new Position(15, 10, 2, Units.MM));
        onUserInterface(() -> panel.capturePoint(0));

        machineState.offset = Code.G55;
        setMachinePosition(ControllerState.IDLE, new Position(35, 25, 2, Units.MM));
        onUserInterface(() -> panel.capturePoint(1));

        assertEquals(1, errors.size());
        assertTrue(errors.get(0), errors.get(0).startsWith(AlignmentModel.ERROR_MIXED_WORK_COORDINATE_SYSTEMS));
        assertNull(panel.getModel().getPoints().get(1).measuredX());
        assertEquals(Code.G54, panel.getModel().getWorkCoordinateSystem());
    }

    @Test
    public void workCoordinateSystemShouldNotBeChangedByHandAfterCapture() throws Exception {
        onUserInterface(() -> panel.capturePoint(0));

        onUserInterface(() -> {
            try {
                panel.getModel().setWorkCoordinateSystem(Code.G56);
            } catch (IllegalStateException e) {
                errors.add(e.getMessage());
            }
        });

        assertEquals(List.of(AlignmentModel.ERROR_WORK_COORDINATE_SYSTEM_IN_USE), errors);
        assertEquals(Code.G54, panel.getModel().getWorkCoordinateSystem());
    }

    @Test
    public void machineLabelShouldShowWorkPositionAndWorkCoordinateSystem() throws Exception {
        machineState.offset = Code.G55;
        setMachinePosition(ControllerState.IDLE, new Position(15.5, 10, 2, Units.MM));

        onUserInterface(() -> panel.refreshProgram());

        assertEquals("Machine: X15.5 Y10 (G55)", panel.getMachineText());
    }

    // Several points and errors in the geometry

    @Test
    public void pointsShouldBeAddedAndRemovedButNotBelowTwo() throws Exception {
        onUserInterface(() -> {
            panel.getModel().addPoint();
            panel.getModel().addPoint();
        });
        assertEquals(4, panel.getTable().getRowCount());

        onUserInterface(() -> {
            panel.getModel().removePoint(3);
            panel.getModel().removePoint(2);
        });
        assertEquals(2, panel.getTable().getRowCount());
    }

    @Test
    public void fourPointsShouldGiveAlignmentWithResidualForEachPoint() throws Exception {
        onUserInterface(() -> {
            panel.getModel().addPoint();
            panel.getModel().addPoint();
        });
        enter(0, "0", "0", "10", "5");
        enter(1, "80", "0", "90", "5");
        enter(2, "80", "92", "90", "97.04");
        enter(3, "0", "92", "10", "97");

        assertEquals(Status.VALID, status());
        for (int row = 0; row < 4; row++) {
            assertFalse(panel.getTable().getValueAt(row, AlignmentTableModel.COLUMN_RESIDUAL).toString().isEmpty());
        }
        assertTrue(panel.isApplyEnabled());
    }

    @Test
    public void errorsInTheGeometryShouldBeToldApart() throws Exception {
        enter(0, "5", "5", "15", "10");
        enter(1, "5", "5", "15", "10");
        assertEquals(Status.COINCIDENT_POINTS.getDescription(), panel.getStatusText());

        enter(1, "7", "5", "17", "10");
        assertEquals(Status.POINTS_TOO_CLOSE.getDescription(), panel.getStatusText());

        enter(1, "85", "5", "95.6", "10");
        assertEquals(Status.DISTANCE_MISMATCH.getDescription(), panel.getStatusText());

        onUserInterface(() -> panel.getModel().addPoint());
        enter(1, "85", "5", "95", "10");
        enter(2, "45", "65", "55.3", "70");
        onUserInterface(() -> panel.setCriteriaText("0.05", "1", "10"));
        assertEquals(Status.RESIDUAL_TOO_LARGE.getDescription(), panel.getStatusText());
        assertFalse(panel.isApplyEnabled());
    }

    @Test
    public void stricterCriteriaShouldMakeAlignmentUnacceptable() throws Exception {
        enter(0, "5", "5", "15", "10");
        enter(1, "85", "5", "95.06", "10");
        assertEquals(Status.VALID, status());

        onUserInterface(() -> panel.setCriteriaText("0.01", "0.2", "10"));

        assertEquals(Status.RESIDUAL_TOO_LARGE, status());
        assertFalse(panel.isApplyEnabled());
    }

    @Test
    public void criteriaThatAreNotNumbersShouldNotAcceptAnything() throws Exception {
        enterValidAlignment();

        onUserInterface(() -> panel.setCriteriaText("", "0.2", "10"));

        assertEquals(Status.INVALID_CRITERIA, status());
        assertFalse(panel.isApplyEnabled());
    }

    // Applying the alignment

    @Test
    public void applyShouldAskForConfirmationWithWhatWillBeDone() throws Exception {
        enterValidAlignment();

        onUserInterface(() -> panel.applyAlignment());

        assertEquals(1, confirmations.size());
        String confirmation = confirmations.get(0);
        assertTrue(confirmation, confirmation.contains("Original program: " + source.getAbsolutePath()));
        assertTrue(confirmation, confirmation.contains("Aligned program: " + alignedFile.getAbsolutePath()));
        assertTrue(confirmation, confirmation.contains("Work coordinate system: G54"));
        assertTrue(confirmation, confirmation.contains("Rotation: 0°"));
        assertTrue(confirmation, confirmation.contains("Translation: X10 Y5 mm"));
        assertTrue(confirmation, confirmation.contains("Largest residual: 0 mm"));
        assertTrue(confirmation, confirmation.contains("Scale error: 0 %"));
    }

    @Test
    public void applyShouldLoadAlignedProgramAndKeepTheOriginal() throws Exception {
        byte[] original = Files.readAllBytes(source.toPath());
        backend.getSettings().getAutoLevelSettings().setApplyToGcode(true);
        enterValidAlignment();

        onUserInterface(() -> panel.applyAlignment());
        waitForUserInterface();

        assertTrue(errors.toString(), errors.isEmpty());
        assertEquals(alignedFile, backend.getGcodeFile());
        assertArrayEquals(original, Files.readAllBytes(source.toPath()));
        AlignmentMetadata metadata = AlignmentMetadata.read(alignedFile).orElseThrow();
        assertEquals(10, metadata.transform().translationX(), 1e-9);
        assertEquals(5, metadata.transform().translationY(), 1e-9);
        assertEquals(Code.G54, metadata.workCoordinateSystem());
        assertFalse(backend.getSettings().getAutoLevelSettings().getApplyToGcode());
        assertEquals(ValidationState.VALID, service.getValidationState());
        assertTrue(panel.getValidationText(), panel.getValidationText().startsWith("VALIDATED"));
        assertTrue(panel.getProgramText(), panel.getProgramText().startsWith("board_aligned.nc (aligned from board.nc in G54"));
        assertNothingWasSentToTheController();
    }

    @Test
    public void applyShouldDoNothingWhenItIsNotConfirmed() throws Exception {
        enterValidAlignment();
        confirm = false;

        onUserInterface(() -> panel.applyAlignment());
        waitForUserInterface();

        assertEquals(1, confirmations.size());
        assertEquals(source, backend.getGcodeFile());
        assertFalse(alignedFile.exists());
    }

    @Test
    public void applyShouldRefuseAlignmentThatIsNotAcceptable() throws Exception {
        enter(0, "5", "5", "15", "10");
        enter(1, "85", "5", "95.6", "10");

        onUserInterface(() -> panel.applyAlignment());

        assertEquals(List.of(PcbAlignmentPanel.ERROR_NOT_VALID + Status.DISTANCE_MISMATCH.getDescription()), errors);
        assertTrue(confirmations.isEmpty());
        assertFalse(alignedFile.exists());
    }

    @Test
    public void applyShouldRefuseWhenTheServiceIsNotAvailable() throws Exception {
        panel = createPanel(backend, Optional.empty());
        enterValidAlignment();

        onUserInterface(() -> panel.applyAlignment());

        assertEquals(List.of(PcbAlignmentPanel.ERROR_NO_SERVICE), errors);
        assertFalse(alignedFile.exists());
    }

    @Test
    public void applyingAgainShouldAlignTheOriginalAndNotAddTheAlignments() throws Exception {
        enterValidAlignment();
        onUserInterface(() -> panel.applyAlignment());
        waitForUserInterface();

        enter(0, "5", "5", "17", "11");
        enter(1, "25", "20", "37", "26");
        onUserInterface(() -> panel.applyAlignment());
        waitForUserInterface();

        assertTrue(errors.toString(), errors.isEmpty());
        assertTrue(confirmations.get(1), confirmations.get(1).contains("Original program: " + source.getAbsolutePath()));
        AlignmentMetadata metadata = AlignmentMetadata.read(alignedFile).orElseThrow();
        assertEquals("board.nc", metadata.sourceName());
        assertEquals(12, metadata.transform().translationX(), 1e-9);
        assertEquals(6, metadata.transform().translationY(), 1e-9);
    }

    @Test
    public void applyShouldRefuseWhenMachineUsesAnotherWorkCoordinateSystem() throws Exception {
        enterValidAlignment();
        machineState.offset = Code.G55;

        onUserInterface(() -> panel.applyAlignment());
        waitForUserInterface();

        assertEquals(1, errors.size());
        assertTrue(errors.get(0), errors.get(0).startsWith(PcbAlignmentService.ERROR_WORK_COORDINATE_SYSTEM));
        assertEquals(source, backend.getGcodeFile());
        assertFalse(alignedFile.exists());
    }

    // The state of the loaded program

    @Test
    public void openingAnotherProgramShouldUpdateTheWindow() throws Exception {
        enterValidAlignment();
        onUserInterface(() -> panel.applyAlignment());
        waitForUserInterface();
        File other = new File(folder.getRoot(), "other.nc");
        Files.write(other.toPath(), PROGRAM, StandardCharsets.UTF_8);

        backend.setGcodeFile(other);
        waitForUserInterface();

        assertEquals("other.nc (original program)", panel.getProgramText());
        assertTrue(panel.getValidationText(), panel.getValidationText().startsWith("This program is not aligned"));
        assertEquals(ValidationState.NOT_VALIDATED, service.getValidationState());
    }

    @Test
    public void unloadingProgramShouldUpdateTheWindow() throws Exception {
        enterValidAlignment();

        backend.unsetGcodeFile();
        waitForUserInterface();

        assertEquals(PcbAlignmentPanel.NO_PROGRAM, panel.getProgramText());
        assertFalse(panel.isApplyEnabled());
    }

    @Test
    public void changedAlignedProgramShouldBeDescribedAsChanged() throws Exception {
        enterValidAlignment();
        onUserInterface(() -> panel.applyAlignment());
        waitForUserInterface();
        List<String> lines = Files.readAllLines(alignedFile.toPath(), StandardCharsets.UTF_8);
        lines.add("G0 X0 Y0");
        Files.write(alignedFile.toPath(), lines, StandardCharsets.UTF_8);

        backend.setGcodeFile(alignedFile);
        waitForUserInterface();

        assertTrue(panel.getProgramText(), panel.getProgramText().contains("has been changed"));
        assertTrue(panel.getValidationText(), panel.getValidationText().startsWith("NOT VALIDATED"));
    }

    @Test
    public void programShouldBeShownAsNotValidatedWhenItIsProcessedAgainAndValidatedAfterValidation() throws Exception {
        enterValidAlignment();
        onUserInterface(() -> panel.applyAlignment());
        waitForUserInterface();

        backend.applyCommandProcessor(createLevelerProcessors());
        waitForUserInterface();
        assertTrue(panel.getValidationText(), panel.getValidationText().startsWith("NOT VALIDATED"));

        onUserInterface(() -> panel.validateProgram());
        waitForUserInterface();
        assertEquals(1, errors.size());
        assertTrue(panel.getValidationText(), panel.getValidationText().startsWith("INVALID"));

        errors.clear();
        onUserInterface(() -> {
            panel.setMaxZChangeText("0,5");
            panel.validateProgram();
        });
        waitForUserInterface();
        assertTrue(errors.toString(), errors.isEmpty());
        assertTrue(panel.getValidationText(), panel.getValidationText().startsWith("VALIDATED"));
        assertEquals(ValidationState.VALID, service.getValidationState());
        assertNothingWasSentToTheController();
    }

    @Test
    public void validateShouldRefuseAcceptedChangeOfZThatIsNotANumber() throws Exception {
        onUserInterface(() -> {
            panel.setMaxZChangeText("a lot");
            panel.validateProgram();
        });

        assertEquals(List.of(PcbAlignmentPanel.ERROR_CRITERIA), errors);
    }

    @Test
    public void previewShouldBeLoadedWithoutErrors() throws Exception {
        enterValidAlignment();

        onUserInterface(() -> panel.loadPreview());
        waitForUserInterface();

        assertTrue(errors.toString(), errors.isEmpty());
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
                mesh[xi][yi] = new Position(x, y, 0.002 * x + 0.003 * y + 0.05, Units.MM);
            }
        }

        CommandProcessorList result = new CommandProcessorList();
        result.add(new ArcExpander(true, 0.1));
        result.add(new LineSplitter(2.5));
        result.add(new MeshLeveler(0, mesh));
        return result;
    }
}
