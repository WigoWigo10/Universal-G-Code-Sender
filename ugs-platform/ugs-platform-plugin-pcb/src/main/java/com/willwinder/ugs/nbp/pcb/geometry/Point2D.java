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
package com.willwinder.ugs.nbp.pcb.geometry;

/**
 * A point in the XY plane. The unit is defined by the caller and must be the same
 * for all points used together.
 */
public record Point2D(double x, double y) {

    public double distance(Point2D other) {
        return Math.hypot(other.x - x, other.y - y);
    }

    public boolean isFinite() {
        return Double.isFinite(x) && Double.isFinite(y);
    }
}
