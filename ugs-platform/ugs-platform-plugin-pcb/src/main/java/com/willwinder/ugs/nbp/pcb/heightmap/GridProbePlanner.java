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
import com.willwinder.universalgcodesender.model.UnitUtils.Units;

import java.util.ArrayList;
import java.util.List;

/**
 * Plans the points of a regular grid, in the same places and in the same order as the surface
 * scanner of the application: the same distance between the points, with the last row and the last
 * column on the edge of the area, going back and forth along Y.
 */
public class GridProbePlanner implements ProbePlanner {
    private final double stepMM;

    /**
     * @param stepMM the distance between the points
     */
    public GridProbePlanner(double stepMM) {
        if (!(stepMM > 0)) {
            throw new IllegalArgumentException("The distance between the points must be more than zero");
        }
        this.stepMM = stepMM;
    }

    @Override
    public List<Point2D> nextPoints(ProbeArea area, List<Position> measured) {
        List<Point2D> result = new ArrayList<>();
        int columns = getPointCount(area.width());
        int rows = getPointCount(area.height());
        for (int column = 0; column < columns; column++) {
            for (int i = 0; i < rows; i++) {
                int row = column % 2 == 0 ? i : rows - 1 - i;
                Point2D point = new Point2D(
                        area.minX() + Math.min(area.width(), column * stepMM),
                        area.minY() + Math.min(area.height(), row * stepMM));
                if (measured.stream().noneMatch(position -> isAt(position, point))) {
                    result.add(point);
                }
            }
        }
        return result;
    }

    /**
     * Returns the number of points along a side of the given length
     */
    public int getPointCount(double length) {
        return (int) Math.ceil(length / stepMM - 1e-9) + 1;
    }

    private static boolean isAt(Position position, Point2D point) {
        Position mm = position.getPositionIn(Units.MM);
        return Math.hypot(mm.getX() - point.x(), mm.getY() - point.y()) <= GridHeightMap.POSITION_TOLERANCE_MM;
    }
}
