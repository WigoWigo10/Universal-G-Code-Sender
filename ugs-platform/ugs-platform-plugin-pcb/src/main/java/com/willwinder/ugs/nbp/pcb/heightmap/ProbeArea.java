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
import com.willwinder.universalgcodesender.model.UnitUtils.Units;

import java.util.Collection;
import java.util.Optional;

/**
 * A rectangle in the XY plane of the work coordinate system, in millimeters
 */
public record ProbeArea(double minX, double minY, double maxX, double maxY) {

    public ProbeArea {
        if (!(minX <= maxX) || !(minY <= maxY)) {
            throw new IllegalArgumentException("The area must have its minimum before its maximum");
        }
    }

    /**
     * Returns the smallest area that has all the positions and a margin around them, or empty if there are none
     */
    public static Optional<ProbeArea> covering(Collection<Position> positions, double marginMM) {
        if (positions.isEmpty()) {
            return Optional.empty();
        }

        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (Position position : positions) {
            Position mm = position.getPositionIn(Units.MM);
            minX = Math.min(minX, mm.getX());
            minY = Math.min(minY, mm.getY());
            maxX = Math.max(maxX, mm.getX());
            maxY = Math.max(maxY, mm.getY());
        }
        return Optional.of(new ProbeArea(minX - marginMM, minY - marginMM, maxX + marginMM, maxY + marginMM));
    }

    public boolean contains(double x, double y) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY;
    }

    public double width() {
        return maxX - minX;
    }

    public double height() {
        return maxY - minY;
    }
}
