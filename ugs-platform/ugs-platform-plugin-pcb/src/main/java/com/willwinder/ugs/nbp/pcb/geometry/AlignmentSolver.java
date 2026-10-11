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

import java.util.ArrayList;
import java.util.List;

/**
 * Finds the rotation and translation that best maps nominal reference points, such as the
 * alignment holes in a PCB design, to where they were measured on the machine.
 * <p>
 * The fit is a least squares fit without scaling or mirroring, so it works for two points and
 * spreads the error evenly when more points are given. If the measured points are mirrored
 * compared to the nominal ones the fit will not hide it, it shows up as large residuals.
 */
public class AlignmentSolver {
    public static final String ERROR_POINT_COUNT_MISMATCH = "The number of nominal and measured points must be the same.";
    public static final String ERROR_NOT_ENOUGH_POINTS = "At least two reference points are required.";
    public static final String ERROR_INVALID_POINT = "All reference points must have finite coordinates.";
    public static final String ERROR_COINCIDENT_POINTS = "The reference points must not all be in the same position.";

    /**
     * The smallest spread of a point set, as the root mean square distance to its centroid,
     * for it to define a direction.
     */
    private static final double MIN_SPREAD = 1e-9;

    private AlignmentSolver() {
    }

    /**
     * @param nominal  the reference points in the coordinates of the design
     * @param measured the same points in the same order as measured on the machine
     * @throws IllegalArgumentException if the points can not define an alignment
     */
    public static AlignmentResult solve(List<Point2D> nominal, List<Point2D> measured) {
        validate(nominal, measured);

        Point2D nominalCentroid = centroid(nominal);
        Point2D measuredCentroid = centroid(measured);

        double dot = 0;
        double cross = 0;
        double nominalSquaredSpread = 0;
        double measuredSquaredSpread = 0;
        for (int i = 0; i < nominal.size(); i++) {
            double nx = nominal.get(i).x() - nominalCentroid.x();
            double ny = nominal.get(i).y() - nominalCentroid.y();
            double mx = measured.get(i).x() - measuredCentroid.x();
            double my = measured.get(i).y() - measuredCentroid.y();
            dot += nx * mx + ny * my;
            cross += nx * my - ny * mx;
            nominalSquaredSpread += nx * nx + ny * ny;
            measuredSquaredSpread += mx * mx + my * my;
        }

        double minSquaredSpread = MIN_SPREAD * MIN_SPREAD * nominal.size();
        if (nominalSquaredSpread < minSquaredSpread || measuredSquaredSpread < minSquaredSpread) {
            throw new IllegalArgumentException(ERROR_COINCIDENT_POINTS);
        }

        double rotation = Math.atan2(cross, dot);
        Point2D rotatedCentroid = new RigidTransform2D(rotation, 0, 0).apply(nominalCentroid);
        RigidTransform2D transform = new RigidTransform2D(
                rotation,
                measuredCentroid.x() - rotatedCentroid.x(),
                measuredCentroid.y() - rotatedCentroid.y());

        List<Double> residuals = new ArrayList<>();
        for (int i = 0; i < nominal.size(); i++) {
            residuals.add(transform.apply(nominal.get(i)).distance(measured.get(i)));
        }

        double scaleError = Math.sqrt(measuredSquaredSpread / nominalSquaredSpread) - 1;
        return new AlignmentResult(transform, residuals, scaleError);
    }

    private static void validate(List<Point2D> nominal, List<Point2D> measured) {
        if (nominal.size() != measured.size()) {
            throw new IllegalArgumentException(ERROR_POINT_COUNT_MISMATCH);
        }
        if (nominal.size() < 2) {
            throw new IllegalArgumentException(ERROR_NOT_ENOUGH_POINTS);
        }
        if (!nominal.stream().allMatch(Point2D::isFinite) || !measured.stream().allMatch(Point2D::isFinite)) {
            throw new IllegalArgumentException(ERROR_INVALID_POINT);
        }
    }

    private static Point2D centroid(List<Point2D> points) {
        double x = 0;
        double y = 0;
        for (Point2D point : points) {
            x += point.x();
            y += point.y();
        }
        return new Point2D(x / points.size(), y / points.size());
    }
}
