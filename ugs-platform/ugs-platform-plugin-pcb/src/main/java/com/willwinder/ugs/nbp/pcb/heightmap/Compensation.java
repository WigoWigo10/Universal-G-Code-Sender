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

/**
 * What a height map does to a program when it is applied, which is what the processed program is
 * validated against.
 *
 * @param surface            the measured surface
 * @param surfaceZ           the Z of the surface that the program was made for
 * @param maxSegmentLengthMM the longest move that follows the surface closely enough
 * @param id                 identifies the height map and what it was applied to
 */
public record Compensation(SurfaceModel surface, double surfaceZ, double maxSegmentLengthMM, String id) {

    /**
     * Returns what is added to the Z of the program at a position. As in the mesh leveler of the
     * application, the surface is taken to be at zero wherever it was not measured.
     */
    public double offsetAt(double x, double y) {
        return (surface.covers(x, y) ? surface.heightAt(x, y) : 0) - surfaceZ;
    }
}
