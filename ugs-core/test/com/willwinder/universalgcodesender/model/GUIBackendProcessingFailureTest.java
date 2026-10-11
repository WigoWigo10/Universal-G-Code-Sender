package com.willwinder.universalgcodesender.model;

import com.willwinder.universalgcodesender.AbstractController;
import com.willwinder.universalgcodesender.IController;
import com.willwinder.universalgcodesender.firmware.IFirmwareSettings;
import com.willwinder.universalgcodesender.gcode.DefaultCommandCreator;
import com.willwinder.universalgcodesender.gcode.GcodeParser;
import com.willwinder.universalgcodesender.gcode.GcodeState;
import com.willwinder.universalgcodesender.gcode.processors.CommandProcessor;
import com.willwinder.universalgcodesender.gcode.util.GcodeParserException;
import com.willwinder.universalgcodesender.listeners.ControllerState;
import com.willwinder.universalgcodesender.listeners.ControllerStatus;
import com.willwinder.universalgcodesender.model.events.FileState;
import com.willwinder.universalgcodesender.model.events.FileStateEvent;
import com.willwinder.universalgcodesender.services.jobvalidation.JobRequest;
import com.willwinder.universalgcodesender.services.jobvalidation.JobValidationException;
import com.willwinder.universalgcodesender.services.jobvalidation.JobValidator;
import com.willwinder.universalgcodesender.utils.GcodeStreamReader;
import com.willwinder.universalgcodesender.utils.GcodeStreamWriter;
import com.willwinder.universalgcodesender.utils.IGcodeStreamReader;
import com.willwinder.universalgcodesender.utils.IGcodeWriter;
import com.willwinder.universalgcodesender.utils.Settings;
import org.apache.commons.io.FileUtils;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Tests that a program that could not be processed completely is never made available for
 * sending, and that programs that are processed successfully are handled as before.
 */
public class GUIBackendProcessingFailureTest {
    private static final String PROGRAM = "G0 X1\nG0 X2\nG0 X3\nG0 X4\n";
    private static final List<String> PROCESSED_PROGRAM = List.of("G0X1", "G0X2", "G0X3", "G0X4");

    private AbstractController controller;
    private TestBackend backend;
    private File gcodeFile;
    private final List<FileState> fileStates = new ArrayList<>();

    /**
     * A backend where the steps after the program has been processed can be made to fail
     */
    private static class TestBackend extends GUIBackend {
        private final IController controller;
        private boolean failAfterProcessing;
        private boolean failVerification;

        TestBackend(IController controller) {
            this.controller = controller;
        }

        @Override
        protected IController fetchControllerFromFirmware(String firmware) {
            return controller;
        }

        @Override
        protected void preprocessAndExportToFile(GcodeParser gcp, File input, IGcodeWriter gcw) throws Exception {
            super.preprocessAndExportToFile(gcp, input, gcw);
            if (failAfterProcessing) {
                throw new IOException("Failed after the last command was processed");
            }
        }

        @Override
        protected void verifyProcessedFile(File file, int numberOfRows) throws IOException {
            super.verifyProcessedFile(file, numberOfRows);
            if (failVerification) {
                throw new IOException("The processed program could not be verified");
            }
        }
    }

    @Before
    public void setUp() throws Exception {
        controller = mock(AbstractController.class);
        doReturn(mock(IFirmwareSettings.class)).when(controller).getFirmwareSettings();
        when(controller.isCommOpen()).thenReturn(true);
        when(controller.getControllerStatus()).thenReturn(new ControllerStatus(ControllerState.IDLE, new Position(0, 0, 0), new Position(0, 0, 0)));

        backend = new TestBackend(controller);
        backend.applySettings(new Settings());
        backend.connect("GRBL", "/dev/ttyS0", 9600);
        backend.addUGSEventListener(event -> {
            if (event instanceof FileStateEvent fileStateEvent) {
                fileStates.add(fileStateEvent.getFileState());
            }
        });

        gcodeFile = createGcodeFile(PROGRAM);
        backend.setGcodeFile(gcodeFile);
        fileStates.clear();
    }

    private static File createGcodeFile(String content) throws Exception {
        File file = File.createTempFile("ugs-", ".gcode");
        file.deleteOnExit();
        FileUtils.writeStringToFile(file, content, StandardCharsets.UTF_8);
        return file;
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

    private static List<String> readCommands(File processedFile) throws Exception {
        List<String> result = new ArrayList<>();
        try (IGcodeStreamReader reader = new GcodeStreamReader(processedFile, new DefaultCommandCreator())) {
            while (reader.getNumRowsRemaining() > 0) {
                result.add(reader.getNextCommand().getCommandString());
            }
        }
        return result;
    }

    private static List<String> filesIn(File directory) {
        return Arrays.stream(directory.list()).sorted().toList();
    }

    private void assertNothingCanBeSent() throws Exception {
        assertNull(backend.getProcessedGcodeFile());
        assertFalse(backend.canSend());

        JobValidationException exception = assertThrows(JobValidationException.class, () -> backend.send());
        assertEquals(GUIBackend.ERROR_PROGRAM_NOT_PROCESSED, exception.getMessage());
        verify(controller, never()).queueStream(any());
        verify(controller, never()).beginStreaming();
    }

    /**
     * Applies a processor that fails and asserts that nothing of the program is left to send
     */
    private void assertFailingProcessorLeavesNothing(CommandProcessor processor) throws Exception {
        File directory = backend.getProcessedGcodeFile().getParentFile();
        List<String> filesBefore = filesIn(directory);

        assertThrows(Exception.class, () -> backend.applyCommandProcessor(processor));

        assertNothingCanBeSent();
        assertEquals(filesBefore, filesIn(directory));
        assertEquals(gcodeFile, backend.getGcodeFile());
        assertEquals(PROGRAM, FileUtils.readFileToString(gcodeFile, StandardCharsets.UTF_8));
    }

    // Successful processing is as before

    @Test
    public void loadedProgramShouldBeProcessedCompletely() throws Exception {
        File processedFile = backend.getProcessedGcodeFile();

        assertEquals(PROCESSED_PROGRAM, readCommands(processedFile));
        assertTrue(processedFile.getName(), processedFile.getName().matches(".*_ugs_\\d+$"));
        assertEquals(List.of(processedFile.getName()), filesIn(processedFile.getParentFile()));
        assertEquals(PROGRAM, FileUtils.readFileToString(gcodeFile, StandardCharsets.UTF_8));
        assertTrue(backend.canSend());
    }

    @Test
    public void loadingProgramShouldSendTheSameEventsAsBefore() throws Exception {
        backend.setGcodeFile(gcodeFile);

        assertEquals(List.of(FileState.FILE_UNLOADED, FileState.OPENING_FILE, FileState.FILE_LOADING, FileState.FILE_LOADED), fileStates);
    }

    @Test
    public void reprocessedProgramShouldBeWrittenToANewFile() throws Exception {
        File firstProcessedFile = backend.getProcessedGcodeFile();

        backend.reloadGcodeFile();

        assertNotEquals(firstProcessedFile, backend.getProcessedGcodeFile());
        assertEquals(PROCESSED_PROGRAM, readCommands(backend.getProcessedGcodeFile()));
        assertEquals(PROCESSED_PROGRAM, readCommands(firstProcessedFile));
        assertEquals(List.of(FileState.OPENING_FILE, FileState.FILE_LOADING, FileState.FILE_LOADED), fileStates);
    }

    @Test
    public void processedProgramShouldBeSent() throws Exception {
        backend.send();

        verify(controller).queueStream(any());
        verify(controller).beginStreaming();
    }

    @Test
    public void emptyProgramShouldBeProcessedToAnEmptyProgram() throws Exception {
        backend.setGcodeFile(createGcodeFile(""));

        assertNotNull(backend.getProcessedGcodeFile());
        assertEquals(List.of(), readCommands(backend.getProcessedGcodeFile()));
        assertTrue(backend.canSend());
    }

    @Test
    public void statisticsShouldBeOfTheProcessedProgram() throws Exception {
        assertTrue(backend.getGcodeStats().getCommandCount() > 0);
        assertEquals(backend.getGcodeStats().getCommandCount(), backend.getSettings().getFileStats().numCommands);
    }

    // Failures while processing

    @Test
    public void failureOnFirstCommandShouldLeaveNothingToSend() throws Exception {
        assertFailingProcessorLeavesNothing(failsOn("X1"));
    }

    @Test
    public void failureInTheMiddleShouldLeaveNothingToSend() throws Exception {
        assertFailingProcessorLeavesNothing(failsOn("X3"));
    }

    @Test
    public void failureOnLastCommandShouldLeaveNothingToSend() throws Exception {
        assertFailingProcessorLeavesNothing(failsOn("X4"));
    }

    @Test
    public void unexpectedExceptionFromProcessorShouldLeaveNothingToSend() throws Exception {
        assertFailingProcessorLeavesNothing(new CommandProcessor() {
            @Override
            public List<String> processCommand(String command, GcodeState state) {
                if (command.contains("X3")) {
                    throw new IllegalStateException("Something is wrong");
                }
                return List.of(command);
            }

            @Override
            public String getHelp() {
                return "";
            }
        });
    }

    @Test
    public void failureAfterLastCommandShouldLeaveNothingToSend() throws Exception {
        File directory = backend.getProcessedGcodeFile().getParentFile();
        List<String> filesBefore = filesIn(directory);
        backend.failAfterProcessing = true;

        assertThrows(IOException.class, () -> backend.reloadGcodeFile());

        assertNothingCanBeSent();
        assertEquals(filesBefore, filesIn(directory));
    }

    @Test
    public void failureWhenVerifyingWrittenProgramShouldLeaveNothingToSend() throws Exception {
        File directory = backend.getProcessedGcodeFile().getParentFile();
        List<String> filesBefore = filesIn(directory);
        backend.failVerification = true;

        assertThrows(IOException.class, () -> backend.reloadGcodeFile());

        assertNothingCanBeSent();
        assertEquals(filesBefore, filesIn(directory));
    }

    @Test
    public void failureWhenLoadingProgramShouldLeaveNothingToSend() throws Exception {
        File directory = backend.getProcessedGcodeFile().getParentFile();
        List<String> filesBefore = filesIn(directory);
        File otherFile = createGcodeFile("G0 X9\n");
        backend.failAfterProcessing = true;

        assertThrows(IOException.class, () -> backend.setGcodeFile(otherFile));

        assertNothingCanBeSent();
        assertEquals(otherFile, backend.getGcodeFile());
        assertEquals(filesBefore, filesIn(directory));
        assertEquals(List.of(FileState.FILE_UNLOADED, FileState.OPENING_FILE, FileState.FILE_LOADING, FileState.FILE_UNLOADED), fileStates);
    }

    @Test
    public void failureWhenReprocessingShouldTellListenersThatTheProgramIsNotAvailable() {
        assertThrows(Exception.class, () -> backend.applyCommandProcessor(failsOn("X3")));

        assertEquals(List.of(FileState.FILE_LOADING, FileState.FILE_UNLOADED), fileStates);
    }

    @Test
    public void failureWhenReprocessingShouldNotKeepEarlierProcessedProgram() throws Exception {
        File earlierProcessedFile = backend.getProcessedGcodeFile();

        assertThrows(Exception.class, () -> backend.applyCommandProcessor(failsOn("X3")));

        assertNull(backend.getProcessedGcodeFile());
        assertEquals(PROCESSED_PROGRAM, readCommands(earlierProcessedFile));
        assertNothingCanBeSent();
    }

    @Test
    public void failureShouldNotKeepStatisticsOfPartOfTheProgram() {
        assertThrows(Exception.class, () -> backend.applyCommandProcessor(failsOn("X3")));

        assertEquals(0, backend.getGcodeStats().getCommandCount());
    }

    @Test
    public void processedProgramShouldBeSentOnceAfterSendingFailedProgramWasRefused() throws Exception {
        CommandProcessor failing = failsOn("X3");
        assertThrows(Exception.class, () -> backend.applyCommandProcessor(failing));
        assertThrows(JobValidationException.class, () -> backend.send());

        backend.removeCommandProcessor(failing);
        backend.send();

        verify(controller).queueStream(any());
        verify(controller).beginStreaming();
    }

    // Recovering from a failure

    @Test
    public void removingFailingProcessorShouldProcessProgramAgain() throws Exception {
        long commandCount = backend.getGcodeStats().getCommandCount();
        CommandProcessor failing = failsOn("X3");
        assertThrows(Exception.class, () -> backend.applyCommandProcessor(failing));
        fileStates.clear();

        backend.removeCommandProcessor(failing);

        assertEquals(PROCESSED_PROGRAM, readCommands(backend.getProcessedGcodeFile()));
        assertTrue(backend.canSend());
        assertEquals(List.of(FileState.FILE_LOADING, FileState.FILE_LOADED), fileStates);
        assertEquals(commandCount, backend.getGcodeStats().getCommandCount());
        assertEquals(commandCount, backend.getSettings().getFileStats().numCommands);
    }

    @Test
    public void reloadingProgramWithFailingProcessorShouldLeaveNothingToSend() throws Exception {
        assertThrows(Exception.class, () -> backend.applyCommandProcessor(failsOn("X3")));

        assertThrows(Exception.class, () -> backend.reloadGcodeFile());

        assertNothingCanBeSent();
    }

    @Test
    public void openingProgramAgainAfterFailureShouldProcessIt() throws Exception {
        assertThrows(Exception.class, () -> backend.applyCommandProcessor(failsOn("X3")));

        backend.setGcodeFile(gcodeFile);

        assertEquals(PROCESSED_PROGRAM, readCommands(backend.getProcessedGcodeFile()));
        assertTrue(backend.canSend());
    }

    // The job validation

    @Test
    public void validatorsShouldNotBeAskedAboutProgramThatCouldNotBeProcessed() throws Exception {
        List<JobRequest> requests = new ArrayList<>();
        backend.getJobValidationService().register(createValidator(requests));
        assertThrows(Exception.class, () -> backend.applyCommandProcessor(failsOn("X3")));

        assertNothingCanBeSent();
        assertTrue(requests.isEmpty());
    }

    @Test
    public void validatorsShouldBeAskedAboutProgramThatWasProcessedAgainAfterFailure() throws Exception {
        List<JobRequest> requests = new ArrayList<>();
        backend.getJobValidationService().register(createValidator(requests));
        CommandProcessor failing = failsOn("X3");
        assertThrows(Exception.class, () -> backend.applyCommandProcessor(failing));
        backend.removeCommandProcessor(failing);

        backend.send();

        assertEquals(1, requests.size());
        assertEquals(backend.getProcessedGcodeFile(), requests.get(0).getProcessedGcodeFile());
        assertEquals(JobRequest.sha256(backend.getProcessedGcodeFile()), requests.get(0).getProcessedSha256());
    }

    private static JobValidator createValidator(List<JobRequest> requests) {
        return new JobValidator() {
            @Override
            public String getId() {
                return "test";
            }

            @Override
            public void validate(JobRequest request) {
                requests.add(request);
            }
        };
    }

    // Verifying the written program

    @Test
    public void verifyShouldAcceptCompleteProgram() throws Exception {
        File file = writeStreamFile(3);

        backend.verifyProcessedFile(file, 3);
    }

    @Test
    public void verifyShouldAcceptEmptyProgram() throws Exception {
        File file = writeStreamFile(0);

        backend.verifyProcessedFile(file, 0);
    }

    @Test
    public void verifyShouldRejectProgramWithOtherNumberOfRowsThanWritten() throws Exception {
        File file = writeStreamFile(3);

        assertThrows(IOException.class, () -> backend.verifyProcessedFile(file, 4));
    }

    @Test
    public void verifyShouldRejectProgramThatWasCutShort() throws Exception {
        File file = writeStreamFile(3);
        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        Files.write(file.toPath(), lines.subList(0, lines.size() - 1), StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> backend.verifyProcessedFile(file, 3));
    }

    @Test
    public void verifyShouldRejectFileThatIsNotAProcessedProgram() throws Exception {
        File file = createGcodeFile(PROGRAM);

        assertThrows(IOException.class, () -> backend.verifyProcessedFile(file, 4));
    }

    private static File writeStreamFile(int rows) throws Exception {
        File file = File.createTempFile("ugs-", ".stream");
        file.deleteOnExit();
        try (IGcodeWriter writer = new GcodeStreamWriter(file)) {
            for (int i = 1; i <= rows; i++) {
                writer.addLine("G0 X" + i, "G0X" + i, null, i);
            }
        }
        return file;
    }
}
