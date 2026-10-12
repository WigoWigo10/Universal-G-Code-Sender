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
package com.willwinder.ugs.nbp.pcb.ui;

import com.willwinder.ugs.nbp.pcb.geometry.AlignmentResult;
import com.willwinder.ugs.nbp.pcb.geometry.AlignmentSolver;
import com.willwinder.ugs.nbp.pcb.geometry.Point2D;
import com.willwinder.universalgcodesender.gcode.util.Code;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * The reference points of an alignment as they are being entered, and what they add up to. All
 * coordinates are in millimeters. The geometry is calculated by the {@link AlignmentSolver}, this
 * only decides if its result is good enough to be applied.
 */
public class AlignmentModel {
    public static final int MIN_POINTS = 2;

    public static final String ERROR_MIN_POINTS = "An alignment needs at least two reference points.";
    public static final String ERROR_MIXED_WORK_COORDINATE_SYSTEMS = "The position is in another work coordinate system than the positions that are already measured. Clear the measured positions to measure in ";
    public static final String ERROR_WORK_COORDINATE_SYSTEM_IN_USE = "The work coordinate system can not be changed while there are positions that were measured on the machine. Clear the measured positions first.";
    public static final String ERROR_NOT_FINITE = "The position is not known.";

    /**
     * How good an alignment has to be to be applied. There are no values that are right for every
     * tool and board, so they are for the operator to decide.
     *
     * @param maxResidualMM      the largest accepted distance between a measured point and where the alignment puts it
     * @param maxScaleError      the largest accepted relative difference in size between the measured and the nominal points
     * @param minPointDistanceMM the smallest accepted distance between the two reference points that are furthest apart
     */
    public record Criteria(double maxResidualMM, double maxScaleError, double minPointDistanceMM) {
        public static final Criteria DEFAULT = new Criteria(0.05, 0.002, 10);
    }

    public enum Status {
        INCOMPLETE("Enter the nominal and the measured position of every reference point."),
        INVALID_COORDINATES("A reference point has a coordinate that is not a number."),
        INVALID_CRITERIA("The acceptance criteria must be numbers that are not negative."),
        POINTS_TOO_CLOSE("The reference points are too close to each other to give a reliable rotation."),
        COINCIDENT_POINTS("The reference points are all in the same position."),
        DISTANCE_MISMATCH("The distances between the measured points do not match the nominal ones. A point may be measured wrong or entered in the wrong row."),
        RESIDUAL_TOO_LARGE("The measured points do not fit the nominal ones well enough."),
        VALID("The alignment is within the acceptance criteria.");

        private final String description;

        Status(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * @param status what is preventing the alignment from being applied, or that it is valid
     * @param result the calculated alignment, when the points are complete enough to calculate one
     */
    public record Evaluation(Status status, Optional<AlignmentResult> result) {
        public boolean isValid() {
            return status == Status.VALID;
        }
    }

    /**
     * A reference point. A coordinate that has not been entered is null.
     *
     * @param measuredOnMachine true if the measured position was captured from the machine
     */
    public record ReferencePoint(Double nominalX, Double nominalY, Double measuredX, Double measuredY, boolean measuredOnMachine) {
        static final ReferencePoint EMPTY = new ReferencePoint(null, null, null, null, false);

        boolean isComplete() {
            return nominalX != null && nominalY != null && measuredX != null && measuredY != null;
        }
    }

    private final List<ReferencePoint> points = new ArrayList<>();
    private final List<Runnable> listeners = new ArrayList<>();
    private Code workCoordinateSystem = Code.G54;
    private Criteria criteria = Criteria.DEFAULT;

    public AlignmentModel() {
        for (int i = 0; i < MIN_POINTS; i++) {
            points.add(ReferencePoint.EMPTY);
        }
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    int getListenerCount() {
        return listeners.size();
    }

    private void changed() {
        List.copyOf(listeners).forEach(Runnable::run);
    }

    public List<ReferencePoint> getPoints() {
        return Collections.unmodifiableList(points);
    }

    public void addPoint() {
        points.add(ReferencePoint.EMPTY);
        changed();
    }

    public void removePoint(int index) {
        if (points.size() <= MIN_POINTS) {
            throw new IllegalStateException(ERROR_MIN_POINTS);
        }
        points.remove(index);
        changed();
    }

    public void setNominal(int index, Double x, Double y) {
        ReferencePoint point = points.get(index);
        points.set(index, new ReferencePoint(x, y, point.measuredX(), point.measuredY(), point.measuredOnMachine()));
        changed();
    }

    /**
     * Sets a measured position that is entered by hand, in the work coordinate system of the model
     */
    public void setMeasured(int index, Double x, Double y) {
        ReferencePoint point = points.get(index);
        points.set(index, new ReferencePoint(point.nominalX(), point.nominalY(), x, y, false));
        changed();
    }

    /**
     * Sets a measured position that was captured from the machine. All captured positions must be
     * in the same work coordinate system, which becomes the one of the alignment.
     *
     * @throws IllegalArgumentException if the position is not known or is in another work coordinate system than the others
     */
    public void setCaptured(int index, double x, double y, Code capturedWorkCoordinateSystem) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || capturedWorkCoordinateSystem == null) {
            throw new IllegalArgumentException(ERROR_NOT_FINITE);
        }

        boolean hasOtherMeasuredPoints = false;
        for (int i = 0; i < points.size(); i++) {
            ReferencePoint point = points.get(i);
            hasOtherMeasuredPoints |= i != index && (point.measuredX() != null || point.measuredY() != null);
        }
        if (hasOtherMeasuredPoints && capturedWorkCoordinateSystem != workCoordinateSystem) {
            throw new IllegalArgumentException(ERROR_MIXED_WORK_COORDINATE_SYSTEMS + capturedWorkCoordinateSystem + ". They are in " + workCoordinateSystem + ".");
        }

        ReferencePoint point = points.get(index);
        workCoordinateSystem = capturedWorkCoordinateSystem;
        points.set(index, new ReferencePoint(point.nominalX(), point.nominalY(), x, y, true));
        changed();
    }

    public void clearMeasured() {
        points.replaceAll(point -> new ReferencePoint(point.nominalX(), point.nominalY(), null, null, false));
        changed();
    }

    public Code getWorkCoordinateSystem() {
        return workCoordinateSystem;
    }

    /**
     * Sets the work coordinate system that the measured positions are entered in. It can not be changed
     * while there are positions that were captured from the machine, as they are known to be in another one.
     */
    public void setWorkCoordinateSystem(Code workCoordinateSystem) {
        if (workCoordinateSystem == this.workCoordinateSystem) {
            return;
        }
        if (workCoordinateSystem == null || workCoordinateSystem.getType() != Code.ModalGroup.WCS) {
            throw new IllegalArgumentException("Not a work coordinate system: " + workCoordinateSystem);
        }
        if (points.stream().anyMatch(ReferencePoint::measuredOnMachine)) {
            throw new IllegalStateException(ERROR_WORK_COORDINATE_SYSTEM_IN_USE);
        }
        this.workCoordinateSystem = workCoordinateSystem;
        changed();
    }

    public Criteria getCriteria() {
        return criteria;
    }

    public void setCriteria(Criteria criteria) {
        this.criteria = criteria;
        changed();
    }

    public Evaluation evaluate() {
        if (points.size() < MIN_POINTS || !points.stream().allMatch(ReferencePoint::isComplete)) {
            return new Evaluation(Status.INCOMPLETE, Optional.empty());
        }

        List<Point2D> nominal = points.stream().map(point -> new Point2D(point.nominalX(), point.nominalY())).toList();
        List<Point2D> measured = points.stream().map(point -> new Point2D(point.measuredX(), point.measuredY())).toList();
        if (!nominal.stream().allMatch(Point2D::isFinite) || !measured.stream().allMatch(Point2D::isFinite)) {
            return new Evaluation(Status.INVALID_COORDINATES, Optional.empty());
        }
        if (!isValid(criteria.maxResidualMM()) || !isValid(criteria.maxScaleError()) || !isValid(criteria.minPointDistanceMM())) {
            return new Evaluation(Status.INVALID_CRITERIA, Optional.empty());
        }

        AlignmentResult result;
        try {
            result = AlignmentSolver.solve(nominal, measured);
        } catch (IllegalArgumentException e) {
            return new Evaluation(Status.COINCIDENT_POINTS, Optional.empty());
        }

        Status status = Status.VALID;
        if (largestDistance(nominal) < criteria.minPointDistanceMM() || largestDistance(measured) < criteria.minPointDistanceMM()) {
            status = Status.POINTS_TOO_CLOSE;
        } else if (Math.abs(result.scaleError()) > criteria.maxScaleError()) {
            status = Status.DISTANCE_MISMATCH;
        } else if (result.maxResidual() > criteria.maxResidualMM()) {
            status = Status.RESIDUAL_TOO_LARGE;
        }
        return new Evaluation(status, Optional.of(result));
    }

    private static boolean isValid(double criterion) {
        return Double.isFinite(criterion) && criterion >= 0;
    }

    private static double largestDistance(List<Point2D> points) {
        double result = 0;
        for (int i = 0; i < points.size(); i++) {
            for (int j = i + 1; j < points.size(); j++) {
                result = Math.max(result, points.get(i).distance(points.get(j)));
            }
        }
        return result;
    }
}
