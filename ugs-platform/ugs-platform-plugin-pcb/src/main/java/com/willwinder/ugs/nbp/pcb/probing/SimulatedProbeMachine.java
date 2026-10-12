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
import com.willwinder.universalgcodesender.gcode.util.Code;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UnitUtils.Units;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.DoubleBinaryOperator;
import java.util.function.Function;

/**
 * A machine that only exists in memory. It is what a sequence is simulated on before it is carried
 * out, and what the probing is tested with: it can be told to fail in every way that a real
 * machine can.
 * <p>
 * An operation is only done when the simulation is told to go on, as a real machine takes time,
 * and it refuses a second operation while one is not done.
 */
public class SimulatedProbeMachine implements ProbeMachine {
    private final List<ProbeOperation> executed = new ArrayList<>();
    private final DoubleBinaryOperator contactZ;
    private ProbeCapabilities capabilities = new ProbeCapabilities(true, "Simulated machine", true);
    private MachineEnvelope envelope;
    private boolean isConnected = true;
    private Condition condition = Condition.IDLE;
    private Code workCoordinateSystem = Code.G54;
    private Position workOffset = new Position(0, 0, 0, Units.MM);
    private double x;
    private double y;
    private double z;
    private Boolean probeContact = false;
    private boolean isContactStuck;
    private ProbeOperation pending;
    private Consumer<OperationResult> pendingCallback;
    private Function<ProbeOperation, OperationResult> fault = operation -> null;
    private Consumer<ProbeOperation> afterOperation = operation -> {
    };

    /**
     * @param contactZ gives the Z in the work coordinate system where the sensor gets contact at an X and Y
     * @param envelope where the tool can be in the coordinates of the machine, null if that is not known
     */
    public SimulatedProbeMachine(DoubleBinaryOperator contactZ, MachineEnvelope envelope, Position workPosition) {
        this.contactZ = contactZ;
        this.envelope = envelope;
        this.x = workPosition.getX();
        this.y = workPosition.getY();
        this.z = workPosition.getZ();
    }

    @Override
    public synchronized ProbeCapabilities getCapabilities() {
        return capabilities;
    }

    @Override
    public synchronized MachineSnapshot getSnapshot() {
        if (!isConnected) {
            return MachineSnapshot.disconnected();
        }
        return new MachineSnapshot(true, pending != null ? Condition.BUSY : condition, workCoordinateSystem, workOffset, new Position(x, y, z, Units.MM), probeContact);
    }

    @Override
    public synchronized Optional<MachineEnvelope> getEnvelope() {
        return Optional.ofNullable(envelope);
    }

    @Override
    public synchronized void execute(ProbeOperation operation, Consumer<OperationResult> whenDone) {
        if (pending != null) {
            throw new IllegalStateException("An operation was given while " + pending.describe() + " was not done");
        }
        pending = operation;
        pendingCallback = whenDone;
    }

    public synchronized boolean hasPendingOperation() {
        return pending != null;
    }

    public synchronized ProbeOperation getPendingOperation() {
        return pending;
    }

    /**
     * Does the operation that the machine was given and tells how it ended
     *
     * @return false if there was no operation to do
     */
    public boolean completeNext() {
        ProbeOperation operation;
        Consumer<OperationResult> callback;
        OperationResult result;
        synchronized (this) {
            if (pending == null) {
                return false;
            }
            operation = pending;
            callback = pendingCallback;
            pending = null;
            pendingCallback = null;
            executed.add(operation);

            result = fault.apply(operation);
            if (result == null) {
                result = move(operation);
            }
            afterOperation.accept(operation);
        }
        callback.accept(result);
        return true;
    }

    /**
     * Does operations until the machine is given no more
     *
     * @return the number of operations that were done
     */
    public int runUntilIdle() {
        int count = 0;
        while (completeNext()) {
            count++;
            if (count > 1_000_000) {
                throw new IllegalStateException("The sequence does not end");
            }
        }
        return count;
    }

    private OperationResult move(ProbeOperation operation) {
        if (!isConnected) {
            return new OperationResult(Outcome.DISCONNECTED, null, "Not connected");
        }
        if (condition == Condition.ALARM) {
            return new OperationResult(Outcome.ERROR, position(), "The controller is locked by an alarm");
        }

        double targetX = operation.kind() == ProbeOperation.Kind.MOVE_XY ? operation.x() : x;
        double targetY = operation.kind() == ProbeOperation.Kind.MOVE_XY ? operation.y() : y;
        double targetZ = operation.kind() == ProbeOperation.Kind.RETRACT ? Math.max(z, operation.z()) : operation.z();
        if (envelope != null && !envelope.contains(targetX + workOffset.getX(), targetY + workOffset.getY(), targetZ + workOffset.getZ(), 0)) {
            condition = Condition.ALARM;
            return new OperationResult(Outcome.ALARM, position(), "Soft limit");
        }

        double contact = contactZ.applyAsDouble(targetX, targetY);
        switch (operation.kind()) {
            case RETRACT -> {
                z = targetZ;
                probeContact = isContactStuck;
            }
            case MOVE_XY -> {
                x = targetX;
                y = targetY;
            }
            case APPROACH -> {
                // A tool that is lowered onto something without probing is stopped by nothing
                z = targetZ;
                probeContact = isContactStuck || contact >= targetZ;
            }
            case PROBE -> {
                if (probeContact) {
                    condition = Condition.ALARM;
                    return new OperationResult(Outcome.ALARM, position(), "The sensor had contact when the probing started");
                }
                if (contact >= targetZ && contact <= z) {
                    z = contact;
                    probeContact = true;
                    return new OperationResult(Outcome.CONTACT, position(), null);
                }
                z = targetZ;
                return new OperationResult(Outcome.NO_CONTACT, position(), null);
            }
        }
        return new OperationResult(Outcome.COMPLETED, position(), null);
    }

    private Position position() {
        return new Position(x, y, z, Units.MM);
    }

    public synchronized List<ProbeOperation> getExecuted() {
        return List.copyOf(executed);
    }

    public synchronized void setCapabilities(ProbeCapabilities capabilities) {
        this.capabilities = capabilities;
    }

    public synchronized void setEnvelope(MachineEnvelope envelope) {
        this.envelope = envelope;
    }

    public synchronized void setConnected(boolean isConnected) {
        this.isConnected = isConnected;
    }

    public synchronized void setCondition(Condition condition) {
        this.condition = condition;
    }

    public synchronized void setWorkCoordinateSystem(Code workCoordinateSystem) {
        this.workCoordinateSystem = workCoordinateSystem;
    }

    public synchronized void setWorkOffset(Position workOffset) {
        this.workOffset = workOffset;
    }

    public synchronized void setWorkPosition(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /**
     * @param probeContact if the sensor has contact, null if the controller does not tell
     */
    public synchronized void setProbeContact(Boolean probeContact) {
        this.probeContact = probeContact;
    }

    /**
     * @param isContactStuck true if the sensor keeps its contact when the tool is raised
     */
    public synchronized void setContactStuck(boolean isContactStuck) {
        this.isContactStuck = isContactStuck;
    }

    /**
     * @param fault gives the result of an operation in place of doing it, or null to do it
     */
    public synchronized void setFault(Function<ProbeOperation, OperationResult> fault) {
        this.fault = fault;
    }

    /**
     * @param afterOperation is called when an operation was done, before its result is told
     */
    public synchronized void setAfterOperation(Consumer<ProbeOperation> afterOperation) {
        this.afterOperation = afterOperation;
    }
}
