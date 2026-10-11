package com.willwinder.ugs.nbp.pcb;

import com.willwinder.ugs.nbp.pcb.gcode.AlignedGcodeExporter;
import com.willwinder.ugs.nbp.pcb.gcode.AlignedGcodeFile;
import com.willwinder.ugs.nbp.pcb.gcode.AlignmentException;
import com.willwinder.ugs.nbp.pcb.gcode.AlignmentMetadata;
import com.willwinder.ugs.nbp.pcb.geometry.RigidTransform2D;
import com.willwinder.ugs.nbp.pcb.service.PcbAlignmentService;
import com.willwinder.ugs.nbp.pcb.service.PcbAlignmentService.Approval;
import com.willwinder.ugs.nbp.pcb.service.PcbJobValidator;
import com.willwinder.universalgcodesender.AbstractController;
import com.willwinder.universalgcodesender.IController;
import com.willwinder.universalgcodesender.firmware.IFirmwareSettings;
import com.willwinder.universalgcodesender.gcode.DefaultCommandCreator;
import com.willwinder.universalgcodesender.gcode.GcodeState;
import com.willwinder.universalgcodesender.gcode.processors.ArcExpander;
import com.willwinder.universalgcodesender.gcode.processors.CommandProcessor;
import com.willwinder.universalgcodesender.gcode.processors.CommandProcessorList;
import com.willwinder.universalgcodesender.gcode.processors.LineSplitter;
import com.willwinder.universalgcodesender.gcode.processors.MeshLeveler;
import com.willwinder.universalgcodesender.gcode.util.Code;
import com.willwinder.universalgcodesender.gcode.util.GcodeParserException;
import com.willwinder.universalgcodesender.listeners.ControllerState;
import com.willwinder.universalgcodesender.listeners.ControllerStatus;
import com.willwinder.universalgcodesender.model.CommunicatorState;
import com.willwinder.universalgcodesender.model.GUIBackend;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UnitUtils.Units;
import com.willwinder.universalgcodesender.services.RunFromService;
import com.willwinder.universalgcodesender.services.jobvalidation.JobRequest;
import com.willwinder.universalgcodesender.services.jobvalidation.JobType;
import com.willwinder.universalgcodesender.services.jobvalidation.JobValidationException;
import com.willwinder.universalgcodesender.services.jobvalidation.JobValidationService;
import com.willwinder.universalgcodesender.utils.Settings;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Tests that aligned programs can only be started when they are the validated program, through
 * every way that a job is started, and that other programs are started as before.
 */
public class PcbJobProtectionTest {
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

    private AbstractController controller;
    private GcodeState machineState;
    private GUIBackend backend;
    private RunFromService runFromService;
    private PcbAlignmentService service;
    private File source;
    private File alignedFile;

    @Before
    public void setUp() throws Exception {
        controller = mock(AbstractController.class);
        machineState = new GcodeState();
        machineState.offset = Code.G54;
        backend = createConnectedBackend(controller);
        when(controller.getCurrentGcodeState()).thenReturn(machineState);

        runFromService = new RunFromService(backend);
        service = PcbToolsStartup.register(backend, runFromService::getLineNumber);

        source = new File(folder.getRoot(), "board.nc");
        Files.write(source.toPath(), PROGRAM, StandardCharsets.UTF_8);
        alignedFile = new File(folder.getRoot(), "board_aligned.nc");
        backend.setGcodeFile(source);
    }

    private static GUIBackend createConnectedBackend(AbstractController controller) throws Exception {
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
        backend.connect("GRBL", "/dev/ttyS0", 9600);
        return backend;
    }

    private void assertSent(int times) throws Exception {
        verify(controller, times(times)).queueStream(any());
        verify(controller, times(times)).beginStreaming();
    }

    private void assertSendRejected(String expectedMessage) throws Exception {
        JobValidationException exception = assertThrows(JobValidationException.class, () -> backend.send());
        assertTrue(exception.getMessage(), exception.getMessage().startsWith(expectedMessage));
        verify(controller, never()).queueStream(any());
        verify(controller, never()).beginStreaming();
    }

    private void assertOutlineRejected(String expectedMessage) {
        JobValidationException exception = assertThrows(JobValidationException.class,
                () -> backend.getJobValidationService().validate(JobType.OUTLINE, backend.getGcodeFile(), backend.getProcessedGcodeFile()));
        assertTrue(exception.getMessage(), exception.getMessage().startsWith(expectedMessage));
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

    // Programs that are not aligned

    @Test
    public void conventionalProgramShouldBeSent() throws Exception {
        backend.send();

        assertSent(1);
    }

    @Test
    public void conventionalProgramShouldBeSentFromALine() throws Exception {
        runFromService.runFromLine(5);

        backend.send();

        assertSent(1);
    }

    @Test
    public void conventionalProgramWithHeightMapShouldBeSent() throws Exception {
        backend.applyCommandProcessor(createLevelerProcessors());

        backend.send();

        assertSent(1);
    }

    @Test
    public void outlineOfConventionalProgramShouldBeAccepted() throws Exception {
        backend.getJobValidationService().validate(JobType.OUTLINE, backend.getGcodeFile(), backend.getProcessedGcodeFile());
    }

    @Test
    public void originalProgramShouldBeSentAfterItsAlignedProgramWasLoaded() throws Exception {
        service.alignLoadedFile(TRANSFORM, Code.G54);
        backend.setGcodeFile(source);

        backend.send();

        assertSent(1);
    }

    // Aligned programs that are valid

    @Test
    public void alignedProgramShouldBeSentWhenLoadedByTheAlignment() throws Exception {
        service.alignLoadedFile(TRANSFORM, Code.G54);

        backend.send();

        assertSent(1);
    }

    @Test
    public void approvalShouldIdentifyTheJob() throws Exception {
        AlignedGcodeFile aligned = service.alignLoadedFile(TRANSFORM, Code.G54);

        Approval approval = service.getApproval().orElseThrow();

        assertEquals(alignedFile.getAbsoluteFile(), approval.alignedFile());
        assertEquals(aligned.metadata(), approval.metadata());
        assertEquals(Code.G54, approval.metadata().workCoordinateSystem());
        assertEquals(JobRequest.sha256(backend.getProcessedGcodeFile()), approval.processedSha256());
    }

    @Test
    public void alignedProgramShouldBeSentAgainWhenNothingHasChanged() throws Exception {
        service.alignLoadedFile(TRANSFORM, Code.G54);

        backend.send();
        backend.send();

        assertSent(2);
    }

    @Test
    public void alignedProgramWithHeightMapShouldBeSentAfterItIsValidated() throws Exception {
        service.alignLoadedFile(TRANSFORM, Code.G54);
        backend.applyCommandProcessor(createLevelerProcessors());
        service.validateProcessedFile(0.5);

        backend.send();

        assertSent(1);
    }

    // Aligned programs that have not been validated

    @Test
    public void alignedProgramThatIsOpenedShouldNotBeSentUntilItIsValidated() throws Exception {
        new AlignedGcodeExporter().export(source, TRANSFORM, Code.G54, alignedFile);
        backend.setGcodeFile(alignedFile);

        assertSendRejected(PcbAlignmentService.ERROR_NOT_APPROVED);

        service.validateProcessedFile(0);
        backend.send();
        assertSent(1);
    }

    @Test
    public void alignedProgramShouldNotBeSentAfterHeightMapIsApplied() throws Exception {
        service.alignLoadedFile(TRANSFORM, Code.G54);

        backend.applyCommandProcessor(createLevelerProcessors());

        assertSendRejected(PcbAlignmentService.ERROR_NOT_APPROVED);
    }

    @Test
    public void alignedProgramShouldNotBeSentAfterItIsProcessedAgainEvenIfTheServiceWasNotTold() throws Exception {
        service.alignLoadedFile(TRANSFORM, Code.G54);
        backend.removeUGSEventListener(service);

        backend.applyCommandProcessor(createLevelerProcessors());

        assertTrue(service.getApproval().isPresent());
        assertSendRejected(PcbAlignmentService.ERROR_PROCESSED_CHANGED);
    }

    @Test
    public void alignedProgramShouldNotBeSentWhenHeightMapChangesZMoreThanAccepted() throws Exception {
        service.alignLoadedFile(TRANSFORM, Code.G54);
        backend.applyCommandProcessor(createLevelerProcessors());

        assertThrows(AlignmentException.class, () -> service.validateProcessedFile(0.01));

        assertSendRejected(PcbAlignmentService.ERROR_NOT_APPROVED);
    }

    @Test
    public void alignedProgramThatCouldNotBeProcessedShouldNotBeSent() throws Exception {
        service.alignLoadedFile(TRANSFORM, Code.G54);

        // The backend does not keep any of the program when processing fails
        assertThrows(Exception.class, () -> backend.applyCommandProcessor(failsOn("F100")));
        assertTrue(service.getApproval().isEmpty());
        assertThrows(AlignmentException.class, () -> service.validateProcessedFile(0));

        assertSendRejected(GUIBackend.ERROR_PROGRAM_NOT_PROCESSED);
    }

    @Test
    public void alignedProgramThatCouldNotBeProcessedShouldNotBeSentEvenIfTheServiceWasNotTold() throws Exception {
        service.alignLoadedFile(TRANSFORM, Code.G54);
        backend.removeUGSEventListener(service);

        assertThrows(Exception.class, () -> backend.applyCommandProcessor(failsOn("F100")));

        assertTrue(service.getApproval().isPresent());
        assertSendRejected(GUIBackend.ERROR_PROGRAM_NOT_PROCESSED);
    }

    @Test
    public void alignedProgramShouldNotBeSentWithEarlierApprovalAfterFailedProcessingIsRecovered() throws Exception {
        service.alignLoadedFile(TRANSFORM, Code.G54);
        backend.removeUGSEventListener(service);
        CommandProcessor failing = failsOn("F100");
        assertThrows(Exception.class, () -> backend.applyCommandProcessor(failing));

        // The program can be processed again, but not to what was approved
        backend.removeCommandProcessor(failing);
        backend.applyCommandProcessor(createLevelerProcessors());

        assertSendRejected(PcbAlignmentService.ERROR_PROCESSED_CHANGED);
    }

    @Test
    public void alignedProgramShouldBeSentAfterFailedProcessingIsRecoveredAndValidated() throws Exception {
        service.alignLoadedFile(TRANSFORM, Code.G54);
        CommandProcessor failing = failsOn("F100");
        assertThrows(Exception.class, () -> backend.applyCommandProcessor(failing));
        assertThrows(JobValidationException.class, () -> backend.send());

        backend.removeCommandProcessor(failing);
        service.validateProcessedFile(0);
        backend.send();

        assertSent(1);
    }

    // Aligned programs that have been changed

    @Test
    public void alignedProgramThatIsChangedOnDiskShouldNotBeSent() throws Exception {
        service.alignLoadedFile(TRANSFORM, Code.G54);
        List<String> lines = Files.readAllLines(alignedFile.toPath(), StandardCharsets.UTF_8);
        lines.add("G0 X0 Y0");

        Files.write(alignedFile.toPath(), lines, StandardCharsets.UTF_8);

        assertSendRejected(PcbAlignmentService.ERROR_NOT_ALIGNED);
    }

    @Test
    public void alignedProgramThatIsChangedAndLoadedAgainShouldNotBeSent() throws Exception {
        service.alignLoadedFile(TRANSFORM, Code.G54);
        List<String> lines = Files.readAllLines(alignedFile.toPath(), StandardCharsets.UTF_8);
        lines.set(lines.size() - 3, "G1 X99 Y99");
        Files.write(alignedFile.toPath(), lines, StandardCharsets.UTF_8);

        backend.setGcodeFile(alignedFile);

        assertThrows(AlignmentException.class, () -> service.validateProcessedFile(0));
        assertSendRejected(PcbAlignmentService.ERROR_NOT_ALIGNED);
    }

    @Test
    public void alignedProgramThatIsReplacedOnDiskByAnotherAlignmentShouldNotBeSent() throws Exception {
        service.alignLoadedFile(TRANSFORM, Code.G54);

        new AlignedGcodeExporter().export(source, OTHER_TRANSFORM, Code.G54, alignedFile);

        assertSendRejected(PcbAlignmentService.ERROR_NOT_APPROVED);
    }

    @Test
    public void approvalShouldNotApplyToAnotherAlignedProgram() throws Exception {
        service.alignLoadedFile(TRANSFORM, Code.G54);
        backend.removeUGSEventListener(service);
        File otherAligned = new File(folder.getRoot(), "other_aligned.nc");
        new AlignedGcodeExporter().export(source, TRANSFORM, Code.G54, otherAligned);

        backend.setGcodeFile(otherAligned);

        assertSendRejected(PcbAlignmentService.ERROR_NOT_APPROVED);
    }

    // The work coordinate system

    @Test
    public void alignedProgramShouldNotBeSentWhenMachineUsesAnotherWorkCoordinateSystem() throws Exception {
        service.alignLoadedFile(TRANSFORM, Code.G54);

        machineState.offset = Code.G55;

        assertSendRejected(PcbAlignmentService.ERROR_WORK_COORDINATE_SYSTEM);
    }

    @Test
    public void alignedProgramShouldNotBeSentWhenWorkCoordinateSystemIsUnknown() throws Exception {
        service.alignLoadedFile(TRANSFORM, Code.G54);

        machineState.offset = null;
        assertSendRejected(PcbAlignmentService.ERROR_WORK_COORDINATE_SYSTEM_UNKNOWN);

        when(controller.getCurrentGcodeState()).thenReturn(null);
        assertSendRejected(PcbAlignmentService.ERROR_WORK_COORDINATE_SYSTEM_UNKNOWN);
    }

    @Test
    public void alignedProgramShouldBeSentWhenMachineIsBackInTheMeasuredWorkCoordinateSystem() throws Exception {
        service.alignLoadedFile(TRANSFORM, Code.G54);
        machineState.offset = Code.G55;
        assertThrows(JobValidationException.class, () -> backend.send());

        machineState.offset = Code.G54;
        backend.send();

        assertSent(1);
    }

    // Run from a line

    @Test
    public void alignedProgramShouldNotBeSentFromALine() throws Exception {
        service.alignLoadedFile(TRANSFORM, Code.G54);

        runFromService.runFromLine(13);

        assertSendRejected(PcbJobValidator.ERROR_RUN_FROM + 13);
    }

    @Test
    public void alignedProgramShouldNotBeSentFromALineEvenIfItIsValidated() throws Exception {
        service.alignLoadedFile(TRANSFORM, Code.G54);
        runFromService.runFromLine(12);

        // Running from the first move of this program gives the same tool path, which is valid
        service.validateProcessedFile(0);

        assertSendRejected(PcbJobValidator.ERROR_RUN_FROM + 12);
    }

    @Test
    public void alignedProgramShouldBeSentWhenItIsSetToRunFromTheBeginningAgain() throws Exception {
        service.alignLoadedFile(TRANSFORM, Code.G54);
        runFromService.runFromLine(13);
        assertThrows(JobValidationException.class, () -> backend.send());

        runFromService.runFromLine(0);
        service.validateProcessedFile(0);
        backend.send();

        assertSent(1);
    }

    // Outline

    @Test
    public void outlineOfAlignedProgramShouldBeRejected() throws Exception {
        service.alignLoadedFile(TRANSFORM, Code.G54);

        assertOutlineRejected(PcbJobValidator.ERROR_OUTLINE);
    }

    @Test
    public void outlineOfAlignedProgramThatIsNotValidatedShouldBeRejected() throws Exception {
        new AlignedGcodeExporter().export(source, TRANSFORM, Code.G54, alignedFile);
        backend.setGcodeFile(alignedFile);

        assertOutlineRejected(PcbJobValidator.ERROR_OUTLINE);
    }

    // The validator is missing or fails

    @Test
    public void alignedProgramShouldNotBeSentWhenValidatorIsNotRegistered() throws Exception {
        AbstractController otherController = mock(AbstractController.class);
        GUIBackend otherBackend = createConnectedBackend(otherController);
        new AlignedGcodeExporter().export(source, TRANSFORM, Code.G54, alignedFile);
        otherBackend.setGcodeFile(alignedFile);

        JobValidationException exception = assertThrows(JobValidationException.class, otherBackend::send);

        assertEquals(JobValidationService.ERROR_VALIDATOR_MISSING + AlignmentMetadata.JOB_VALIDATOR_ID, exception.getMessage());
        verify(otherController, never()).queueStream(any());
        verify(otherController, never()).beginStreaming();
    }

    @Test
    public void outlineOfAlignedProgramShouldBeRejectedWhenValidatorIsNotRegistered() throws Exception {
        GUIBackend otherBackend = createConnectedBackend(mock(AbstractController.class));
        new AlignedGcodeExporter().export(source, TRANSFORM, Code.G54, alignedFile);
        otherBackend.setGcodeFile(alignedFile);

        assertThrows(JobValidationException.class,
                () -> otherBackend.getJobValidationService().validate(JobType.OUTLINE, otherBackend.getGcodeFile(), otherBackend.getProcessedGcodeFile()));
    }

    @Test
    public void conventionalProgramShouldBeSentWhenValidatorIsNotRegistered() throws Exception {
        AbstractController otherController = mock(AbstractController.class);
        GUIBackend otherBackend = createConnectedBackend(otherController);
        otherBackend.setGcodeFile(source);

        otherBackend.send();

        verify(otherController).beginStreaming();
    }

    @Test
    public void alignedProgramShouldNotBeSentWhenValidatorFails() throws Exception {
        AbstractController otherController = mock(AbstractController.class);
        when(otherController.getCurrentGcodeState()).thenReturn(machineState);
        GUIBackend otherBackend = createConnectedBackend(otherController);
        otherBackend.setGcodeFile(source);
        PcbAlignmentService otherService = PcbToolsStartup.register(otherBackend, () -> {
            throw new IllegalStateException("The run from service is not available");
        });
        otherService.alignLoadedFile(TRANSFORM, Code.G54);

        JobValidationException exception = assertThrows(JobValidationException.class, otherBackend::send);

        assertEquals(JobValidationService.ERROR_VALIDATOR_FAILED + AlignmentMetadata.JOB_VALIDATOR_ID, exception.getMessage());
        verify(otherController, never()).queueStream(any());
    }

    // Exporting the processed program

    @Test
    public void exportShouldNotAffectTheApprovalOfAnAlignedProgram() throws Exception {
        service.alignLoadedFile(TRANSFORM, Code.G54);
        Approval approval = service.getApproval().orElseThrow();
        File processedFile = backend.getProcessedGcodeFile();

        backend.preprocessAndExportToFile(new File(folder.getRoot(), "exported.nc"));

        assertEquals(PcbAlignmentService.ValidationState.VALID, service.getValidationState());
        assertEquals(approval, service.getApproval().orElseThrow());
        assertEquals(processedFile, backend.getProcessedGcodeFile());
        assertEquals(approval.processedSha256(), JobRequest.sha256(processedFile));
        assertEquals(approval.metadata(), AlignmentMetadata.read(alignedFile).orElseThrow());
        backend.send();
        assertSent(1);
    }

    @Test
    public void exportOverAlignedProgramShouldBeRejected() throws Exception {
        service.alignLoadedFile(TRANSFORM, Code.G54);
        Approval approval = service.getApproval().orElseThrow();

        assertThrows(Exception.class, () -> backend.preprocessAndExportToFile(alignedFile));

        assertEquals(approval.metadata(), AlignmentMetadata.read(alignedFile).orElseThrow());
        backend.send();
        assertSent(1);
    }

    @Test
    public void exportedAlignedProgramShouldNotBeSentAsAValidatedProgram() throws Exception {
        service.alignLoadedFile(TRANSFORM, Code.G54);
        File exported = new File(folder.getRoot(), "exported.nc");
        backend.preprocessAndExportToFile(exported);

        backend.setGcodeFile(exported);

        assertTrue(AlignmentMetadata.hasAlignmentHeader(exported));
        assertSendRejected(PcbAlignmentService.ERROR_NOT_ALIGNED);
    }

    // Pause and resume

    @Test
    public void pausedAlignedProgramShouldBeResumedWithoutBeingValidatedAgain() throws Exception {
        service.alignLoadedFile(TRANSFORM, Code.G54);
        backend.send();
        when(controller.getCommunicatorState()).thenReturn(CommunicatorState.COMM_SENDING_PAUSED);
        when(controller.getControllerStatus()).thenReturn(new ControllerStatus(ControllerState.HOLD, new Position(0, 0, 0), new Position(0, 0, 0)));

        // Something that would prevent the program from being started
        backend.removeUGSEventListener(service);
        machineState.offset = null;
        backend.pauseResume();

        verify(controller).resumeStreaming();
        assertSent(1);
        assertFalse(service.getApproval().isEmpty());
    }
}
