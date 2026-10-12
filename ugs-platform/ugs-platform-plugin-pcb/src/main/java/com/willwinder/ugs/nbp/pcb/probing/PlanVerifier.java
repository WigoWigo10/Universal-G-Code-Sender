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

import com.willwinder.ugs.nbp.pcb.probing.MachineSnapshot.Condition;
import com.willwinder.ugs.nbp.pcb.probing.ProbeOperation.Kind;
import com.willwinder.universalgcodesender.model.Position;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Verifies a plan against a machine before anything moves: that the controller is one that can be
 * used, that enough is known of the machine, and that every position of every move is where the
 * tool can be. If any of this can not be told, that is a reason not to probe.
 */
public final class PlanVerifier {
    public static final String ERROR_UNSUPPORTED = "This controller can not be used for probing: ";
    public static final String ERROR_NOT_CONNECTED = "The machine is not connected.";
    public static final String ERROR_NOT_IDLE = "The machine is not idle, it is: ";
    public static final String ERROR_REFERENCE_UNKNOWN = "The work coordinate system or its offsets are not known.";
    public static final String ERROR_POSITION_UNKNOWN = "The position of the tool is not known.";
    public static final String ERROR_ENVELOPE_UNKNOWN = "The limits of the machine are not known for certain: it has to be homed and its controller has to tell its travel. The path can not be verified without them.";
    public static final String ERROR_SENSOR_UNKNOWN = "The controller does not tell whether the sensor has contact, so the sensor can not be tested.";
    public static final String ERROR_SENSOR_CONTACT = "The sensor has contact before probing was started.";
    public static final String ERROR_OUTSIDE_LIMITS = "The machine can not reach, with the margin to its limits: ";
    public static final String ERROR_TOOL_OUTSIDE_LIMITS = "The tool is outside of the limits that the controller tells, so the position of the machine can not be trusted.";

    private PlanVerifier() {
    }

    /**
     * Tells why the plan must not be carried out on the machine, empty if it may
     */
    public static List<String> findBlocks(ProbePlan plan, ProbeMachine machine) {
        List<String> result = new ArrayList<>(plan.problems());

        ProbeCapabilities capabilities = machine.getCapabilities();
        if (!capabilities.isSupported()) {
            result.add(ERROR_UNSUPPORTED + capabilities.reason());
            return result;
        }

        MachineSnapshot snapshot = machine.getSnapshot();
        if (!snapshot.isConnected()) {
            result.add(ERROR_NOT_CONNECTED);
            return result;
        }
        if (snapshot.condition() != Condition.IDLE) {
            result.add(ERROR_NOT_IDLE + snapshot.condition());
        }
        if (!capabilities.reportsProbeContact() || snapshot.probeContact() == null) {
            result.add(ERROR_SENSOR_UNKNOWN);
        } else if (snapshot.probeContact()) {
            result.add(ERROR_SENSOR_CONTACT);
        }

        ReferenceFrame reference = snapshot.getReferenceFrame();
        if (reference == null) {
            result.add(ERROR_REFERENCE_UNKNOWN);
        }
        if (snapshot.workPosition() == null) {
            result.add(ERROR_POSITION_UNKNOWN);
        }
        Optional<MachineEnvelope> envelope = machine.getEnvelope();
        if (envelope.isEmpty()) {
            result.add(ERROR_ENVELOPE_UNKNOWN);
        }
        if (reference == null || snapshot.workPosition() == null || envelope.isEmpty() || !plan.isValid()) {
            return result;
        }

        result.addAll(findPositionsOutsideLimits(plan, snapshot.workPosition(), reference, envelope.get()));
        return result;
    }

    /**
     * Follows the tool through every operation of the plan, in the coordinates of the machine. All
     * moves are along one axis or at the travel height, so a move is inside of the limits if both
     * its ends are.
     */
    private static List<String> findPositionsOutsideLimits(ProbePlan plan, Position start, ReferenceFrame reference, MachineEnvelope envelope) {
        List<String> result = new ArrayList<>();
        double margin = plan.settings().limitMarginMM();
        double x = start.getX();
        double y = start.getY();
        double z = start.getZ();
        if (!envelope.contains(x + reference.offsetX(), y + reference.offsetY(), z + reference.offsetZ(), 0)) {
            result.add(ERROR_TOOL_OUTSIDE_LIMITS);
            return result;
        }

        for (ProbeOperation operation : plan.operations()) {
            if (operation.kind() == Kind.MOVE_XY) {
                x = operation.x();
                y = operation.y();
            }
            // The tool is never lowered by the first retraction, it may be above the travel height already
            z = operation.kind() == Kind.RETRACT ? Math.max(z, operation.z()) : operation.z();

            if (!envelope.contains(x + reference.offsetX(), y + reference.offsetY(), z + reference.offsetZ(), margin)) {
                result.add(ERROR_OUTSIDE_LIMITS + String.format(Locale.ROOT, "X%.3f Y%.3f Z%.3f (machine X%.3f Y%.3f Z%.3f), to %s point %d",
                        x, y, z, x + reference.offsetX(), y + reference.offsetY(), z + reference.offsetZ(),
                        operation.kind().getText().toLowerCase(Locale.ROOT), operation.pointIndex() + 1));
                // One position tells the operator what is wrong, the others would only repeat it
                return result;
            }
        }
        return result;
    }
}
