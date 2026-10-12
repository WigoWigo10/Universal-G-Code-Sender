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

import java.util.Locale;

/**
 * What the positions of a probing are measured from: the work coordinate system and where its
 * origin is on the machine, which includes where Z is zero. Measurements are only worth anything
 * together if all of them were made with the same.
 *
 * @param workCoordinateSystem the work coordinate system
 * @param offsetX              the X of the machine at the origin, in millimeters
 * @param offsetY              the Y of the machine at the origin
 * @param offsetZ              the Z of the machine at the origin
 */
public record ReferenceFrame(Code workCoordinateSystem, double offsetX, double offsetY, double offsetZ) {
    public static final double TOLERANCE_MM = 0.0005;

    public boolean isSame(ReferenceFrame other) {
        return other != null
                && workCoordinateSystem == other.workCoordinateSystem
                && Math.abs(offsetX - other.offsetX) <= TOLERANCE_MM
                && Math.abs(offsetY - other.offsetY) <= TOLERANCE_MM
                && Math.abs(offsetZ - other.offsetZ) <= TOLERANCE_MM;
    }

    public String describe() {
        return String.format(Locale.ROOT, "%s with its origin at machine X%.3f Y%.3f Z%.3f", workCoordinateSystem, offsetX, offsetY, offsetZ);
    }
}
