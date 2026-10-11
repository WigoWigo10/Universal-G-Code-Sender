package com.willwinder.universalgcodesender.model;

import com.willwinder.universalgcodesender.AbstractController;
import com.willwinder.universalgcodesender.IController;
import com.willwinder.universalgcodesender.firmware.IFirmwareSettings;
import com.willwinder.universalgcodesender.gcode.DefaultCommandCreator;
import com.willwinder.universalgcodesender.gcode.GcodeState;
import com.willwinder.universalgcodesender.gcode.processors.CommandProcessor;
import com.willwinder.universalgcodesender.listeners.ControllerState;
import com.willwinder.universalgcodesender.listeners.ControllerStatus;
import com.willwinder.universalgcodesender.model.events.FileStateEvent;
import com.willwinder.universalgcodesender.utils.FirmwareUtils;
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
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Tests that the processed program is exported to the file that is asked for, without changing
 * any of the files that the backend works with.
 */
public class GUIBackendExportTest {
    private static final List<String> PROGRAM = List.of("(A comment)", "G0 X1", "G1 X2 (cut)", "G0 X3", "M5");
    private static final List<String> EXPORTED_PROGRAM = List.of("(A comment)", "G0X1", "G1X2 (cut)", "G0X3", "M5");

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private AbstractController controller;
    private TestBackend backend;
    private File gcodeFile;
    private File target;
    private final List<FileStateEvent> fileEvents = new ArrayList<>();

    /**
     * A backend where the verification of the exported file can be made to fail
     */
    private static class TestBackend extends GUIBackend {
        private final IController controller;
        private boolean failVerification;

        TestBackend(IController controller) {
            this.controller = controller;
        }

        @Override
        protected IController fetchControllerFromFirmware(String firmware) {
            return controller;
        }

        @Override
        protected void verifyExportedFile(File file, int numberOfRows) throws IOException {
            super.verifyExportedFile(file, numberOfRows);
            if (failVerification) {
                throw new IOException("The exported program could not be verified");
            }
        }
    }

    @Before
    public void setUp() throws Exception {
        controller = mock(AbstractController.class);
        doReturn(mock(IFirmwareSettings.class)).when(controller).getFirmwareSettings();
        when(controller.isCommOpen()).thenReturn(true);
        when(controller.getCommandCreator()).thenReturn(new DefaultCommandCreator());
        when(controller.getControllerStatus()).thenReturn(new ControllerStatus(ControllerState.IDLE, new Position(0, 0, 0), new Position(0, 0, 0)));

        backend = new TestBackend(controller);
        backend.applySettings(new Settings());
        backend.connect("GRBL", "/dev/ttyS0", 9600);

        gcodeFile = folder.newFile("program.nc");
        Files.write(gcodeFile.toPath(), PROGRAM, StandardCharsets.UTF_8);
        backend.setGcodeFile(gcodeFile);
        backend.addUGSEventListener(event -> {
            if (event instanceof FileStateEvent fileStateEvent) {
                fileEvents.add(fileStateEvent);
            }
        });

        target = new File(folder.getRoot(), "exported.nc");
    }

    private static List<String> readLines(File file) throws IOException {
        return Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
    }

    private List<String> filesInFolder() {
        return Arrays.stream(folder.getRoot().list()).sorted().toList();
    }

    private static CommandProcessor appending(String text) {
        return new CommandProcessor() {
            @Override
            public List<String> processCommand(String command, GcodeState state) {
                return List.of(command + text);
            }

            @Override
            public String getHelp() {
                return "";
            }
        };
    }

    // Valid targets

    @Test
    public void exportShouldWriteProcessedProgramToTheGivenFile() throws Exception {
        backend.preprocessAndExportToFile(target);

        assertEquals(EXPORTED_PROGRAM, readLines(target));
        assertEquals(List.of("exported.nc", "program.nc"), filesInFolder());
    }

    @Test
    public void exportShouldNotChangeLoadedOrProcessedProgram() throws Exception {
        File processedFile = backend.getProcessedGcodeFile();
        byte[] loadedBefore = Files.readAllBytes(gcodeFile.toPath());
        byte[] processedBefore = Files.readAllBytes(processedFile.toPath());

        backend.preprocessAndExportToFile(target);

        assertArrayEquals(loadedBefore, Files.readAllBytes(gcodeFile.toPath()));
        assertArrayEquals(processedBefore, Files.readAllBytes(processedFile.toPath()));
        assertEquals(processedFile, backend.getProcessedGcodeFile());
        assertEquals(gcodeFile, backend.getGcodeFile());
    }

    @Test
    public void exportShouldNotProcessProgramAgainOrChangeItsStatistics() throws Exception {
        long commandCount = backend.getGcodeStats().getCommandCount();

        backend.preprocessAndExportToFile(target);
        backend.preprocessAndExportToFile(target);

        assertEquals(commandCount, backend.getGcodeStats().getCommandCount());
        assertTrue(fileEvents.isEmpty());
        assertTrue(backend.canSend());
    }

    @Test
    public void exportShouldIncludeWhatProcessorsHaveDoneToTheProgram() throws Exception {
        backend.applyCommandProcessor(appending("F100"));

        backend.preprocessAndExportToFile(target);

        assertEquals(List.of("F100 (A comment)", "G0X1F100", "G1X2F100 (cut)", "G0X3F100", "M5F100"), readLines(target));
    }

    @Test
    public void exportShouldReplaceEarlierExport() throws Exception {
        Files.write(target.toPath(), List.of("An earlier export", "with other content", "and more lines", "than", "the", "new one"), StandardCharsets.UTF_8);

        backend.preprocessAndExportToFile(target);

        assertEquals(EXPORTED_PROGRAM, readLines(target));
        assertEquals(List.of("exported.nc", "program.nc"), filesInFolder());
    }

    @Test
    public void exportOfEmptyProgramShouldWriteEmptyFile() throws Exception {
        File emptyFile = folder.newFile("empty.nc");
        backend.setGcodeFile(emptyFile);

        backend.preprocessAndExportToFile(target);

        assertTrue(target.isFile());
        assertEquals(List.of(), readLines(target));
    }

    // Forbidden targets

    @Test
    public void exportOverLoadedProgramShouldBeRejected() throws Exception {
        IOException exception = assertThrows(IOException.class, () -> backend.preprocessAndExportToFile(gcodeFile));

        assertEquals(GUIBackend.ERROR_EXPORT_OVER_PROGRAM + gcodeFile, exception.getMessage());
        assertEquals(PROGRAM, readLines(gcodeFile));
    }

    @Test
    public void exportOverLoadedProgramGivenByAnotherPathShouldBeRejected() throws Exception {
        File samePlace = new File(folder.getRoot(), "." + File.separator + "program.nc");

        assertThrows(IOException.class, () -> backend.preprocessAndExportToFile(samePlace));

        assertEquals(PROGRAM, readLines(gcodeFile));
    }

    @Test
    public void exportOverProcessedProgramShouldBeRejected() throws Exception {
        File processedFile = backend.getProcessedGcodeFile();
        byte[] processedBefore = Files.readAllBytes(processedFile.toPath());

        IOException exception = assertThrows(IOException.class, () -> backend.preprocessAndExportToFile(processedFile));

        assertEquals(GUIBackend.ERROR_EXPORT_OVER_PROGRAM + processedFile, exception.getMessage());
        assertArrayEquals(processedBefore, Files.readAllBytes(processedFile.toPath()));
    }

    @Test
    public void exportOverEarlierProcessedProgramShouldBeRejected() throws Exception {
        // A job that is being sent keeps streaming from the processed program it was started with
        File earlierProcessedFile = backend.getProcessedGcodeFile();
        byte[] before = Files.readAllBytes(earlierProcessedFile.toPath());
        backend.reloadGcodeFile();

        assertThrows(IOException.class, () -> backend.preprocessAndExportToFile(earlierProcessedFile));

        assertArrayEquals(before, Files.readAllBytes(earlierProcessedFile.toPath()));
    }

    @Test
    public void exportToTheDirectoryOfProcessedProgramsShouldBeRejected() throws Exception {
        File directory = backend.getProcessedGcodeFile().getParentFile();
        List<String> filesBefore = Arrays.stream(directory.list()).sorted().toList();

        assertThrows(IOException.class, () -> backend.preprocessAndExportToFile(new File(directory, "exported.nc")));

        assertEquals(filesBefore, Arrays.stream(directory.list()).sorted().toList());
    }

    @Test
    public void exportWithoutTargetShouldBeRejected() {
        assertThrows(IOException.class, () -> backend.preprocessAndExportToFile(null));
    }

    @Test
    public void exportToDirectoryShouldBeRejected() {
        assertThrows(FileNotFoundException.class, () -> backend.preprocessAndExportToFile(folder.getRoot()));

        assertEquals(List.of("program.nc"), filesInFolder());
    }

    @Test
    public void exportToDirectoryThatDoesNotExistShouldBeRejected() {
        File missing = new File(new File(folder.getRoot(), "missing"), "exported.nc");

        assertThrows(FileNotFoundException.class, () -> backend.preprocessAndExportToFile(missing));

        assertEquals(List.of("program.nc"), filesInFolder());
    }

    // Failures

    @Test
    public void exportWithoutProcessedProgramShouldBeRejected() throws Exception {
        backend.unsetGcodeFile();

        IOException exception = assertThrows(IOException.class, () -> backend.preprocessAndExportToFile(target));

        assertEquals(GUIBackend.ERROR_EXPORT_NOT_PROCESSED, exception.getMessage());
        assertFalse(target.exists());
    }

    @Test
    public void failedExportShouldNotLeaveAnyFile() {
        backend.failVerification = true;

        assertThrows(IOException.class, () -> backend.preprocessAndExportToFile(target));

        assertEquals(List.of("program.nc"), filesInFolder());
    }

    @Test
    public void failedExportShouldKeepEarlierExport() throws Exception {
        List<String> earlier = List.of("An earlier export", "that must be kept");
        Files.write(target.toPath(), earlier, StandardCharsets.UTF_8);
        backend.failVerification = true;

        assertThrows(IOException.class, () -> backend.preprocessAndExportToFile(target));

        assertEquals(earlier, readLines(target));
        assertEquals(List.of("exported.nc", "program.nc"), filesInFolder());
    }

    @Test
    public void verifyShouldRejectFileWithOtherNumberOfRowsThanWritten() throws Exception {
        File file = folder.newFile("written.nc");
        Files.write(file.toPath(), List.of("G0 X1", "G0 X2"), StandardCharsets.UTF_8);

        backend.verifyExportedFile(file, 2);
        assertThrows(IOException.class, () -> backend.verifyExportedFile(file, 3));
    }

    // While a job is being sent

    @Test
    public void exportWhileProgramIsBeingSentShouldNotChangeTheProgramThatIsStreamed() throws Exception {
        backend.send();
        ArgumentCaptor<IGcodeStreamReader> stream = ArgumentCaptor.forClass(IGcodeStreamReader.class);
        verify(controller).queueStream(stream.capture());
        when(controller.isStreaming()).thenReturn(true);
        File processedFile = backend.getProcessedGcodeFile();
        byte[] processedBefore = Files.readAllBytes(processedFile.toPath());
        assertEquals("", stream.getValue().getNextCommand().getCommandString());
        assertEquals("G0X1", stream.getValue().getNextCommand().getCommandString());

        backend.preprocessAndExportToFile(target);

        assertEquals(EXPORTED_PROGRAM, readLines(target));
        assertArrayEquals(processedBefore, Files.readAllBytes(processedFile.toPath()));
        assertEquals("G1X2", stream.getValue().getNextCommand().getCommandString());
        assertEquals("G0X3", stream.getValue().getNextCommand().getCommandString());
        assertEquals("M5", stream.getValue().getNextCommand().getCommandString());
        assertEquals(0, stream.getValue().getNumRowsRemaining());
    }

    // The classic user interface

    @Test
    public void exportShouldWorkAsTheClassicUserInterfaceUsesIt() throws Exception {
        // The save button applies the settings to a controller of its own and exports to the chosen file
        GUIBackend classicBackend = new GUIBackend();
        Settings settings = new Settings();
        classicBackend.applySettings(settings);
        classicBackend.setGcodeFile(gcodeFile);
        IController grblController = FirmwareUtils.getControllerFor("GRBL").get();
        classicBackend.applySettingsToController(settings, grblController);

        classicBackend.preprocessAndExportToFile(target);

        assertEquals(EXPORTED_PROGRAM, readLines(target));
        assertEquals(PROGRAM, readLines(gcodeFile));
    }

    @Test
    public void exportWithoutLoadedProgramShouldFailAsTheClassicUserInterfaceExpects() throws Exception {
        GUIBackend classicBackend = new GUIBackend();
        classicBackend.applySettings(new Settings());

        // The save button reports an IOException as a failure to process the file
        assertThrows(IOException.class, () -> classicBackend.preprocessAndExportToFile(target));
        assertFalse(target.exists());
    }
}
