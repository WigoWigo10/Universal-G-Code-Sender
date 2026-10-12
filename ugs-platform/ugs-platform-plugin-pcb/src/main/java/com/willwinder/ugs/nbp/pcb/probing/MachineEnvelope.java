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

/**
 * Where the tool can be, in millimeters in the coordinates of the machine. It is only known when
 * the machine has been homed and its controller tells its travel.
 */
public record MachineEnvelope(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {

    public boolean contains(double x, double y, double z, double marginMM) {
        return containsXY(x, y, marginMM) && z >= minZ + marginMM && z <= maxZ - marginMM;
    }

    public boolean containsXY(double x, double y, double marginMM) {
        return x >= minX + marginMM && x <= maxX - marginMM && y >= minY + marginMM && y <= maxY - marginMM;
    }
}
