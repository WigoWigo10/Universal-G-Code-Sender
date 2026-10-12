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

import com.willwinder.ugs.nbp.pcb.heightmap.ProbeArea;
import com.willwinder.universalgcodesender.model.Position;

import java.time.Instant;
import java.util.List;

/**
 * The measurements of a probing session so far, kept so that they are not lost when the session
 * is interrupted. A checkpoint is not a height map: it is never applied to a program, however many
 * of its points have been measured.
 *
 * @param sessionId     identifies the session
 * @param created       when the checkpoint was made
 * @param reference     what the measurements were made from
 * @param gridArea      the area that the points of the session cover
 * @param stepMM        the distance between the points
 * @param plannedPoints the number of points of the session
 * @param measured      the points that have been measured, in millimeters
 */
public record ProbeCheckpoint(String sessionId, Instant created, ReferenceFrame reference, ProbeArea gridArea, double stepMM,
                              int plannedPoints, List<Position> measured) {
    public ProbeCheckpoint {
        measured = List.copyOf(measured);
    }

    public boolean isComplete() {
        return measured.size() == plannedPoints;
    }
}
