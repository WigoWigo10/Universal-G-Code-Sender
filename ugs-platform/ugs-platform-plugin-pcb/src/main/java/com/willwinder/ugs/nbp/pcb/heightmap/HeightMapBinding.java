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

import com.willwinder.ugs.nbp.pcb.gcode.AlignmentMetadata;
import com.willwinder.ugs.nbp.pcb.geometry.RigidTransform2D;
import com.willwinder.universalgcodesender.gcode.util.Code;

import java.util.Locale;
import java.util.Optional;

/**
 * What a height map was measured for: where the board was, which is what its alignment tells, and
 * the work coordinate system that the heights are in. A height map can be used for every program
 * of the same board, such as its isolation, its drilling and its cutout, as long as they were
 * aligned in the same way. It can not be used once the board has been aligned again, as that is
 * what is done when the board has been moved.
 *
 * @param transform            the alignment of the board, with the translation in millimeters
 * @param workCoordinateSystem the work coordinate system that the heights were measured in
 */
public record HeightMapBinding(RigidTransform2D transform, Code workCoordinateSystem) {
    public static final String ERROR_WORK_COORDINATE_SYSTEM = "The height map was measured in %s and the program is aligned in %s.";
    public static final String ERROR_ALIGNMENT = "The height map was measured for another alignment of the board (rotation %.4f°, translation X%.3f Y%.3f mm) than the one of the program (rotation %.4f°, translation X%.3f Y%.3f mm).";

    /**
     * Alignments are the same if they were read from the same text, which has more decimals than this
     */
    private static final double TOLERANCE = 1e-9;

    public static HeightMapBinding of(AlignmentMetadata metadata) {
        return new HeightMapBinding(metadata.transform(), metadata.workCoordinateSystem());
    }

    /**
     * Tells why the height map can not be used for an aligned program, or empty if it can
     */
    public Optional<String> findMismatch(AlignmentMetadata metadata) {
        if (workCoordinateSystem != metadata.workCoordinateSystem()) {
            return Optional.of(String.format(Locale.ROOT, ERROR_WORK_COORDINATE_SYSTEM, workCoordinateSystem, metadata.workCoordinateSystem()));
        }

        RigidTransform2D other = metadata.transform();
        boolean isSame = Math.abs(transform.rotation() - other.rotation()) <= TOLERANCE
                && Math.abs(transform.translationX() - other.translationX()) <= TOLERANCE
                && Math.abs(transform.translationY() - other.translationY()) <= TOLERANCE;
        if (!isSame) {
            return Optional.of(String.format(Locale.ROOT, ERROR_ALIGNMENT,
                    Math.toDegrees(transform.rotation()), transform.translationX(), transform.translationY(),
                    Math.toDegrees(other.rotation()), other.translationX(), other.translationY()));
        }
        return Optional.empty();
    }
}
