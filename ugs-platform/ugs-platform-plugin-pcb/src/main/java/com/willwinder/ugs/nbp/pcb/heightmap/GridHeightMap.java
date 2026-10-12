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
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UnitUtils.Units;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * A surface that was measured on a regular grid, which is what the surface scanner of the
 * application measures and what its mesh leveler can apply. The points are in millimeters.
 * <p>
 * The grid must be as the surface scanner makes it: the same distance between the points along
 * both axes, except that the last row and the last column may be closer. The mesh leveler finds
 * the points around a position from that single distance, so it would use the wrong points of
 * any other grid.
 */
public final class GridHeightMap implements SurfaceModel {
    public static final String ERROR_NO_POINTS = "The height map has no points.";
    public static final String ERROR_INVALID_POINT = "The height map has a point without a known position or height: ";
    public static final String ERROR_TOO_FEW_POINTS = "The height map needs at least two points along each axis.";
    public static final String ERROR_DUPLICATE_POINT = "The height map has more than one height for the point ";
    public static final String ERROR_MISSING_POINT = "The height map is incomplete, it has no height for the point ";
    public static final String ERROR_SPACING = "The points of the height map are not at the same distance along X and Y, which the mesh leveler requires. Distances found: ";

    /**
     * Points that are closer than this along an axis are in the same row or column
     */
    static final double POSITION_TOLERANCE_MM = 1e-3;

    private final double[] xs;
    private final double[] ys;
    private final double[][] heights;
    private final double step;

    private GridHeightMap(double[] xs, double[] ys, double[][] heights, double step) {
        this.xs = xs;
        this.ys = ys;
        this.heights = heights;
        this.step = step;
    }

    /**
     * @param points the measured points in any order, one for every crossing of the grid
     * @throws HeightMapException if the points are not a complete regular grid
     */
    public static GridHeightMap of(Collection<Position> points) throws HeightMapException {
        if (points.isEmpty()) {
            throw new HeightMapException(ERROR_NO_POINTS);
        }

        List<Position> millimeters = new ArrayList<>();
        for (Position point : points) {
            Position position = point.getPositionIn(Units.MM);
            if (!Double.isFinite(position.getX()) || !Double.isFinite(position.getY()) || !Double.isFinite(position.getZ())) {
                throw new HeightMapException(ERROR_INVALID_POINT + format(point.getX(), point.getY()));
            }
            millimeters.add(position);
        }

        double[] xs = distinct(millimeters.stream().mapToDouble(Position::getX).toArray());
        double[] ys = distinct(millimeters.stream().mapToDouble(Position::getY).toArray());
        if (xs.length < 2 || ys.length < 2) {
            throw new HeightMapException(ERROR_TOO_FEW_POINTS);
        }

        double[][] heights = new double[xs.length][ys.length];
        for (double[] column : heights) {
            Arrays.fill(column, Double.NaN);
        }
        for (Position position : millimeters) {
            int xi = nearest(xs, position.getX());
            int yi = nearest(ys, position.getY());
            if (!Double.isNaN(heights[xi][yi])) {
                throw new HeightMapException(ERROR_DUPLICATE_POINT + format(xs[xi], ys[yi]));
            }
            heights[xi][yi] = position.getZ();
        }
        for (int xi = 0; xi < xs.length; xi++) {
            for (int yi = 0; yi < ys.length; yi++) {
                if (Double.isNaN(heights[xi][yi])) {
                    throw new HeightMapException(ERROR_MISSING_POINT + format(xs[xi], ys[yi]));
                }
            }
        }

        double step = Math.max(xs[1] - xs[0], ys[1] - ys[0]);
        if (!hasStep(xs, step) || !hasStep(ys, step)) {
            throw new HeightMapException(ERROR_SPACING + format(xs[1] - xs[0], ys[1] - ys[0]));
        }
        return new GridHeightMap(xs, ys, heights, step);
    }

    private static double[] distinct(double[] values) {
        Arrays.sort(values);
        List<Double> result = new ArrayList<>();
        for (double value : values) {
            if (result.isEmpty() || value - result.get(result.size() - 1) > POSITION_TOLERANCE_MM) {
                result.add(value);
            }
        }
        return result.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private static int nearest(double[] values, double value) {
        int result = 0;
        for (int i = 1; i < values.length; i++) {
            if (Math.abs(values[i] - value) < Math.abs(values[result] - value)) {
                result = i;
            }
        }
        return result;
    }

    private static boolean hasStep(double[] values, double step) {
        for (int i = 0; i < values.length - 1; i++) {
            double distance = values[i + 1] - values[i];
            boolean isLast = i == values.length - 2;
            if (distance > step + POSITION_TOLERANCE_MM || (!isLast && distance < step - POSITION_TOLERANCE_MM)) {
                return false;
            }
        }
        return true;
    }

    private static String format(double x, double y) {
        return String.format(Locale.ROOT, "X%.3f Y%.3f", x, y);
    }

    @Override
    public boolean covers(double x, double y) {
        return x >= xs[0] && x <= xs[xs.length - 1] && y >= ys[0] && y <= ys[ys.length - 1];
    }

    /**
     * Interpolates between the four measured points around the position
     */
    @Override
    public double heightAt(double x, double y) {
        if (!covers(x, y)) {
            throw new IllegalArgumentException("The height map does not cover " + format(x, y));
        }

        int xi = cellOf(xs, x);
        int yi = cellOf(ys, y);
        double tx = (x - xs[xi]) / (xs[xi + 1] - xs[xi]);
        double ty = (y - ys[yi]) / (ys[yi + 1] - ys[yi]);
        double low = heights[xi][yi] + tx * (heights[xi + 1][yi] - heights[xi][yi]);
        double high = heights[xi][yi + 1] + tx * (heights[xi + 1][yi + 1] - heights[xi][yi + 1]);
        return low + ty * (high - low);
    }

    private static int cellOf(double[] values, double value) {
        int result = 0;
        while (result < values.length - 2 && values[result + 1] <= value) {
            result++;
        }
        return result;
    }

    @Override
    public ProbeArea getArea() {
        return new ProbeArea(xs[0], ys[0], xs[xs.length - 1], ys[ys.length - 1]);
    }

    @Override
    public List<Position> getPoints() {
        List<Position> result = new ArrayList<>();
        for (int xi = 0; xi < xs.length; xi++) {
            for (int yi = 0; yi < ys.length; yi++) {
                result.add(new Position(xs[xi], ys[yi], heights[xi][yi], Units.MM));
            }
        }
        return result;
    }

    @Override
    public double getMinHeight() {
        return getPoints().stream().mapToDouble(Position::getZ).min().orElse(0);
    }

    @Override
    public double getMaxHeight() {
        return getPoints().stream().mapToDouble(Position::getZ).max().orElse(0);
    }

    public int getColumns() {
        return xs.length;
    }

    public int getRows() {
        return ys.length;
    }

    /**
     * Returns the distance between the points, which the last row and the last column may be closer than
     */
    public double getStep() {
        return step;
    }

    /**
     * Returns the points as the mesh leveler of the application takes them, by column and then by row
     */
    public Position[][] toMesh() {
        Position[][] result = new Position[xs.length][ys.length];
        for (int xi = 0; xi < xs.length; xi++) {
            for (int yi = 0; yi < ys.length; yi++) {
                result[xi][yi] = new Position(xs[xi], ys[yi], heights[xi][yi], Units.MM);
            }
        }
        return result;
    }

    /**
     * Returns the points as text, one on each line, which is the same for the same measurements
     */
    public List<String> toLines() {
        return getPoints().stream()
                .map(point -> String.format(Locale.ROOT, "%.6f %.6f %.6f", point.getX(), point.getY(), point.getZ()))
                .toList();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof GridHeightMap heightMap && toLines().equals(heightMap.toLines());
    }

    @Override
    public int hashCode() {
        return toLines().hashCode();
    }

    /**
     * Returns the hash of the points, which identifies the measurements
     */
    public String sha256() {
        return AlignmentMetadata.sha256(String.join("\n", toLines()).getBytes(StandardCharsets.UTF_8));
    }
}
