package com.willwinder.ugs.nbp.core.actions;

import com.willwinder.universalgcodesender.model.BackendAPI;
import com.willwinder.universalgcodesender.model.GUIBackend;
import com.willwinder.universalgcodesender.services.LookupService;
import com.willwinder.universalgcodesender.utils.Settings;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests that the start action only starts programs through the backend, which is where jobs are
 * validated, and that it resumes a paused job without starting it again.
 */
public class StartActionJobValidationTest {

    @Before
    public void setUp() throws Exception {
        GUIBackend backend = new GUIBackend();
        backend.applySettings(new Settings());
        LookupService.register(backend);
    }

    @Test
    public void executeShouldStartProgramThroughTheBackend() throws Exception {
        BackendAPI backend = mock(BackendAPI.class);
        when(backend.isPaused()).thenReturn(false);

        new StartAction(backend).execute(null);

        verify(backend).send();
        verify(backend, never()).pauseResume();
        verify(backend, never()).getController();
    }

    @Test
    public void executeShouldResumePausedJobWithoutStartingItAgain() throws Exception {
        BackendAPI backend = mock(BackendAPI.class);
        when(backend.isPaused()).thenReturn(true);

        new StartAction(backend).execute(null);

        verify(backend).pauseResume();
        verify(backend, never()).send();
    }
}
