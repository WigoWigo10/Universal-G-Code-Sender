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

import com.willwinder.ugs.nbp.pcb.geometry.Point2D;
import com.willwinder.ugs.nbp.pcb.heightmap.GridProbePlanner;
import com.willwinder.ugs.nbp.pcb.heightmap.ProbeArea;
import com.willwinder.ugs.nbp.pcb.heightmap.ProbePlanner;
import com.willwinder.ugs.nbp.pcb.probing.ProbeOperation.Kind;
import com.willwinder.universalgcodesender.model.Position;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Everything that a probing will do, decided before anything moves: the points and every move
 * between them. For every point the tool is moved above it at the travel height, lowered to where
 * probing starts, lowered until the sensor has contact, and raised to the travel height again.
 *
 * @param settings   what the plan was made from
 * @param gridArea   the area that the points cover
 * @param points     the points that are still to be measured, in the order to measure them
 * @param operations what the machine does, in order
 * @param problems   why the plan must not be carried out, empty if it may
 */
public record ProbePlan(ProbeSettings settings, ProbeArea gridArea, List<Point2D> points, List<ProbeOperation> operations, List<String> problems) {
    public static final String ERROR_NO_POINTS = "There are no points to measure.";
    public static final String ERROR_ON_OBSTACLE = "A point to measure is on an obstacle: ";

    /**
     * Plans a regular grid over the area of the settings
     *
     * @param measured the points that have been measured already, which are not planned again
     */
    public static ProbePlan create(ProbeSettings settings, List<Position> measured) {
        List<String> problems = new ArrayList<>(settings.findProblems());
        if (!problems.isEmpty()) {
            return new ProbePlan(settings, settings.area(), List.of(), List.of(), problems);
        }
        GridProbePlanner planner = new GridProbePlanner(settings.stepMM());
        return create(settings, planner, planner.getGridArea(settings.area()), measured);
    }

    /**
     * @param planner  decides where to measure
     * @param gridArea the area that the points of the planner cover
     * @param measured the points that have been measured already
     */
    public static ProbePlan create(ProbeSettings settings, ProbePlanner planner, ProbeArea gridArea, List<Position> measured) {
        List<String> problems = new ArrayList<>(settings.findProblems());
        if (!problems.isEmpty()) {
            return new ProbePlan(settings, gridArea, List.of(), List.of(), problems);
        }

        List<Point2D> points = planner.nextPoints(settings.area(), measured);
        if (points.isEmpty() && measured.isEmpty()) {
            problems.add(ERROR_NO_POINTS);
        }
        for (Point2D point : points) {
            for (Obstacle obstacle : settings.obstacles()) {
                if (obstacle.area().contains(point.x(), point.y())) {
                    problems.add(ERROR_ON_OBSTACLE + String.format(Locale.ROOT, "X%.3f Y%.3f", point.x(), point.y()));
                }
            }
        }

        List<ProbeOperation> operations = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            Point2D point = points.get(i);
            // Before the first point the tool is where the operator left it, and is only raised there
            if (i == 0) {
                operations.add(new ProbeOperation(Kind.RETRACT, i, Double.NaN, Double.NaN, settings.travelZ(), 0));
            }
            operations.add(new ProbeOperation(Kind.MOVE_XY, i, point.x(), point.y(), settings.travelZ(), 0));
            operations.add(new ProbeOperation(Kind.APPROACH, i, point.x(), point.y(), settings.probeStartZ(), settings.approachFeed()));
            operations.add(new ProbeOperation(Kind.PROBE, i, point.x(), point.y(), settings.minProbeZ(), settings.probeFeed()));
            operations.add(new ProbeOperation(Kind.RETRACT, i, point.x(), point.y(), settings.travelZ(), 0));
        }
        return new ProbePlan(settings, gridArea, List.copyOf(points), List.copyOf(operations), List.copyOf(problems));
    }

    public boolean isValid() {
        return problems.isEmpty();
    }

    /**
     * Returns how far the tool is moved in X and Y between the points, from the first point on
     */
    public double getTravelLength() {
        double result = 0;
        for (int i = 1; i < points.size(); i++) {
            result += points.get(i - 1).distance(points.get(i));
        }
        return result;
    }
}
