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
package com.willwinder.ugs.nbp.pcb.probing;

import com.willwinder.universalgcodesender.model.Position;

/**
 * How an operation ended
 *
 * @param outcome      what happened
 * @param workPosition where the tool is in the work coordinate system in millimeters, null if that is not known.
 *                     For a probing with contact it is where the contact was.
 * @param message      what the controller told, if anything
 */
public record OperationResult(Outcome outcome, Position workPosition, String message) {
    public enum Outcome {
        /** The move was done */
        COMPLETED,
        /** The probing ended with contact */
        CONTACT,
        /** The probing ended without contact */
        NO_CONTACT,
        /** The controller went into an alarm */
        ALARM,
        /** The controller refused the operation or answered with an error */
        ERROR,
        /** The communication with the controller was lost */
        DISCONNECTED
    }
}
