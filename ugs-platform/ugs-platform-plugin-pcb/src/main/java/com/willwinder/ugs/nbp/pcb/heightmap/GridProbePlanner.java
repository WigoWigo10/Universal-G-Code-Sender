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
 * Plans the points of a regular grid, going back and forth along Y as the surface scanner of the
 * application does.
 * <p>
 * All points are at the same distance from each other. An area that is not a whole number of
 * steps wide or high is made larger to the next whole number, by the same amount on both sides,
 * instead of ending in a row or column that is closer to the one before it. Such a last interval
 * could be so short that two points are in almost the same place, and the mesh leveler of the
 * application finds the points around a position from a single distance for both axes.
 * <p>
 * The grid starts at a whole thousandth of a millimeter, so that its points are written in G-code
 * exactly as they were planned.
 */
public class GridProbePlanner implements ProbePlanner {
    /**
     * The positions of the grid are whole multiples of this
     */
    public static final double POSITION_RESOLUTION_MM = 0.001;

    private final double stepMM;

    /**
     * @param stepMM the distance between the points
     */
    public GridProbePlanner(double stepMM) {
        if (!(stepMM > 0) || Double.isInfinite(stepMM)) {
            throw new IllegalArgumentException("The distance between the points must be more than zero");
        }
        this.stepMM = stepMM;
    }

    @Override
    public List<Point2D> nextPoints(ProbeArea area, List<Position> measured) {
        ProbeArea grid = getGridArea(area);
        List<Point2D> result = new ArrayList<>();
        int columns = getPointCount(area.width());
        int rows = getPointCount(area.height());
        for (int column = 0; column < columns; column++) {
            for (int i = 0; i < rows; i++) {
                int row = column % 2 == 0 ? i : rows - 1 - i;
                Point2D point = new Point2D(round(grid.minX() + column * stepMM), round(grid.minY() + row * stepMM));
                if (measured.stream().noneMatch(position -> isAt(position, point))) {
                    result.add(point);
                }
            }
        }
        return result;
    }

    /**
     * Returns the area that the grid covers, which has the given area and is a whole number of steps
     * wide and high
     */
    public ProbeArea getGridArea(ProbeArea area) {
        double width = (getPointCount(area.width()) - 1) * stepMM;
        double height = (getPointCount(area.height()) - 1) * stepMM;
        double minX = Math.floor((area.minX() - (width - area.width()) / 2) / POSITION_RESOLUTION_MM + 1e-6) * POSITION_RESOLUTION_MM;
        double minY = Math.floor((area.minY() - (height - area.height()) / 2) / POSITION_RESOLUTION_MM + 1e-6) * POSITION_RESOLUTION_MM;
        return new ProbeArea(round(minX), round(minY), round(minX + width), round(minY + height));
    }

    /**
     * Returns the number of points along a side of the given length, which is at least two
     */
    public int getPointCount(double length) {
        return Math.max(1, (int) Math.ceil(length / stepMM - 1e-6)) + 1;
    }

    public double getStep() {
        return stepMM;
    }

    private static double round(double value) {
        return Math.round(value / POSITION_RESOLUTION_MM) * POSITION_RESOLUTION_MM;
    }

    private static boolean isAt(Position position, Point2D point) {
        Position mm = position.getPositionIn(Units.MM);
        return Math.hypot(mm.getX() - point.x(), mm.getY() - point.y()) <= GridHeightMap.POSITION_TOLERANCE_MM / 2;
    }
}
