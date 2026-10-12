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
 * What a controller can do for probing
 *
 * @param isSupported          if the controller can be used for probing by this application
 * @param reason               why not, or what it was recognized as
 * @param reportsProbeContact  if the controller tells whether the sensor has contact while nothing is being probed
 */
public record ProbeCapabilities(boolean isSupported, String reason, boolean reportsProbeContact) {
}
