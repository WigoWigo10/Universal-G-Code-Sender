package com.willwinder.universalgcodesender.model;

import com.willwinder.universalgcodesender.AbstractController;
import com.willwinder.universalgcodesender.firmware.IFirmwareSettings;
import com.willwinder.universalgcodesender.i18n.Localization;
import com.willwinder.universalgcodesender.listeners.ControllerState;
import com.willwinder.universalgcodesender.listeners.ControllerStatus;
import com.willwinder.universalgcodesender.services.jobvalidation.JobRequest;
import com.willwinder.universalgcodesender.services.jobvalidation.JobType;
import com.willwinder.universalgcodesender.services.jobvalidation.JobValidationException;
import com.willwinder.universalgcodesender.services.jobvalidation.JobValidationService;
import com.willwinder.universalgcodesender.services.jobvalidation.JobValidator;
import com.willwinder.universalgcodesender.utils.Settings;
import org.apache.commons.io.FileUtils;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Tests that the backend asks the job validators before it streams a program, and that it
 * behaves as before when there are none.
 */
public class GUIBackendJobValidationTest {
    private AbstractController controller;
    private GUIBackend backend;
    private File gcodeFile;
    private final List<JobRequest> requests = new ArrayList<>();
    private String rejection;

    private final JobValidator validator = new JobValidator() {
        @Override
        public String getId() {
            return "test";
        }

        @Override
        public void validate(JobRequest request) throws JobValidationException {
            requests.add(request);
            if (rejection != null) {
                throw new JobValidationException(rejection);
            }
        }
    };

    @Before
    public void setUp() throws Exception {
        backend = spy(new GUIBackend(new UGSEventDispatcher()));
        controller = mock(AbstractController.class);
        doReturn(controller).when(backend).fetchControllerFromFirmware(any());
        doReturn(mock(IFirmwareSettings.class)).when(controller).getFirmwareSettings();
        when(controller.isCommOpen()).thenReturn(true);
        backend.applySettings(new Settings());
        backend.connect("GRBL", "/dev/ttyS0", 9600);

        gcodeFile = createGcodeFile("G0 X0 Y0\nG1 X10\n");
        backend.setGcodeFile(gcodeFile);
    }

    private static File createGcodeFile(String content) throws Exception {
        File file = File.createTempFile("ugs-", ".gcode");
        file.deleteOnExit();
        FileUtils.writeStringToFile(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private void assertNothingWasStreamed() throws Exception {
        verify(controller, never()).queueStream(any());
        verify(controller, never()).beginStreaming();
    }

    @Test
    public void sendWithoutValidatorsShouldStreamProgram() throws Exception {
        backend.send();

        verify(controller).queueStream(any());
        verify(controller).beginStreaming();
    }

    @Test
    public void sendShouldStreamProgramThatValidatorAccepts() throws Exception {
        backend.getJobValidationService().register(validator);

        backend.send();

        verify(controller).queueStream(any());
        verify(controller).beginStreaming();
        assertEquals(1, requests.size());
    }

    @Test
    public void sendShouldAskValidatorAboutTheProgramThatIsStreamed() throws Exception {
        backend.getJobValidationService().register(validator);
        File processedFile = backend.getProcessedGcodeFile();

        backend.send();

        JobRequest request = requests.get(0);
        assertEquals(JobType.PROGRAM, request.getType());
        assertEquals(gcodeFile, request.getGcodeFile());
        assertEquals(processedFile, request.getProcessedGcodeFile());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(processedFile.toPath()))),
                request.getProcessedSha256());
    }

    @Test
    public void sendShouldNotStreamProgramThatValidatorRejects() throws Exception {
        backend.getJobValidationService().register(validator);
        rejection = "The program has not been validated";

        JobValidationException exception = assertThrows(JobValidationException.class, () -> backend.send());

        assertEquals("The program has not been validated", exception.getMessage());
        assertNothingWasStreamed();
    }

    @Test
    public void sendShouldNotStreamProgramWhenValidatorFailsUnexpectedly() throws Exception {
        backend.getJobValidationService().register(new JobValidator() {
            @Override
            public String getId() {
                return "broken";
            }

            @Override
            public void validate(JobRequest request) {
                throw new IllegalStateException("Something is wrong");
            }
        });

        JobValidationException exception = assertThrows(JobValidationException.class, () -> backend.send());

        assertEquals(JobValidationService.ERROR_VALIDATOR_FAILED + "broken", exception.getMessage());
        assertNothingWasStreamed();
    }

    @Test
    public void sendShouldNotStreamProgramThatRequiresMissingValidator() throws Exception {
        backend.setGcodeFile(createGcodeFile(JobValidationService.createRequirement("special") + "\nG0 X0 Y0\n"));

        JobValidationException exception = assertThrows(JobValidationException.class, () -> backend.send());

        assertEquals(JobValidationService.ERROR_VALIDATOR_MISSING + "special", exception.getMessage());
        assertNothingWasStreamed();
    }

    @Test
    public void sendShouldAskValidatorAgainForEveryJob() throws Exception {
        backend.getJobValidationService().register(validator);
        backend.send();

        rejection = "The program was changed";

        assertThrows(JobValidationException.class, () -> backend.send());
        assertEquals(2, requests.size());
        verify(controller).beginStreaming();
    }

    @Test
    public void sendShouldAskValidatorAboutProgramAsItIsProcessedWhenSent() throws Exception {
        backend.getJobValidationService().register(validator);
        File firstProcessedFile = backend.getProcessedGcodeFile();

        backend.reloadGcodeFile();
        backend.send();

        assertFalse(firstProcessedFile.equals(requests.get(0).getProcessedGcodeFile()));
        assertEquals(backend.getProcessedGcodeFile(), requests.get(0).getProcessedGcodeFile());
    }

    @Test
    public void resumingPausedJobShouldNotAskValidatorAgain() throws Exception {
        backend.getJobValidationService().register(validator);
        backend.send();
        rejection = "The program was changed";
        when(controller.getCommunicatorState()).thenReturn(CommunicatorState.COMM_SENDING_PAUSED);
        when(controller.getControllerStatus()).thenReturn(new ControllerStatus(ControllerState.HOLD, new Position(0, 0, 0), new Position(0, 0, 0)));
        assertTrue(backend.isPaused());

        backend.pauseResume();

        verify(controller).resumeStreaming();
        assertEquals(1, requests.size());
    }

    @Test
    public void pausingJobShouldNotAskValidator() throws Exception {
        backend.getJobValidationService().register(validator);
        backend.send();
        rejection = "The program was changed";
        when(controller.getCommunicatorState()).thenReturn(CommunicatorState.COMM_SENDING);

        backend.pauseResume();

        verify(controller).pauseStreaming();
        assertEquals(1, requests.size());
    }

    @Test
    public void otherFailuresWhenSendingShouldBeReportedAsBefore() throws Exception {
        backend.getJobValidationService().register(validator);
        doThrow(new Exception("Not ready")).when(controller).isReadyToStreamFile();

        Exception exception = assertThrows(Exception.class, () -> backend.send());

        assertFalse(exception instanceof JobValidationException);
        assertEquals(Localization.getString("mainWindow.error.startingStream"), exception.getMessage());
        assertTrue(requests.isEmpty());
        assertNothingWasStreamed();
    }
}
