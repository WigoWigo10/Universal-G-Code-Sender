package com.willwinder.universalgcodesender.pendantui.v1.resources;

import com.willwinder.universalgcodesender.AbstractController;
import com.willwinder.universalgcodesender.IController;
import com.willwinder.universalgcodesender.firmware.IFirmwareSettings;
import com.willwinder.universalgcodesender.listeners.ControllerState;
import com.willwinder.universalgcodesender.listeners.ControllerStatus;
import com.willwinder.universalgcodesender.model.BackendAPI;
import com.willwinder.universalgcodesender.model.CommunicatorState;
import com.willwinder.universalgcodesender.model.GUIBackend;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.services.jobvalidation.JobRequest;
import com.willwinder.universalgcodesender.services.jobvalidation.JobValidationException;
import com.willwinder.universalgcodesender.services.jobvalidation.JobValidationService;
import com.willwinder.universalgcodesender.services.jobvalidation.JobValidator;
import com.willwinder.universalgcodesender.utils.Settings;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Tests that a program that is started from the pendant is validated like any other program
 */
public class FilesResourceJobValidationTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private AbstractController controller;
    private GUIBackend backend;
    private FilesResource filesResource;
    private int validations;
    private String rejection;

    @Before
    public void setUp() throws Exception {
        controller = mock(AbstractController.class);
        backend = new GUIBackend() {
            @Override
            protected IController fetchControllerFromFirmware(String firmware) {
                return controller;
            }
        };
        doReturn(mock(IFirmwareSettings.class)).when(controller).getFirmwareSettings();
        when(controller.isCommOpen()).thenReturn(true);
        backend.applySettings(new Settings());
        backend.connect("GRBL", "/dev/ttyS0", 9600);

        File gcodeFile = folder.newFile("program.nc");
        Files.write(gcodeFile.toPath(), List.of("G0 X0 Y0", "G1 X10"), StandardCharsets.UTF_8);
        backend.setGcodeFile(gcodeFile);

        backend.getJobValidationService().register(new JobValidator() {
            @Override
            public String getId() {
                return "test";
            }

            @Override
            public void validate(JobRequest request) throws JobValidationException {
                validations++;
                if (rejection != null) {
                    throw new JobValidationException(rejection);
                }
            }
        });

        filesResource = new FilesResource();
        Field backendField = FilesResource.class.getDeclaredField("backendAPI");
        backendField.setAccessible(true);
        backendField.set(filesResource, (BackendAPI) backend);
    }

    @Test
    public void sendShouldStartProgramThatIsAccepted() throws Exception {
        filesResource.send();

        verify(controller).queueStream(any());
        verify(controller).beginStreaming();
        assertEquals(1, validations);
    }

    @Test
    public void sendShouldNotStartProgramThatIsRejected() throws Exception {
        rejection = "The program has not been validated";

        JobValidationException exception = assertThrows(JobValidationException.class, () -> filesResource.send());

        assertEquals("The program has not been validated", exception.getMessage());
        verify(controller, never()).queueStream(any());
        verify(controller, never()).beginStreaming();
    }

    @Test
    public void sendShouldNotStartProgramThatRequiresMissingValidator() throws Exception {
        File protectedFile = folder.newFile("protected.nc");
        Files.write(protectedFile.toPath(), List.of(JobValidationService.createRequirement("special"), "G0 X0 Y0"), StandardCharsets.UTF_8);
        backend.setGcodeFile(protectedFile);

        JobValidationException exception = assertThrows(JobValidationException.class, () -> filesResource.send());

        assertEquals(JobValidationService.ERROR_VALIDATOR_MISSING + "special", exception.getMessage());
        verify(controller, never()).queueStream(any());
    }

    @Test
    public void sendShouldResumePausedJobWithoutValidatingItAgain() throws Exception {
        filesResource.send();
        rejection = "The program was changed";
        when(controller.getCommunicatorState()).thenReturn(CommunicatorState.COMM_SENDING_PAUSED);
        when(controller.getControllerStatus()).thenReturn(new ControllerStatus(ControllerState.HOLD, new Position(0, 0, 0), new Position(0, 0, 0)));

        filesResource.send();

        verify(controller).resumeStreaming();
        verify(controller).beginStreaming();
        assertEquals(1, validations);
    }
}
