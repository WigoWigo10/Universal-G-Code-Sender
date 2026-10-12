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
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UnitUtils.Units;
import org.junit.Test;

import java.util.List;

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
    public void lastRowAndColumnShouldBeOnTheEdgeOfTheArea() {
        List<Point2D> points = new GridProbePlanner(5).nextPoints(new ProbeArea(2, 3, 14, 10), List.of());

        assertEquals(4 * 3, points.size());
        assertTrue(points.contains(new Point2D(14, 10)));
        assertTrue(points.contains(new Point2D(12, 8)));
        assertTrue(points.stream().allMatch(point -> point.x() <= 14 && point.y() <= 10));
    }

    @Test
    public void areaThatIsAMultipleOfTheStepShouldNotGetAnExtraPoint() {
        assertEquals(8, new GridProbePlanner(0.1).getPointCount(0.7));
        assertEquals(4, new GridProbePlanner(2.5).getPointCount(7.5));
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
        assertEquals(area, GridHeightMap.of(measured).getArea());
    }

    @Test
    public void stepShouldBeMoreThanZero() {
        assertThrows(IllegalArgumentException.class, () -> new GridProbePlanner(0));
        assertThrows(IllegalArgumentException.class, () -> new GridProbePlanner(Double.NaN));
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
