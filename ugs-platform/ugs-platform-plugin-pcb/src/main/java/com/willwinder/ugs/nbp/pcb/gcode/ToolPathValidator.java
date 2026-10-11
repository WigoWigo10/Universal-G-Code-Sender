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

import com.willwinder.universalgcodesender.gcode.GcodeParser;
import com.willwinder.universalgcodesender.gcode.util.GcodeParserException;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UnitUtils.Units;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Verifies that a processed program, which is what will be sent to the machine, still follows
 * the tool path of the program it was made from. The processed program may have its moves split
 * into shorter moves and its Z adjusted within a limit, as the auto leveler does, but it must
 * visit every position of the expected program in order, never leave its path and reach its end.
 * A program that was only partly processed is therefore not valid.
 */
public class ToolPathValidator {
    public static final String ERROR_NO_MOVES = "The program has no moves to validate.";
    public static final String ERROR_OFF_PATH = "The processed program leaves the expected tool path at ";
    public static final String ERROR_INCOMPLETE = "The processed program ends before the expected program does, it stops at ";

    private ToolPathValidator() {
    }

    /**
     * Returns every fully known position in millimeters that the program moves to
     */
    public static List<Position> trace(Iterable<String> commands) throws GcodeParserException {
        GcodeParser parser = new GcodeParser();
        List<Position> result = new ArrayList<>();
        for (String command : commands) {
            parser.addCommand(command);
            Position position = parser.getCurrentState().currentPoint.getPositionIn(Units.MM);
            boolean isKnown = Double.isFinite(position.getX()) && Double.isFinite(position.getY()) && Double.isFinite(position.getZ());
            if (isKnown && (result.isEmpty() || !isNear(position, result.get(result.size() - 1), 0, 0))) {
                result.add(position);
            }
        }
        return result;
    }

    /**
     * @param expected      the positions of the program that the processed program was made from
     * @param processed     the positions of the processed program
     * @param toleranceMM   the largest accepted distance from the expected tool path
     * @param maxZChangeMM  the largest accepted change of Z, zero if Z must not have been changed
     * @throws AlignmentException if the processed program does not follow the expected tool path
     */
    public static void validate(List<Position> expected, List<Position> processed, double toleranceMM, double maxZChangeMM) throws AlignmentException {
        if (expected.isEmpty() || processed.isEmpty()) {
            throw new AlignmentException(ERROR_NO_MOVES);
        }

        double zTolerance = toleranceMM + maxZChangeMM;
        int last = expected.size() - 1;

        // The moves of the expected program that the processed program may currently be on. There can be
        // more than one when expected positions are closer to each other than the tolerance.
        SortedSet<Integer> current = new TreeSet<>(Set.of(0));
        for (Position position : processed) {
            SortedSet<Integer> next = new TreeSet<>();
            for (int candidate : current) {
                for (int i = candidate; i <= last; i++) {
                    boolean isOnMove = i == last
                            ? isNear(position, expected.get(last), toleranceMM, zTolerance)
                            : isOnSegment(position, expected.get(i), expected.get(i + 1), toleranceMM, zTolerance);
                    if (isOnMove) {
                        next.add(i);
                    }

                    // Only continue to the following move if this one has been completed
                    if (i == last || !isNear(position, expected.get(i + 1), toleranceMM, zTolerance)) {
                        break;
                    }
                }
            }

            if (next.isEmpty()) {
                throw new AlignmentException(ERROR_OFF_PATH + format(position));
            }
            current = next;
        }

        if (!current.contains(last)) {
            throw new AlignmentException(ERROR_INCOMPLETE + format(processed.get(processed.size() - 1)));
        }
    }

    private static boolean isNear(Position position, Position expected, double tolerance, double zTolerance) {
        return Math.hypot(position.getX() - expected.getX(), position.getY() - expected.getY()) <= tolerance
                && Math.abs(position.getZ() - expected.getZ()) <= zTolerance;
    }

    private static boolean isOnSegment(Position position, Position start, Position end, double tolerance, double zTolerance) {
        double dx = end.getX() - start.getX();
        double dy = end.getY() - start.getY();
        double lengthSquared = dx * dx + dy * dy;

        // A move in only Z
        if (lengthSquared <= tolerance * tolerance) {
            return Math.hypot(position.getX() - start.getX(), position.getY() - start.getY()) <= tolerance
                    && position.getZ() >= Math.min(start.getZ(), end.getZ()) - zTolerance
                    && position.getZ() <= Math.max(start.getZ(), end.getZ()) + zTolerance;
        }

        double t = ((position.getX() - start.getX()) * dx + (position.getY() - start.getY()) * dy) / lengthSquared;
        t = Math.max(0, Math.min(1, t));
        double distance = Math.hypot(position.getX() - (start.getX() + t * dx), position.getY() - (start.getY() + t * dy));
        double expectedZ = start.getZ() + t * (end.getZ() - start.getZ());
        return distance <= tolerance && Math.abs(position.getZ() - expectedZ) <= zTolerance;
    }

    private static String format(Position position) {
        return String.format(Locale.ROOT, "X%.4f Y%.4f Z%.4f", position.getX(), position.getY(), position.getZ());
    }
}
