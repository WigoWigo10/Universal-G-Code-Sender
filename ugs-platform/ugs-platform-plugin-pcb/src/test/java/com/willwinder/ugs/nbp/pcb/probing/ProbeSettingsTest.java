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

import com.willwinder.ugs.nbp.pcb.heightmap.ProbeArea;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProbeSettingsTest {
    private static final ProbeArea AREA = new ProbeArea(10, 20, 30, 40);

    private static ProbeSettings settings(double step, double maxBoardZ, double fixtureTopZ, double clearance, double probeStart,
                                          double minProbeZ, double probeFeed, double approachFeed, double plate, double limitMargin, String tool) {
        return new ProbeSettings(AREA, step, maxBoardZ, fixtureTopZ, List.of(), clearance, probeStart, minProbeZ, probeFeed, approachFeed, plate, limitMargin, tool);
    }

    @Test
    public void completeSettingsShouldHaveNoProblems() {
        assertEquals(List.of(), settings(5, 0.5, 3, 2, 1, -1, 50, 300, 0, 1, "V-bit").findProblems());
    }

    @Test
    public void travelHeightShouldBeAboveTheBoardTheFixturesAndEveryObstacle() {
        assertEquals(5, settings(5, 0.5, 3, 2, 1, -1, 50, 300, 0, 1, "V-bit").travelZ(), 1e-12);
        // The fixtures are lower than the board with its touch plate
        assertEquals(0.5 + 1.6 + 2, settings(5, 0.5, 1, 2, 1, -1, 50, 300, 1.6, 1, "V-bit").travelZ(), 1e-12);

        ProbeSettings withObstacle = new ProbeSettings(AREA, 5, 0.5, 3, List.of(new Obstacle(new ProbeArea(100, 100, 110, 110), 12), new Obstacle(new ProbeArea(0, 0, 1, 1), 7)),
                2, 1, -1, 50, 300, 0, 1, "V-bit");
        assertEquals(14, withObstacle.travelZ(), 1e-12);
    }

    @Test
    public void probingShouldStartAboveTheHighestExpectedContact() {
        assertEquals(1.5, settings(5, 0.5, 3, 2, 1, -1, 50, 300, 0, 1, "V-bit").probeStartZ(), 1e-12);
        assertEquals(3.1, settings(5, 0.5, 3, 2, 1, -1, 50, 300, 1.6, 1, "V-bit").probeStartZ(), 1e-12);
    }

    @Test
    public void everyValueShouldBeVerified() {
        assertEquals(List.of(ProbeSettings.ERROR_STEP), settings(0, 0.5, 3, 2, 1, -1, 50, 300, 0, 1, "V-bit").findProblems());
        assertEquals(List.of(ProbeSettings.ERROR_STEP), settings(2.5004, 0.5, 3, 2, 1, -1, 50, 300, 0, 1, "V-bit").findProblems());
        assertEquals(List.of(ProbeSettings.ERROR_CLEARANCE, ProbeSettings.ERROR_PROBE_START), settings(5, 0.5, 3, 0, 1, -1, 50, 300, 0, 1, "V-bit").findProblems());
        assertEquals(List.of(ProbeSettings.ERROR_PROBE_START), settings(5, 0.5, 3, 2, 0, -1, 50, 300, 0, 1, "V-bit").findProblems());
        assertEquals(List.of(ProbeSettings.ERROR_PROBE_START), settings(5, 0.5, 3, 2, 2.5, -1, 50, 300, 0, 1, "V-bit").findProblems());
        assertEquals(List.of(ProbeSettings.ERROR_MIN_PROBE_Z), settings(5, 0.5, 3, 2, 1, 0.5, 50, 300, 0, 1, "V-bit").findProblems());
        assertEquals(List.of(ProbeSettings.ERROR_FEED), settings(5, 0.5, 3, 2, 1, -1, 0, 300, 0, 1, "V-bit").findProblems());
        assertEquals(List.of(ProbeSettings.ERROR_FEED), settings(5, 0.5, 3, 2, 1, -1, 50, -1, 0, 1, "V-bit").findProblems());
        assertEquals(List.of(ProbeSettings.ERROR_TOUCH_PLATE), settings(5, 0.5, 3, 2, 1, -1, 50, 300, -0.1, 1, "V-bit").findProblems());
        assertEquals(List.of(ProbeSettings.ERROR_LIMIT_MARGIN), settings(5, 0.5, 3, 2, 1, -1, 50, 300, 0, -1, "V-bit").findProblems());
        assertEquals(List.of(ProbeSettings.ERROR_TOOL), settings(5, 0.5, 3, 2, 1, -1, 50, 300, 0, 1, "").findProblems());
        assertEquals(List.of(ProbeSettings.ERROR_TOOL), settings(5, 0.5, 3, 2, 1, -1, 50, 300, 0, 1, null).findProblems());
    }

    @Test
    public void valueThatIsNotANumberShouldBeAProblem() {
        assertEquals(List.of(ProbeSettings.ERROR_NUMBER), settings(5, Double.NaN, 3, 2, 1, -1, 50, 300, 0, 1, "V-bit").findProblems());
        assertEquals(List.of(ProbeSettings.ERROR_NUMBER), settings(5, 0.5, 3, 2, 1, Double.NEGATIVE_INFINITY, 50, 300, 0, 1, "V-bit").findProblems());
    }

    @Test
    public void planOfSettingsWithProblemsShouldHaveNoOperations() {
        ProbePlan plan = ProbePlan.create(settings(5, 0.5, 3, 2, 1, -1, 0, 300, 0, 1, "V-bit"), List.of());

        assertFalse(plan.isValid());
        assertTrue(plan.operations().isEmpty());
        assertEquals(List.of(ProbeSettings.ERROR_FEED), plan.problems());
    }

    @Test
    public void planShouldTellHowFarTheToolIsMovedBetweenThePoints() {
        ProbePlan plan = ProbePlan.create(settings(5, 0.5, 3, 2, 1, -1, 50, 300, 0, 1, "V-bit"), List.of());

        assertTrue(plan.isValid());
        assertEquals(25, plan.points().size());
        assertEquals(1 + 4 * 25, plan.operations().size());
        // Along five columns of 20 mm and four steps of 5 mm between them
        assertEquals(5 * 20 + 4 * 5, plan.getTravelLength(), 1e-9);
        assertEquals(AREA, plan.gridArea());
    }
}
