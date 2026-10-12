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

import com.willwinder.universalgcodesender.model.Position;

import java.util.List;

/**
 * The measured surface of a board: its height at any position that it covers, in millimeters in
 * the work coordinate system. The program is validated against this and the preview is drawn from
 * it, so neither depends on how the measured points are arranged. A regular grid is one way, a
 * surface from points that are closer together where the board bends more can be another.
 */
public interface SurfaceModel {
    /**
     * Returns true if the height at the position was measured or can be interpolated from measurements
     */
    boolean covers(double x, double y);

    /**
     * Returns the height of the surface at a position that is covered
     */
    double heightAt(double x, double y);

    /**
     * Returns the smallest rectangle that has all that is covered
     */
    ProbeArea getArea();

    /**
     * Returns the measured points
     */
    List<Position> getPoints();

    double getMinHeight();

    double getMaxHeight();
}
