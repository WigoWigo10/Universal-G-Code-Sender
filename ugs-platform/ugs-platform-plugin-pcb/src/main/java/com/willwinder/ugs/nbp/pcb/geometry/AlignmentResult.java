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

import java.util.List;

/**
 * The result of fitting a set of nominal points to their measured positions.
 *
 * @param transform  maps nominal coordinates to measured coordinates
 * @param residuals  the distance between each transformed nominal point and its measured point
 * @param scaleError the relative size difference between the measured and the nominal point sets,
 *                   0.01 means the measured points are spread 1% wider than the nominal ones. A rigid
 *                   transform can not correct this, so it indicates a measurement or setup error.
 */
public record AlignmentResult(RigidTransform2D transform, List<Double> residuals, double scaleError) {

    public AlignmentResult {
        residuals = List.copyOf(residuals);
    }

    public double maxResidual() {
        return residuals.stream().mapToDouble(Double::doubleValue).max().orElse(0);
    }

    public double rmsResidual() {
        return Math.sqrt(residuals.stream().mapToDouble(r -> r * r).average().orElse(0));
    }
}
