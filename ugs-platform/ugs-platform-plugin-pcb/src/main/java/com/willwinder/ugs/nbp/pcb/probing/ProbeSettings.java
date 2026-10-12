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

import com.willwinder.ugs.nbp.pcb.heightmap.GridProbePlanner;
import com.willwinder.ugs.nbp.pcb.heightmap.ProbeArea;

import java.util.ArrayList;
import java.util.List;

/**
 * All that the operator decides about a probing. None of it has a default: what is safe depends on
 * the machine, the sensor and how the board is held, so every value has to be given.
 * <p>
 * Positions are in millimeters in the work coordinate system, feed rates in millimeters a minute.
 *
 * @param area                  the area to measure
 * @param stepMM                the distance between the points
 * @param maxBoardZ             the highest that the surface of the board is expected to be
 * @param fixtureTopZ           the highest point of what holds the board, anywhere on the machine that the tool may pass
 * @param obstacles             what the tool must not be lowered onto, with their own heights
 * @param clearanceMM           how far above the board, the fixtures and the obstacles the tool is moved between points
 * @param probeStartClearanceMM how far above the highest expected surface the probing move starts
 * @param minProbeZ             the lowest that the tool is moved while probing. The board must be touched before this.
 * @param probeFeed             the feed rate of the probing move
 * @param approachFeed          the feed rate of the moves in Z that are not probing
 * @param touchPlateThicknessMM the thickness of what is between the tool and the board when it touches, zero if nothing
 * @param limitMarginMM         how far the tool is kept from the limits of the machine
 * @param tool                  tells the operator which tool or probe the settings are for
 */
public record ProbeSettings(ProbeArea area, double stepMM, double maxBoardZ, double fixtureTopZ, List<Obstacle> obstacles,
                            double clearanceMM, double probeStartClearanceMM, double minProbeZ, double probeFeed,
                            double approachFeed, double touchPlateThicknessMM, double limitMarginMM, String tool) {
    public static final String ERROR_STEP = "The distance between the points must be more than zero and a whole number of thousandths of a millimeter.";
    public static final String ERROR_NUMBER = "Every setting must be a number.";
    public static final String ERROR_CLEARANCE = "The clearance above the board and the fixtures must be more than zero.";
    public static final String ERROR_PROBE_START = "The height above the board where probing starts must be more than zero and not more than the clearance.";
    public static final String ERROR_MIN_PROBE_Z = "The lowest position of the probe must be below the highest expected surface of the board.";
    public static final String ERROR_FEED = "The feed rates must be more than zero.";
    public static final String ERROR_TOUCH_PLATE = "The thickness of the touch plate can not be less than zero.";
    public static final String ERROR_LIMIT_MARGIN = "The margin to the limits of the machine can not be less than zero.";
    public static final String ERROR_TOOL = "Tell which tool or probe is used.";

    public ProbeSettings {
        obstacles = List.copyOf(obstacles);
    }

    /**
     * Returns the Z that the tool is moved at between points: clear of the board, the fixtures and
     * every obstacle, whether or not the path goes over them
     */
    public double travelZ() {
        double highest = Math.max(maxBoardZ + touchPlateThicknessMM, fixtureTopZ);
        for (Obstacle obstacle : obstacles) {
            highest = Math.max(highest, obstacle.topZ());
        }
        return highest + clearanceMM;
    }

    /**
     * Returns the Z where the probing move starts
     */
    public double probeStartZ() {
        return maxBoardZ + touchPlateThicknessMM + probeStartClearanceMM;
    }

    /**
     * Tells what is wrong with the settings, empty if nothing
     */
    public List<String> findProblems() {
        List<String> result = new ArrayList<>();
        double[] numbers = {stepMM, maxBoardZ, fixtureTopZ, clearanceMM, probeStartClearanceMM, minProbeZ, probeFeed, approachFeed, touchPlateThicknessMM, limitMarginMM};
        for (double number : numbers) {
            if (!Double.isFinite(number)) {
                return List.of(ERROR_NUMBER);
            }
        }
        for (Obstacle obstacle : obstacles) {
            if (!Double.isFinite(obstacle.topZ())) {
                return List.of(ERROR_NUMBER);
            }
        }

        double steps = stepMM / GridProbePlanner.POSITION_RESOLUTION_MM;
        if (!(stepMM > 0) || Math.abs(steps - Math.round(steps)) > 1e-6) {
            result.add(ERROR_STEP);
        }
        if (!(clearanceMM > 0)) {
            result.add(ERROR_CLEARANCE);
        }
        if (!(probeStartClearanceMM > 0) || probeStartClearanceMM > clearanceMM) {
            result.add(ERROR_PROBE_START);
        }
        if (!(minProbeZ < maxBoardZ)) {
            result.add(ERROR_MIN_PROBE_Z);
        }
        if (!(probeFeed > 0) || !(approachFeed > 0)) {
            result.add(ERROR_FEED);
        }
        if (touchPlateThicknessMM < 0) {
            result.add(ERROR_TOUCH_PLATE);
        }
        if (limitMarginMM < 0) {
            result.add(ERROR_LIMIT_MARGIN);
        }
        if (tool == null || tool.isBlank()) {
            result.add(ERROR_TOOL);
        }
        return result;
    }
}
