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

import com.willwinder.universalgcodesender.gcode.GcodeParser;
import com.willwinder.universalgcodesender.gcode.GcodeState;
import com.willwinder.universalgcodesender.gcode.processors.MeshLeveler;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UnitUtils.Units;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class GridHeightMapTest {
    private static final ProbeArea AREA = new ProbeArea(10, 20, 30, 50);

    private static List<Position> points(double... xyz) {
        List<Position> result = new ArrayList<>();
        for (int i = 0; i < xyz.length; i += 3) {
            result.add(new Position(xyz[i], xyz[i + 1], xyz[i + 2], Units.MM));
        }
        return result;
    }

    @Test
    public void heightShouldBeTheMeasuredHeightAtAPoint() throws Exception {
        GridHeightMap map = SyntheticSurface.heightMap(AREA, 5, SyntheticSurface.BENT_BOARD);

        for (Position point : map.getPoints()) {
            assertEquals(SyntheticSurface.BENT_BOARD.applyAsDouble(point.getX(), point.getY()), map.heightAt(point.getX(), point.getY()), 1e-12);
        }
    }

    @Test
    public void heightShouldBeInterpolatedBetweenThePoints() throws Exception {
        GridHeightMap map = GridHeightMap.of(points(
                0, 0, 0.0,
                10, 0, 0.1,
                0, 10, 0.2,
                10, 10, 0.5));

        assertEquals(0.05, map.heightAt(5, 0), 1e-12);
        assertEquals(0.1, map.heightAt(0, 5), 1e-12);
        assertEquals(0.35, map.heightAt(5, 10), 1e-12);
        assertEquals(0.2, map.heightAt(5, 5), 1e-12);
        assertEquals(0.5, map.heightAt(10, 10), 1e-12);
    }

    @Test
    public void heightOfATiltedPlaneShouldBeExactEverywhere() throws Exception {
        GridHeightMap map = SyntheticSurface.heightMap(AREA, 5, (x, y) -> 0.01 * x - 0.02 * y + 0.3);

        assertEquals(0.01 * 17.3 - 0.02 * 41.9 + 0.3, map.heightAt(17.3, 41.9), 1e-12);
    }

    @Test
    public void mapShouldCoverItsAreaAndNothingElse() throws Exception {
        GridHeightMap map = SyntheticSurface.heightMap(AREA, 5, SyntheticSurface.BENT_BOARD);

        assertEquals(AREA, map.getArea());
        assertTrue(map.covers(10, 20));
        assertTrue(map.covers(30, 50));
        assertTrue(map.covers(17, 33));
        assertFalse(map.covers(9.99, 33));
        assertFalse(map.covers(17, 50.01));
        assertThrows(IllegalArgumentException.class, () -> map.heightAt(9.99, 33));
    }

    @Test
    public void pointsShouldBeAcceptedInAnyOrder() throws Exception {
        List<Position> measured = SyntheticSurface.measure(AREA, 5, SyntheticSurface.BENT_BOARD);
        GridHeightMap expected = GridHeightMap.of(measured);
        Collections.shuffle(measured, new Random(1));

        GridHeightMap map = GridHeightMap.of(measured);

        assertEquals(expected.toLines(), map.toLines());
        assertEquals(expected.sha256(), map.sha256());
    }

    @Test
    public void pointsInInchesShouldBeKeptInMillimeters() throws Exception {
        GridHeightMap map = GridHeightMap.of(List.of(
                new Position(0, 0, 0.001, Units.INCH),
                new Position(1, 0, 0.002, Units.INCH),
                new Position(0, 1, 0.003, Units.INCH),
                new Position(1, 1, 0.004, Units.INCH)));

        assertEquals(new ProbeArea(0, 0, 25.4, 25.4), map.getArea());
        assertEquals(0.004 * 25.4, map.heightAt(25.4, 25.4), 1e-12);
        assertEquals(25.4, map.getStep(), 1e-12);
    }

    @Test
    public void lastRowAndColumnMayBeCloserAsTheSurfaceScannerMakesThem() throws Exception {
        List<Position> measured = new ArrayList<>();
        for (double x : new double[]{0, 5, 10, 12}) {
            for (double y : new double[]{0, 5, 7}) {
                measured.add(new Position(x, y, SyntheticSurface.BENT_BOARD.applyAsDouble(x, y), Units.MM));
            }
        }
        GridHeightMap map = GridHeightMap.of(measured);

        assertEquals(4, map.getColumns());
        assertEquals(3, map.getRows());
        assertEquals(5, map.getStep(), 1e-12);
        assertEquals(new ProbeArea(0, 0, 12, 7), map.getArea());
    }

    @Test
    public void mapWithoutAllPointsShouldBeRejected() {
        List<Position> measured = SyntheticSurface.measure(AREA, 5, SyntheticSurface.BENT_BOARD);
        measured.remove(7);

        HeightMapException exception = assertThrows(HeightMapException.class, () -> GridHeightMap.of(measured));

        assertTrue(exception.getMessage(), exception.getMessage().startsWith(GridHeightMap.ERROR_MISSING_POINT));
    }

    @Test
    public void mapWithTwoHeightsForAPointShouldBeRejected() {
        List<Position> measured = SyntheticSurface.measure(AREA, 5, SyntheticSurface.BENT_BOARD);
        measured.add(new Position(15, 25, 0.9, Units.MM));

        HeightMapException exception = assertThrows(HeightMapException.class, () -> GridHeightMap.of(measured));

        assertTrue(exception.getMessage(), exception.getMessage().startsWith(GridHeightMap.ERROR_DUPLICATE_POINT));
    }

    @Test
    public void mapWithUnknownHeightShouldBeRejected() {
        List<Position> measured = SyntheticSurface.measure(AREA, 5, SyntheticSurface.BENT_BOARD);
        measured.set(3, new Position(measured.get(3).getX(), measured.get(3).getY(), Double.NaN, Units.MM));

        HeightMapException exception = assertThrows(HeightMapException.class, () -> GridHeightMap.of(measured));

        assertTrue(exception.getMessage(), exception.getMessage().startsWith(GridHeightMap.ERROR_INVALID_POINT));
    }

    @Test
    public void mapWithTooFewPointsShouldBeRejected() {
        assertEquals(GridHeightMap.ERROR_NO_POINTS, assertThrows(HeightMapException.class, () -> GridHeightMap.of(List.of())).getMessage());
        assertEquals(GridHeightMap.ERROR_TOO_FEW_POINTS, assertThrows(HeightMapException.class, () -> GridHeightMap.of(points(
                0, 0, 0,
                0, 5, 0,
                0, 10, 0))).getMessage());
    }

    @Test
    public void mapWithOtherDistanceAlongXThanAlongYShouldBeRejected() {
        // The mesh leveler would look up the wrong points in a grid like this
        List<Position> measured = new ArrayList<>();
        for (int xi = 0; xi < 4; xi++) {
            for (int yi = 0; yi < 4; yi++) {
                measured.add(new Position(xi * 2, yi * 5, 0, Units.MM));
            }
        }

        HeightMapException exception = assertThrows(HeightMapException.class, () -> GridHeightMap.of(measured));

        assertTrue(exception.getMessage(), exception.getMessage().startsWith(GridHeightMap.ERROR_SPACING));
    }

    /**
     * The height map is what the processed program is validated against, so it has to tell the same
     * heights as the mesh leveler applies.
     */
    @Test
    public void heightShouldBeTheSameAsTheMeshLevelerApplies() throws Exception {
        // As the surface scanner measures it: the last row and the last column are closer
        List<Position> measured = new ArrayList<>();
        for (double x : new double[]{-7, -2, 3, 8, 13, 18, 23, 28, 33, 36}) {
            for (double y : new double[]{3, 8, 13, 18, 23, 28, 33, 38, 43, 44}) {
                measured.add(new Position(x, y, SyntheticSurface.BENT_BOARD.applyAsDouble(x, y), Units.MM));
            }
        }
        GridHeightMap map = GridHeightMap.of(measured);
        MeshLeveler leveler = new MeshLeveler(0, map.toMesh());
        Random random = new Random(7);

        for (int i = 0; i < 500; i++) {
            double x = -7 + 43 * random.nextDouble();
            double y = 3 + 41 * random.nextDouble();
            GcodeState state = new GcodeState();
            state.currentPoint = new Position(0, 0, 0, Units.MM);
            String command = String.format(Locale.ROOT, "G1 X%.4f Y%.4f Z0", x, y);

            String leveled = leveler.processCommand(command, state).get(0);

            GcodeParser parser = new GcodeParser();
            parser.addCommand("G21 G90");
            parser.addCommand("G0 X0 Y0 Z0");
            parser.addCommand(leveled);
            Position end = parser.getCurrentState().currentPoint;
            assertEquals(command, map.heightAt(end.getX(), end.getY()), end.getZ(), 0.001);
        }
    }

    @Test
    public void meshForTheLevelerShouldHaveThePointsByColumn() throws Exception {
        GridHeightMap map = SyntheticSurface.heightMap(AREA, 5, SyntheticSurface.BENT_BOARD);

        Position[][] mesh = map.toMesh();

        assertEquals(5, mesh.length);
        assertEquals(7, mesh[0].length);
        assertEquals(new Position(10, 20, SyntheticSurface.BENT_BOARD.applyAsDouble(10, 20), Units.MM), mesh[0][0]);
        assertEquals(new Position(15, 30, SyntheticSurface.BENT_BOARD.applyAsDouble(15, 30), Units.MM), mesh[1][2]);
    }
}
