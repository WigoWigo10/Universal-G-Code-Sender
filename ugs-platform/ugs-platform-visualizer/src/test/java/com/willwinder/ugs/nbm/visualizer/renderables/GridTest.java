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
package com.willwinder.ugs.nbm.visualizer.renderables;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GridTest {

    @Test
    public void stepSizeShouldBeTenForProgramsOfAUsualSize() {
        assertEquals(10, Grid.getStepSize(40), 0);
        assertEquals(10, Grid.getStepSize(72), 0);
        assertEquals(10, Grid.getStepSize(400), 0);
    }

    @Test
    public void stepSizeShouldGrowWhenThereWouldBeTooManyLines() {
        assertEquals(50, Grid.getStepSize(401), 0);
        assertEquals(50, Grid.getStepSize(2000), 0);
        assertEquals(100, Grid.getStepSize(2001), 0);
    }

    @Test
    public void stepSizeShouldShrinkWhenThereWouldBeTooFewLines() {
        assertEquals(5, Grid.getStepSize(39), 0);
        assertEquals(1, Grid.getStepSize(19), 0);
        assertEquals(0.5, Grid.getStepSize(2.8), 0);
    }

    @Test
    public void stepSizeShouldStayInsideOfTheKnownSizes() {
        assertEquals(0.01, Grid.getStepSize(0), 0);
        assertEquals(0.01, Grid.getStepSize(0.0001), 0);
        assertEquals(5000, Grid.getStepSize(10_000_000), 0);
    }

    @Test
    public void stepSizeShouldNeverGiveMoreLinesThanCanBeDrawnQuickly() {
        for (double maxSide = 0.05; maxSide < 200_000; maxSide *= 1.07) {
            double cells = maxSide / Grid.getStepSize(maxSide);
            assertTrue("Got " + cells + " cells for " + maxSide, cells <= 40);
        }
    }

    @Test
    public void linesShouldEncloseWhatIsShown() {
        assertEquals(1, Grid.getFirstLine(10.4, 10));
        assertEquals(7, Grid.getLastLine(10.4, 69.6, 10));

        assertEquals(-3, Grid.getFirstLine(-20.1, 10));
        assertEquals(-1, Grid.getLastLine(-20.1, -10.5, 10));
    }

    @Test
    public void linesShouldNotAddACellToWhatEndsOnALine() {
        assertEquals(0, Grid.getFirstLine(0, 10));
        assertEquals(6, Grid.getLastLine(0, 60, 10));

        // Positions in inches are not exact in millimeters
        assertEquals(3, Grid.getFirstLine(0.3 * 25.4, 0.1 * 25.4));
        assertEquals(7, Grid.getLastLine(0.3 * 25.4, 0.7 * 25.4, 0.1 * 25.4));
    }

    @Test
    public void linesShouldMakeAtLeastOneCell() {
        assertEquals(2, Grid.getFirstLine(20, 10));
        assertEquals(3, Grid.getLastLine(20, 20, 10));
    }

    @Test
    public void labelsShouldBeThePositionOfTheLineWithoutNeedlessDecimals() {
        assertEquals("0", Grid.getLabel(0, 10));
        assertEquals("120", Grid.getLabel(12, 10));
        assertEquals("-20", Grid.getLabel(-2, 10));
        assertEquals("1.5", Grid.getLabel(3, 0.5).replace(',', '.'));
        assertEquals("0.3", Grid.getLabel(3, 0.1).replace(',', '.'));
    }
}
