package com.willwinder.ugs.nbp.core.actions;

import com.willwinder.universalgcodesender.IController;
import com.willwinder.universalgcodesender.model.BackendAPI;
import com.willwinder.universalgcodesender.model.GUIBackend;
import com.willwinder.universalgcodesender.services.LookupService;
import com.willwinder.universalgcodesender.services.jobvalidation.JobRequest;
import com.willwinder.universalgcodesender.services.jobvalidation.JobType;
import com.willwinder.universalgcodesender.services.jobvalidation.JobValidationException;
import com.willwinder.universalgcodesender.services.jobvalidation.JobValidationService;
import com.willwinder.universalgcodesender.services.jobvalidation.JobValidator;
import com.willwinder.universalgcodesender.types.GcodeCommand;
import com.willwinder.universalgcodesender.utils.Settings;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests that the outline, which is not streamed through the backend, asks if the job may be started
 */
public class OutlineActionJobValidationTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private final JobValidationService jobValidationService = new JobValidationService();
    private final List<JobRequest> requests = new ArrayList<>();
    private final List<GcodeCommand> outline = List.of(new GcodeCommand("G90G1X0Y0F1000"));
    private IController controller;
    private OutlineAction outlineAction;
    private File gcodeFile;
    private File processedFile;
    private String rejection;

    @Before
    public void setUp() throws Exception {
        GUIBackend registeredBackend = new GUIBackend();
        registeredBackend.applySettings(new Settings());
        LookupService.register(registeredBackend);

        gcodeFile = folder.newFile("program.nc");
        Files.write(gcodeFile.toPath(), List.of("G0 X0 Y0", "G1 X10"), StandardCharsets.UTF_8);
        processedFile = folder.newFile("processed");

        controller = mock(IController.class);
        BackendAPI backend = mock(BackendAPI.class);
        when(backend.getController()).thenReturn(controller);
        when(backend.getJobValidationService()).thenReturn(jobValidationService);
        when(backend.getGcodeFile()).thenReturn(gcodeFile);
        outlineAction = new OutlineAction(backend);

        jobValidationService.register(new JobValidator() {
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
        });
    }

    @Test
    public void outlineShouldBeStreamedWhenItIsAccepted() throws Exception {
        outlineAction.sendOutline(processedFile, outline);

        verify(controller).queueStream(any());
        verify(controller).beginStreaming();
    }

    @Test
    public void validatorShouldBeAskedAboutTheOutlineOfTheLoadedProgram() throws Exception {
        outlineAction.sendOutline(processedFile, outline);

        assertEquals(1, requests.size());
        assertEquals(JobType.OUTLINE, requests.get(0).getType());
        assertSame(gcodeFile, requests.get(0).getGcodeFile());
        assertSame(processedFile, requests.get(0).getProcessedGcodeFile());
    }

    @Test
    public void outlineShouldNotBeStreamedWhenItIsRejected() throws Exception {
        rejection = "The outline is not available for this program";

        JobValidationException exception = assertThrows(JobValidationException.class,
                () -> outlineAction.sendOutline(processedFile, outline));

        assertEquals("The outline is not available for this program", exception.getMessage());
        verify(controller, never()).queueStream(any());
        verify(controller, never()).beginStreaming();
    }

    @Test
    public void outlineShouldNotBeStreamedForProgramThatRequiresMissingValidator() throws Exception {
        Files.write(gcodeFile.toPath(), List.of(JobValidationService.createRequirement("special"), "G0 X0 Y0"), StandardCharsets.UTF_8);

        JobValidationException exception = assertThrows(JobValidationException.class,
                () -> outlineAction.sendOutline(processedFile, outline));

        assertEquals(JobValidationService.ERROR_VALIDATOR_MISSING + "special", exception.getMessage());
        verify(controller, never()).queueStream(any());
    }
}
