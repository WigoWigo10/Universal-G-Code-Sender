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
import com.willwinder.ugs.nbp.pcb.probing.OperationResult.Outcome;
import com.willwinder.universalgcodesender.IController;
import com.willwinder.universalgcodesender.firmware.FirmwareSettingsException;
import com.willwinder.universalgcodesender.firmware.IFirmwareSettings;
import com.willwinder.universalgcodesender.gcode.GcodeState;
import com.willwinder.universalgcodesender.listeners.ControllerStatus;
import com.willwinder.universalgcodesender.model.Axis;
import com.willwinder.universalgcodesender.model.BackendAPI;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UnitUtils.Units;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Reads what the application knows of the connected machine. It only reads: it never sends
 * anything to the controller, and it refuses every operation.
 * <p>
 * Probing on a real machine is not enabled. What is missing is decided in the second safety
 * review: the commands and answers of each supported firmware, and how it is known for certain
 * that the machine was homed since it was switched on, without which its limits are not known.
 */
public class BackendMachineInspector implements ProbeMachine {
    public static final String REASON_NOT_CONNECTED = "no machine is connected";
    public static final String REASON_NOT_ENABLED = "%s was recognized, but probing on a real machine is not enabled in this version. It awaits the second safety review.";
    public static final String REASON_UNSUPPORTED = "there is no probing support for the firmware %s";
    public static final String ERROR_READ_ONLY = "Probing on a real machine is not enabled, nothing was sent to the controller.";

    private final BackendAPI backend;

    public BackendMachineInspector(BackendAPI backend) {
        this.backend = backend;
    }

    @Override
    public ProbeCapabilities getCapabilities() {
        if (!backend.isConnected()) {
            return new ProbeCapabilities(false, REASON_NOT_CONNECTED, false);
        }
        String firmware = String.valueOf(backend.getSettings().getFirmwareVersion());
        boolean isRecognized = firmware.trim().equalsIgnoreCase("GRBL");
        return new ProbeCapabilities(false, String.format(isRecognized ? REASON_NOT_ENABLED : REASON_UNSUPPORTED, firmware), false);
    }

    @Override
    public MachineSnapshot getSnapshot() {
        if (!backend.isConnected() || backend.getController() == null) {
            return MachineSnapshot.disconnected();
        }

        ControllerStatus status = backend.getController().getControllerStatus();
        GcodeState gcodeState = backend.getGcodeState();
        if (status == null) {
            return new MachineSnapshot(true, Condition.UNKNOWN, gcodeState == null ? null : gcodeState.offset, null, null, null);
        }
        Condition condition = switch (status.getState()) {
            case IDLE -> Condition.IDLE;
            case ALARM -> Condition.ALARM;
            case HOLD, DOOR -> Condition.HOLD;
            case RUN, JOG, HOME, CHECK -> Condition.BUSY;
            default -> Condition.UNKNOWN;
        };
        return new MachineSnapshot(true, condition,
                gcodeState == null ? null : gcodeState.offset,
                millimeters(status.getWorkCoordinateOffset()),
                millimeters(status.getWorkCoord()),
                status.getEnabledPins() == null ? null : status.getEnabledPins().probe());
    }

    private static Position millimeters(Position position) {
        if (position == null || position.getUnits() == Units.UNKNOWN
                || !Double.isFinite(position.getX()) || !Double.isFinite(position.getY()) || !Double.isFinite(position.getZ())) {
            return null;
        }
        return position.getPositionIn(Units.MM);
    }

    /**
     * The limits are not known for certain: the controller tells its travel, but the application
     * can not tell yet whether the machine was homed since it was switched on.
     */
    @Override
    public Optional<MachineEnvelope> getEnvelope() {
        return Optional.empty();
    }

    @Override
    public void execute(ProbeOperation operation, Consumer<OperationResult> whenDone) {
        whenDone.accept(new OperationResult(Outcome.ERROR, null, ERROR_READ_ONLY));
    }

    /**
     * Tells the operator what the controller tells of its limits and its sensor
     */
    public List<String> describe() {
        List<String> result = new ArrayList<>();
        MachineSnapshot snapshot = getSnapshot();
        if (!snapshot.isConnected()) {
            result.add("Machine: not connected");
            return result;
        }

        result.add("Firmware: " + backend.getSettings().getFirmwareVersion() + ", state " + snapshot.condition());
        ReferenceFrame reference = snapshot.getReferenceFrame();
        result.add("Reference: " + (reference == null ? "unknown" : reference.describe()));
        result.add("Sensor: " + (snapshot.probeContact() == null ? "state unknown" : snapshot.probeContact() ? "has contact" : "no contact"));

        IController controller = backend.getController();
        IFirmwareSettings settings = controller == null ? null : controller.getFirmwareSettings();
        if (settings == null) {
            result.add("Limits: the controller did not tell its settings");
            return result;
        }
        try {
            result.add(String.format(Locale.ROOT, "Limits: homing %s, soft limits %s, travel X%.1f Y%.1f Z%.1f mm (not verified: it is not known if the machine was homed)",
                    settings.isHomingEnabled() ? "enabled" : "disabled",
                    settings.isSoftLimitsEnabled() ? "enabled" : "disabled",
                    settings.getSoftLimit(Axis.X), settings.getSoftLimit(Axis.Y), settings.getSoftLimit(Axis.Z)));
        } catch (FirmwareSettingsException e) {
            result.add("Limits: the controller did not tell them");
        }
        return result;
    }
}
