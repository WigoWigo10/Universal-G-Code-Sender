package com.willwinder.ugs.nbp.pcb.ui;

import com.willwinder.ugs.nbp.pcb.ui.PositionCapture.CaptureException;
import com.willwinder.ugs.nbp.pcb.ui.PositionCapture.CapturedPosition;
import com.willwinder.universalgcodesender.gcode.GcodeState;
import com.willwinder.universalgcodesender.gcode.util.Code;
import com.willwinder.universalgcodesender.listeners.ControllerState;
import com.willwinder.universalgcodesender.model.BackendAPIReadOnly;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UnitUtils.Units;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PositionCaptureTest {
    private BackendAPIReadOnly backend;
    private GcodeState gcodeState;
    private ControllerState controllerState = ControllerState.IDLE;
    private PositionCapture positionCapture;

    @Before
    public void setUp() {
        gcodeState = new GcodeState();
        gcodeState.offset = Code.G54;

        backend = mock(BackendAPIReadOnly.class);
        when(backend.isConnected()).thenReturn(true);
        when(backend.getGcodeState()).thenReturn(gcodeState);
        when(backend.getWorkPosition()).thenReturn(new Position(12.345, -6.5, 3, Units.MM));
        when(backend.getMachinePosition()).thenReturn(new Position(-200, -150, -10, Units.MM));

        positionCapture = new PositionCapture(backend, () -> controllerState);
    }

    private void assertCaptureFails(String expectedMessage) {
        CaptureException exception = assertThrows(CaptureException.class, () -> positionCapture.capture());
        assertEquals(expectedMessage, exception.getMessage());
    }

    @Test
    public void captureShouldReturnWorkPositionAndNotMachinePosition() throws Exception {
        CapturedPosition position = positionCapture.capture();

        assertEquals(12.345, position.x(), 1e-9);
        assertEquals(-6.5, position.y(), 1e-9);
        assertEquals(Code.G54, position.workCoordinateSystem());
    }

    @Test
    public void captureShouldReturnTheWorkCoordinateSystemOfTheMachine() throws Exception {
        gcodeState.offset = Code.G57;

        assertEquals(Code.G57, positionCapture.capture().workCoordinateSystem());
    }

    @Test
    public void captureShouldConvertPositionToMillimeters() throws Exception {
        when(backend.getWorkPosition()).thenReturn(new Position(1, -2, 0, Units.INCH));

        CapturedPosition position = positionCapture.capture();

        assertEquals(25.4, position.x(), 1e-9);
        assertEquals(-50.8, position.y(), 1e-9);
    }

    @Test
    public void captureShouldFailWhenNotConnected() {
        when(backend.isConnected()).thenReturn(false);

        assertCaptureFails(PositionCapture.ERROR_NOT_CONNECTED);
    }

    @Test
    public void captureShouldFailWhileMachineIsNotIdle() {
        for (ControllerState state : new ControllerState[]{ControllerState.RUN, ControllerState.JOG, ControllerState.HOLD, ControllerState.ALARM, ControllerState.HOME, ControllerState.UNKNOWN}) {
            controllerState = state;

            assertCaptureFails(PositionCapture.ERROR_NOT_IDLE + state);
        }
    }

    @Test
    public void captureShouldFailWhenWorkCoordinateSystemIsNotKnown() {
        gcodeState.offset = null;
        assertCaptureFails(PositionCapture.ERROR_WORK_COORDINATE_SYSTEM_UNKNOWN);

        when(backend.getGcodeState()).thenReturn(null);
        assertCaptureFails(PositionCapture.ERROR_WORK_COORDINATE_SYSTEM_UNKNOWN);
    }

    @Test
    public void captureShouldFailWhenPositionIsNotKnown() {
        when(backend.getWorkPosition()).thenReturn(null);
        assertCaptureFails(PositionCapture.ERROR_POSITION_UNKNOWN);

        when(backend.getWorkPosition()).thenReturn(new Position(Double.NaN, 1, 0, Units.MM));
        assertCaptureFails(PositionCapture.ERROR_POSITION_UNKNOWN);

        when(backend.getWorkPosition()).thenReturn(new Position(1, Double.POSITIVE_INFINITY, 0, Units.MM));
        assertCaptureFails(PositionCapture.ERROR_POSITION_UNKNOWN);

        when(backend.getWorkPosition()).thenReturn(new Position(1, 2, 0, Units.UNKNOWN));
        assertCaptureFails(PositionCapture.ERROR_POSITION_UNKNOWN);
    }
}
