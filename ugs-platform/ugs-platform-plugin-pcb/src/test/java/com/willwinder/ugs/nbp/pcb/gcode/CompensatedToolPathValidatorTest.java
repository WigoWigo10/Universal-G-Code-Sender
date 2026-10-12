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
package com.willwinder.ugs.nbp.pcb.gcode;

import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UnitUtils.Units;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleBinaryOperator;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class CompensatedToolPathValidatorTest {
    private static final double TOLERANCE = 0.005;
    private static final DoubleBinaryOperator SURFACE = (x, y) -> 0.1 + 0.01 * x - 0.02 * y;

    private static final List<Position> EXPECTED = List.of(
            new Position(0, 0, 5, Units.MM),
            new Position(0, 0, -0.1, Units.MM),
            new Position(20, 0, -0.1, Units.MM),
            new Position(20, 10, -0.3, Units.MM),
            new Position(20, 10, 5, Units.MM));

    /**
     * The expected tool path split into short moves and moved by an offset
     */
    private static List<Position> compensated(DoubleBinaryOperator offset, double stepMM) {
        List<Position> result = new ArrayList<>();
        result.add(at(EXPECTED.get(0), offset));
        for (int i = 1; i < EXPECTED.size(); i++) {
            Position start = EXPECTED.get(i - 1);
            Position end = EXPECTED.get(i);
            int segments = Math.max(1, (int) Math.ceil(start.distanceXYZ(end) / stepMM));
            for (int segment = 1; segment <= segments; segment++) {
                double t = (double) segment / segments;
                result.add(at(new Position(
                        start.getX() + t * (end.getX() - start.getX()),
                        start.getY() + t * (end.getY() - start.getY()),
                        start.getZ() + t * (end.getZ() - start.getZ()), Units.MM), offset));
            }
        }
        return result;
    }

    private static Position at(Position position, DoubleBinaryOperator offset) {
        return new Position(position.getX(), position.getY(), position.getZ() + offset.applyAsDouble(position.getX(), position.getY()), Units.MM);
    }

    private static void assertInvalid(String expectedStart, List<Position> processed) {
        AlignmentException exception = assertThrows(AlignmentException.class,
                () -> ToolPathValidator.validate(EXPECTED, processed, TOLERANCE, 0, SURFACE));
        assertTrue(exception.getMessage(), exception.getMessage().startsWith(expectedStart));
    }

    @Test
    public void programOnTheSurfaceShouldBeValid() throws Exception {
        ToolPathValidator.validate(EXPECTED, compensated(SURFACE, 1), TOLERANCE, 0, SURFACE);
    }

    @Test
    public void programThatWasNotCompensatedShouldBeInvalid() {
        assertInvalid(ToolPathValidator.ERROR_OFF_PATH, compensated((x, y) -> 0, 1));
    }

    @Test
    public void programThatWasCompensatedTwiceShouldBeInvalid() {
        assertInvalid(ToolPathValidator.ERROR_OFF_PATH, compensated((x, y) -> 2 * SURFACE.applyAsDouble(x, y), 1));
    }

    @Test
    public void programOnAnotherSurfaceShouldBeInvalid() {
        assertInvalid(ToolPathValidator.ERROR_OFF_PATH, compensated((x, y) -> SURFACE.applyAsDouble(x, y) + 0.02, 1));
        assertInvalid(ToolPathValidator.ERROR_OFF_PATH, compensated((x, y) -> 0.1 + 0.012 * x - 0.02 * y, 1));
    }

    @Test
    public void programThatStopsEarlyShouldBeInvalid() {
        List<Position> processed = compensated(SURFACE, 1);

        assertInvalid(ToolPathValidator.ERROR_INCOMPLETE, processed.subList(0, processed.size() - 8));
    }

    @Test
    public void programThatLeavesThePathShouldBeInvalid() {
        List<Position> processed = compensated(SURFACE, 1);
        Position position = processed.get(12);
        processed.set(12, new Position(position.getX(), position.getY() + 0.05, position.getZ(), Units.MM));

        assertInvalid(ToolPathValidator.ERROR_OFF_PATH, processed);
    }

    @Test
    public void movesShouldBeShortWhereTheSurfaceWasMeasured() throws Exception {
        List<Position> processed = compensated(SURFACE, 1);

        ToolPathValidator.validateMoveLength(processed, 1.001, (x, y) -> true);

        AlignmentException exception = assertThrows(AlignmentException.class,
                () -> ToolPathValidator.validateMoveLength(compensated(SURFACE, 4), 1.001, (x, y) -> true));
        assertTrue(exception.getMessage(), exception.getMessage().startsWith(ToolPathValidator.ERROR_NOT_SPLIT));
    }

    @Test
    public void movesMayBeLongWhereTheSurfaceWasNotMeasured() throws Exception {
        ToolPathValidator.validateMoveLength(compensated(SURFACE, 50), 1.001, (x, y) -> false);

        assertThrows(AlignmentException.class,
                () -> ToolPathValidator.validateMoveLength(compensated(SURFACE, 50), 1.001, (x, y) -> x > 15));
    }
}
