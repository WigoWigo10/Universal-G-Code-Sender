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

import com.willwinder.universalgcodesender.gcode.util.Code;
import com.willwinder.universalgcodesender.model.Position;

/**
 * What is known of the machine at one moment. Whatever is not known is null, and is then never
 * taken to be fine.
 *
 * @param isConnected          if there is communication with the controller
 * @param condition            what the controller is doing
 * @param workCoordinateSystem the work coordinate system in use
 * @param workOffset           what is added to a position in the work coordinate system to get the
 *                             position of the machine, in millimeters
 * @param workPosition         where the tool is in the work coordinate system, in millimeters
 * @param probeContact         if the sensor has contact
 */
public record MachineSnapshot(boolean isConnected, Condition condition, Code workCoordinateSystem, Position workOffset,
                              Position workPosition, Boolean probeContact) {
    public enum Condition {
        IDLE,
        BUSY,
        HOLD,
        ALARM,
        UNKNOWN
    }

    public static MachineSnapshot disconnected() {
        return new MachineSnapshot(false, Condition.UNKNOWN, null, null, null, null);
    }

    /**
     * Returns what the positions of a probing are measured from, or null if not all of it is known
     */
    public ReferenceFrame getReferenceFrame() {
        if (workCoordinateSystem == null || workOffset == null) {
            return null;
        }
        return new ReferenceFrame(workCoordinateSystem, workOffset.getX(), workOffset.getY(), workOffset.getZ());
    }
}
