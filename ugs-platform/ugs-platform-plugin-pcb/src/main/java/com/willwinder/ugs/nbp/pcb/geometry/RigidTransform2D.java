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
 * A rotation around the origin followed by a translation. It never scales or mirrors.
 *
 * @param rotation     the rotation in radians, positive is counter-clockwise. Note that this is the
 *                     opposite direction of {@code Position#rotate} in ugs-core.
 * @param translationX the translation along X applied after the rotation
 * @param translationY the translation along Y applied after the rotation
 */
public record RigidTransform2D(double rotation, double translationX, double translationY) {

    public static RigidTransform2D identity() {
        return new RigidTransform2D(0, 0, 0);
    }

    public Point2D apply(Point2D point) {
        double cos = Math.cos(rotation);
        double sin = Math.sin(rotation);
        return new Point2D(
                cos * point.x() - sin * point.y() + translationX,
                sin * point.x() + cos * point.y() + translationY);
    }
}
