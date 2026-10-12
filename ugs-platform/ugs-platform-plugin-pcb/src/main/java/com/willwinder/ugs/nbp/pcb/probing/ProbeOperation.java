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

import java.util.Locale;

/**
 * One thing that the machine does while probing. Only one is ever given to the machine at a time,
 * and the next one is decided when it is known how this one ended.
 *
 * @param kind       what is done
 * @param pointIndex the number of the point it belongs to, starting at zero
 * @param x          the X that the tool is at when it is done
 * @param y          the Y that the tool is at when it is done
 * @param z          the Z that the tool is at when it is done, or for probing the Z it does not go below
 * @param feed       the feed rate, zero for a rapid move
 */
public record ProbeOperation(Kind kind, int pointIndex, double x, double y, double z, double feed) {
    public enum Kind {
        /** Raises the tool to the height it is moved at, without moving in X or Y */
        RETRACT("Retract"),
        /** Moves to above a point, without moving in Z */
        MOVE_XY("Move"),
        /** Lowers the tool to where probing starts, without moving in X or Y */
        APPROACH("Approach"),
        /** Lowers the tool until the sensor has contact */
        PROBE("Probe");

        private final String text;

        Kind(String text) {
            this.text = text;
        }

        public String getText() {
            return text;
        }
    }

    public String describe() {
        return switch (kind) {
            case RETRACT -> String.format(Locale.ROOT, "Retract to Z%.3f", z);
            case MOVE_XY -> String.format(Locale.ROOT, "Move to X%.3f Y%.3f at Z%.3f", x, y, z);
            case APPROACH -> String.format(Locale.ROOT, "Approach to Z%.3f at F%.0f", z, feed);
            case PROBE -> String.format(Locale.ROOT, "Probe down to at most Z%.3f at F%.0f", z, feed);
        };
    }
}
