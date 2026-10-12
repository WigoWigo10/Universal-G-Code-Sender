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
import java.util.function.DoubleBinaryOperator;

/**
 * Makes height maps of surfaces that are given as a function, as if they had been measured
 */
public final class SyntheticSurface {
    /**
     * A board that is tilted and slightly bent, with heights within a few tenths of a millimeter
     */
    public static final DoubleBinaryOperator BENT_BOARD = (x, y) -> 0.05 + 0.002 * x - 0.003 * y + 0.00004 * x * y;

    private SyntheticSurface() {
    }

    public static List<Position> measure(ProbeArea area, double stepMM, DoubleBinaryOperator height) {
        List<Position> result = new ArrayList<>();
        for (Point2D point : new GridProbePlanner(stepMM).nextPoints(area, List.of())) {
            result.add(new Position(point.x(), point.y(), height.applyAsDouble(point.x(), point.y()), Units.MM));
        }
        return result;
    }

    public static GridHeightMap heightMap(ProbeArea area, double stepMM, DoubleBinaryOperator height) throws HeightMapException {
        return GridHeightMap.of(measure(area, stepMM, height));
    }
}
