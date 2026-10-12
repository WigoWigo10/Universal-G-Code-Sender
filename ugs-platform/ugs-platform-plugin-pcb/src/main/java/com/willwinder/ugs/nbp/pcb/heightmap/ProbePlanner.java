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
package com.willwinder.ugs.nbp.pcb.heightmap;

import com.willwinder.ugs.nbp.pcb.geometry.Point2D;
import com.willwinder.universalgcodesender.model.Position;

import java.util.List;

/**
 * Decides where a surface is measured. It is asked again whenever points have been measured, so
 * a planner can decide from the measurements where more points are needed: closer together
 * where the heights differ from what the points around them tell, and none where the board is flat.
 * <p>
 * A planner only decides where to measure. It does not move the machine.
 */
public interface ProbePlanner {
    /**
     * @param area     the area to measure, in millimeters in the work coordinate system
     * @param measured the points that have been measured so far, in millimeters
     * @return the points that are still to be measured in the order to measure them, empty when
     * the surface has been measured well enough
     */
    List<Point2D> nextPoints(ProbeArea area, List<Position> measured);
}
