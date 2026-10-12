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

import java.util.Optional;
import java.util.function.Consumer;

/**
 * All that a probing needs from a machine. Everything that depends on the firmware of a controller
 * is behind this: how a move and a probing are commanded, how it is known that they are done, and
 * how the state of the controller and of the sensor is read. A controller that there is no
 * implementation for is not used for probing.
 */
public interface ProbeMachine {
    ProbeCapabilities getCapabilities();

    /**
     * Returns what is known of the machine now
     */
    MachineSnapshot getSnapshot();

    /**
     * Returns where the tool can be, empty if that is not known for certain
     */
    Optional<MachineEnvelope> getEnvelope();

    /**
     * Starts one operation. It must not be called again before the result of this one was given.
     *
     * @param whenDone is given the result exactly once, also if the operation could not be started
     */
    void execute(ProbeOperation operation, Consumer<OperationResult> whenDone);
}
