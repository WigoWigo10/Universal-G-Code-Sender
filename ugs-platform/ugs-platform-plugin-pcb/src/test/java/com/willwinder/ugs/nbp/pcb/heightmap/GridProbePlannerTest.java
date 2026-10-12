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

import com.willwinder.ugs.nbp.pcb.geometry.Point2D;
import com.willwinder.universalgcodesender.gcode.GcodeParser;
import com.willwinder.universalgcodesender.gcode.GcodeState;
import com.willwinder.universalgcodesender.gcode.processors.MeshLeveler;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UnitUtils.Units;
import org.junit.Test;

import java.util.List;
import java.util.Locale;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class GridProbePlannerTest {

    @Test
    public void pointsShouldGoBackAndForthAlongY() {
        List<Point2D> points = new GridProbePlanner(5).nextPoints(new ProbeArea(0, 0, 10, 10), List.of());

        assertEquals(List.of(
                new Point2D(0, 0), new Point2D(0, 5), new Point2D(0, 10),
                new Point2D(5, 10), new Point2D(5, 5), new Point2D(5, 0),
                new Point2D(10, 0), new Point2D(10, 5), new Point2D(10, 10)), points);
    }

    @Test
    public void areaThatIsNotAMultipleOfTheStepShouldBeMadeLargerOnBothSides() {
        GridProbePlanner planner = new GridProbePlanner(5);
        ProbeArea area = new ProbeArea(2, 3, 14, 10);

        ProbeArea grid = planner.getGridArea(area);
        List<Point2D> points = planner.nextPoints(area, List.of());

        // 12 mm becomes 15 mm and 7 mm becomes 10 mm
        assertEquals(new ProbeArea(0.5, 1.5, 15.5, 11.5), grid);
        assertEquals(4 * 3, points.size());
        assertEquals(new Point2D(0.5, 1.5), points.get(0));
        assertTrue(points.contains(new Point2D(15.5, 11.5)));
        assertTrue(points.contains(new Point2D(5.5, 6.5)));
    }

    @Test
    public void allPointsShouldBeAtTheSameDistanceForAnyArea() {
        Random random = new Random(3);
        for (int i = 0; i < 300; i++) {
            double step = Math.round((0.5 + 9.5 * random.nextDouble()) * 1000) / 1000.0;
            ProbeArea area = new ProbeArea(-50 + 100 * random.nextDouble(), -50 + 100 * random.nextDouble(), 60 + 60 * random.nextDouble(), 60 + 60 * random.nextDouble());
            GridProbePlanner planner = new GridProbePlanner(step);

            ProbeArea grid = planner.getGridArea(area);
            List<Point2D> points = planner.nextPoints(area, List.of());

            String description = area + " every " + step;
            assertTrue(description, grid.minX() <= area.minX() + 1e-9 && grid.minY() <= area.minY() + 1e-9);
            assertTrue(description, grid.maxX() >= area.maxX() - 1e-9 && grid.maxY() >= area.maxY() - 1e-9);
            // Never more than a step larger than was asked for
            assertTrue(description, grid.width() < area.width() + step + 0.002 && grid.height() < area.height() + step + 0.002);
            int rows = planner.getPointCount(area.height());
            assertEquals(description, planner.getPointCount(area.width()) * rows, points.size());
            for (int index = 1; index < points.size(); index++) {
                // The next point of the path is always one step away
                assertEquals(description, step, points.get(index - 1).distance(points.get(index)), 1e-6);
            }
            for (Point2D point : points) {
                assertEquals(description, Math.rint(point.x() * 1000), point.x() * 1000, 1e-6);
                assertEquals(description, Math.rint(point.y() * 1000), point.y() * 1000, 1e-6);
            }
        }
    }

    @Test
    public void areaThatIsAMultipleOfTheStepShouldNotBeMadeLarger() {
        assertEquals(8, new GridProbePlanner(0.1).getPointCount(0.7));
        assertEquals(4, new GridProbePlanner(2.5).getPointCount(7.5));
        assertEquals(new ProbeArea(10, 20, 30, 40), new GridProbePlanner(5).getGridArea(new ProbeArea(10, 20, 30, 40)));
        assertEquals(new ProbeArea(-0.3, 0.1, 0.4, 0.3), new GridProbePlanner(0.1).getGridArea(new ProbeArea(-0.3, 0.1, 0.4, 0.3)));
    }

    @Test
    public void areaThatIsSmallerThanAStepShouldStillHaveFourPoints() {
        GridProbePlanner planner = new GridProbePlanner(5);

        assertEquals(new ProbeArea(-1.5, -2, 3.5, 3), planner.getGridArea(new ProbeArea(0, 0, 2, 1)));
        assertEquals(4, planner.nextPoints(new ProbeArea(0, 0, 2, 1), List.of()).size());
        assertEquals(4, planner.nextPoints(new ProbeArea(7, 7, 7, 7), List.of()).size());
    }

    /**
     * The mesh leveler finds the points around a position from one distance for both axes, so a
     * height map of the planner must be one that it reads the right heights from, everywhere.
     */
    @Test
    public void meshLevelerShouldApplyTheHeightsOfAnyPlannedGrid() throws Exception {
        Random random = new Random(11);
        for (int i = 0; i < 40; i++) {
            double step = Math.round((1 + 7 * random.nextDouble()) * 1000) / 1000.0;
            ProbeArea area = new ProbeArea(-30 + 60 * random.nextDouble(), -30 + 60 * random.nextDouble(), 31 + 40 * random.nextDouble(), 31 + 40 * random.nextDouble());
            GridHeightMap map = SyntheticSurface.heightMap(area, step, SyntheticSurface.BENT_BOARD);
            MeshLeveler leveler = new MeshLeveler(0, map.toMesh());
            ProbeArea grid = map.getArea();

            for (int sample = 0; sample < 60; sample++) {
                double x = grid.minX() + grid.width() * random.nextDouble();
                double y = grid.minY() + grid.height() * random.nextDouble();
                GcodeState state = new GcodeState();
                state.currentPoint = new Position(0, 0, 0, Units.MM);
                String command = String.format(Locale.ROOT, "G1 X%.4f Y%.4f Z0", x, y);

                String leveled = leveler.processCommand(command, state).get(0);

                GcodeParser parser = new GcodeParser();
                parser.addCommand("G21 G90");
                parser.addCommand("G0 X0 Y0 Z0");
                parser.addCommand(leveled);
                Position end = parser.getCurrentState().currentPoint;
                assertEquals(area + " every " + step + ": " + command, map.heightAt(end.getX(), end.getY()), end.getZ(), 0.001);
            }
        }
    }

    @Test
    public void measuredPointsShouldNotBePlannedAgain() {
        GridProbePlanner planner = new GridProbePlanner(5);
        ProbeArea area = new ProbeArea(0, 0, 10, 10);
        List<Position> measured = List.of(
                new Position(0, 0, 0.1, Units.MM),
                new Position(0, 5, 0.1, Units.MM),
                new Position(5, 5, 0.1, Units.MM));

        List<Point2D> points = planner.nextPoints(area, measured);

        assertEquals(List.of(
                new Point2D(0, 10), new Point2D(5, 10), new Point2D(5, 0),
                new Point2D(10, 0), new Point2D(10, 5), new Point2D(10, 10)), points);
    }

    @Test
    public void nothingShouldBeLeftWhenAllPointsAreMeasured() throws Exception {
        GridProbePlanner planner = new GridProbePlanner(5);
        ProbeArea area = new ProbeArea(2, 3, 14, 10);

        List<Position> measured = SyntheticSurface.measure(area, 5, SyntheticSurface.BENT_BOARD);

        assertTrue(planner.nextPoints(area, measured).isEmpty());
        assertEquals(planner.getGridArea(area), GridHeightMap.of(measured).getArea());
    }

    @Test
    public void stepShouldBeMoreThanZero() {
        assertThrows(IllegalArgumentException.class, () -> new GridProbePlanner(0));
        assertThrows(IllegalArgumentException.class, () -> new GridProbePlanner(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new GridProbePlanner(Double.POSITIVE_INFINITY));
    }

    @Test
    public void areaShouldCoverPositionsWithAMargin() {
        List<Position> positions = List.of(
                new Position(10, 20, -0.1, Units.MM),
                new Position(1, 2, -0.1, Units.INCH),
                new Position(15, 5, -0.1, Units.MM));

        assertEquals(new ProbeArea(8, 3, 27.4, 52.8), ProbeArea.covering(positions, 2).orElseThrow());
        assertTrue(ProbeArea.covering(List.of(), 2).isEmpty());
    }
}
