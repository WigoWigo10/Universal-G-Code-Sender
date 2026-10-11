/*
    Copyright 2026 Will Winder

    This file is part of Universal Gcode Sender (UGS).

    UGS is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    UGS is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with UGS.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.willwinder.ugs.nbp.pcb.ui;

import com.willwinder.universalgcodesender.gcode.GcodeState;
import com.willwinder.universalgcodesender.gcode.util.Code;
import com.willwinder.universalgcodesender.listeners.ControllerState;
import com.willwinder.universalgcodesender.model.BackendAPIReadOnly;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UnitUtils.Units;

import java.util.function.Supplier;

/**
 * Reads where the machine is, in work coordinates. It only reads what the backend already knows
 * about the machine: it can not send anything to the controller, as it only has the part of the
 * backend that is for reading.
 */
public class PositionCapture {
    public static final String ERROR_NOT_CONNECTED = "The machine is not connected. Connect to it, or enter the measured position by hand.";
    public static final String ERROR_NOT_IDLE = "The position can only be captured while the machine is idle. It is ";
    public static final String ERROR_POSITION_UNKNOWN = "The work position of the machine is not known.";
    public static final String ERROR_WORK_COORDINATE_SYSTEM_UNKNOWN = "The work coordinate system of the machine is not known.";

    /**
     * A position in work coordinates
     *
     * @param x                    the X position in millimeters
     * @param y                    the Y position in millimeters
     * @param workCoordinateSystem the work coordinate system that the position is in
     */
    public record CapturedPosition(double x, double y, Code workCoordinateSystem) {
    }

    public static class CaptureException extends Exception {
        public CaptureException(String message) {
            super(message);
        }
    }

    private final BackendAPIReadOnly backend;
    private final Supplier<ControllerState> controllerState;

    /**
     * @param backend         the part of the backend that is for reading
     * @param controllerState gives the state of the controller, which is not in that part of the backend
     */
    public PositionCapture(BackendAPIReadOnly backend, Supplier<ControllerState> controllerState) {
        this.backend = backend;
        this.controllerState = controllerState;
    }

    /**
     * @throws CaptureException if the position of the machine or its work coordinate system is not known
     */
    public CapturedPosition capture() throws CaptureException {
        if (!backend.isConnected()) {
            throw new CaptureException(ERROR_NOT_CONNECTED);
        }

        ControllerState state = controllerState.get();
        if (state != ControllerState.IDLE) {
            throw new CaptureException(ERROR_NOT_IDLE + state);
        }

        GcodeState gcodeState = backend.getGcodeState();
        if (gcodeState == null || gcodeState.offset == null || gcodeState.offset.getType() != Code.ModalGroup.WCS) {
            throw new CaptureException(ERROR_WORK_COORDINATE_SYSTEM_UNKNOWN);
        }

        Position workPosition = backend.getWorkPosition();
        if (workPosition == null || workPosition.getUnits() == Units.UNKNOWN) {
            throw new CaptureException(ERROR_POSITION_UNKNOWN);
        }

        Position millimeters = workPosition.getPositionIn(Units.MM);
        if (!Double.isFinite(millimeters.getX()) || !Double.isFinite(millimeters.getY())) {
            throw new CaptureException(ERROR_POSITION_UNKNOWN);
        }
        return new CapturedPosition(millimeters.getX(), millimeters.getY(), gcodeState.offset);
    }
}
