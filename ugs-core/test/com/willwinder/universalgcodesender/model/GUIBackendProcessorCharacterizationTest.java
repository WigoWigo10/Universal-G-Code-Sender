package com.willwinder.universalgcodesender.model;

import com.willwinder.universalgcodesender.gcode.DefaultCommandCreator;
import com.willwinder.universalgcodesender.gcode.GcodeState;
import com.willwinder.universalgcodesender.gcode.processors.CommandProcessor;
import com.willwinder.universalgcodesender.gcode.util.GcodeParserException;
import com.willwinder.universalgcodesender.utils.GcodeStreamReader;
import com.willwinder.universalgcodesender.utils.IGcodeStreamReader;
import com.willwinder.universalgcodesender.utils.Settings;
import org.apache.commons.io.FileUtils;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests how the backend handles command processors when files are loaded, reloaded and when a
 * processor fails, as constraints for processors that must never leave a partly processed program.
 */
public class GUIBackendProcessorCharacterizationTest {
    private static final String PROGRAM = "G0 X1\nG0 X2\nG0 X3\nG0 X4\n";

    private GUIBackend backend;

    /**
     * Adds a marker to every command so that it is visible if the processor was applied
     */
    private final CommandProcessor marker = new CommandProcessor() {
        @Override
        public List<String> processCommand(String command, GcodeState state) {
            return List.of(command + "F123");
        }

        @Override
        public String getHelp() {
            return "";
        }
    };

    private final CommandProcessor failsOnThirdCommand = new CommandProcessor() {
        @Override
        public List<String> processCommand(String command, GcodeState state) throws GcodeParserException {
            if (command.contains("X3")) {
                throw new GcodeParserException("Can not process " + command);
            }
            return List.of(command);
        }

        @Override
        public String getHelp() {
            return "";
        }
    };

    @Before
    public void setUp() throws Exception {
        backend = new GUIBackend();
        backend.applySettings(new Settings());
    }

    private static File createGcodeFile(String content) throws Exception {
        File file = File.createTempFile("ugs-", ".gcode");
        file.deleteOnExit();
        FileUtils.writeStringToFile(file, content, StandardCharsets.UTF_8);
        return file;
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

    @Test
    public void appliedProcessorShouldProcessLoadedFile() throws Exception {
        backend.setGcodeFile(createGcodeFile(PROGRAM));

        backend.applyCommandProcessor(marker);

        assertEquals(List.of("G0X1F123", "G0X2F123", "G0X3F123", "G0X4F123"), readCommands(backend.getProcessedGcodeFile()));
    }

    @Test
    public void appliedProcessorShouldNotChangeOriginalFile() throws Exception {
        File file = createGcodeFile(PROGRAM);
        backend.setGcodeFile(file);

        backend.applyCommandProcessor(marker);

        assertEquals(PROGRAM, FileUtils.readFileToString(file, StandardCharsets.UTF_8));
        assertEquals(file, backend.getGcodeFile());
    }

    @Test
    public void reloadingFileShouldKeepAppliedProcessor() throws Exception {
        backend.setGcodeFile(createGcodeFile(PROGRAM));
        backend.applyCommandProcessor(marker);

        backend.reloadGcodeFile();

        assertEquals(List.of("G0X1F123", "G0X2F123", "G0X3F123", "G0X4F123"), readCommands(backend.getProcessedGcodeFile()));
    }

    @Test
    public void openingAnotherFileShouldRemoveAppliedProcessor() throws Exception {
        backend.setGcodeFile(createGcodeFile(PROGRAM));
        backend.applyCommandProcessor(marker);

        backend.setGcodeFile(createGcodeFile("G0 X9\n"));

        assertEquals(List.of("G0X9"), readCommands(backend.getProcessedGcodeFile()));
    }

    @Test
    public void openingSameFileAgainShouldRemoveAppliedProcessor() throws Exception {
        File file = createGcodeFile(PROGRAM);
        backend.setGcodeFile(file);
        backend.applyCommandProcessor(marker);

        backend.setGcodeFile(file);

        assertEquals(List.of("G0X1", "G0X2", "G0X3", "G0X4"), readCommands(backend.getProcessedGcodeFile()));
    }

    @Ignore("Fails: the commands processed before the failure are left as the processed file, which is what gets sent")
    @Test
    public void failingProcessorShouldNotLeavePartlyProcessedFile() throws Exception {
        backend.setGcodeFile(createGcodeFile(PROGRAM));

        assertThrows(Exception.class, () -> backend.applyCommandProcessor(failsOnThirdCommand));

        File processedFile = backend.getProcessedGcodeFile();
        if (processedFile != null) {
            fail("A processed file with the commands " + readCommands(processedFile) + " is still available for sending");
        }
        assertNull(processedFile);
    }
}
