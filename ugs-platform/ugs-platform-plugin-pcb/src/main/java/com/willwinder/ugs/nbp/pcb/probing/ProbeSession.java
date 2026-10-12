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

import com.willwinder.ugs.nbp.pcb.heightmap.GridHeightMap;
import com.willwinder.ugs.nbp.pcb.heightmap.GridProbePlanner;
import com.willwinder.ugs.nbp.pcb.heightmap.HeightMapException;
import com.willwinder.ugs.nbp.pcb.heightmap.ProbeArea;
import com.willwinder.ugs.nbp.pcb.probing.MachineSnapshot.Condition;
import com.willwinder.ugs.nbp.pcb.probing.OperationResult.Outcome;
import com.willwinder.ugs.nbp.pcb.probing.ProbeOperation.Kind;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UnitUtils.Units;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Measures the points of a plan, one operation at a time. The next operation is only given to the
 * machine when the one before it has ended, its result is what was expected, and the machine is
 * still as it was when the session was prepared.
 * <p>
 * Anything else ends the sequence with a failure, and nothing more is commanded: not a retraction
 * either, as that would move a machine whose position, communication or sensor is not to be
 * trusted at that moment. The session only goes on after the operator has found the cause,
 * restored the reference and authorized it, and then everything is verified again from the start.
 * <p>
 * The measurements only become a height map when all points were measured.
 */
public class ProbeSession {
    public enum State {
        /** Nothing is being done and nothing was verified */
        IDLE,
        /** The operator is to make the sensor have contact, to show that it works */
        SENSOR_TEST_AWAIT_CONTACT,
        /** The operator is to end the contact of the sensor */
        SENSOR_TEST_AWAIT_RELEASE,
        /** Everything was verified, the measuring can be started */
        READY,
        /** The tool is being raised to the height it is moved at */
        RETRACTING,
        /** The tool is being moved to above a point */
        MOVING_XY,
        /** The tool is being lowered to where probing starts */
        APPROACHING,
        /** The tool is being lowered until the sensor has contact */
        PROBING,
        /** The sequence was stopped by the operator with the tool at the height it is moved at */
        PAUSED,
        /** The sequence was ended by something unexpected */
        FAILED,
        /** All points were measured */
        COMPLETED
    }

    public enum FailureKind {
        DISCONNECTED,
        ALARM,
        CONTROLLER_ERROR,
        NOT_IDLE,
        REFERENCE_CHANGED,
        SENSOR_UNKNOWN,
        UNEXPECTED_CONTACT,
        PREMATURE_CONTACT,
        NO_CONTACT,
        CONTACT_NOT_RELEASED,
        INCONSISTENT_POSITION
    }

    /**
     * @param kind      what went wrong
     * @param message   tells the operator what went wrong
     * @param phase     what was being done
     * @param operation the operation that was being done or was about to be done, null if none
     */
    public record Failure(FailureKind kind, String message, State phase, ProbeOperation operation) {
    }

    /**
     * The operator allows the machine to be moved and is watching it
     */
    public record OperatorAuthorization(boolean isSupervised) {
    }

    /**
     * What the operator confirms before a failed session may be prepared again
     */
    public record RecoveryConfirmation(boolean isCauseFound, boolean isReferenceRestored, boolean isAuthorized) {
    }

    public interface Listener {
        void onSessionChanged(ProbeSession session);
    }

    public static final String ERROR_STATE = "This can not be done while the session is ";
    public static final String ERROR_NOT_AUTHORIZED = "The operator has to authorize the movement of the machine and watch it.";
    public static final String ERROR_RECOVERY = "The session can only go on when the cause of the failure was found, the reference was restored and the operator authorizes it.";
    public static final String ERROR_INCOMPLETE = "Not all points were measured, so there is no height map: ";
    public static final String ERROR_REFERENCE_CHANGED = "The work coordinate system or its origin is not what the earlier points of this session were measured from, so they can not be used together. It was %s and is %s.";
    public static final String ERROR_CHECKPOINT = "The checkpoint is of another area or another distance between the points than the settings.";

    /**
     * The largest accepted difference between where the tool was sent and where the controller tells it is
     */
    public static final double POSITION_TOLERANCE_MM = 0.02;

    private final ProbeMachine machine;
    private final ProbeSettings settings;
    private final Consumer<ProbeCheckpoint> checkpointSink;
    private final Set<Listener> listeners = ConcurrentHashMap.newKeySet();
    private final List<Position> measured = new ArrayList<>();
    private final GridProbePlanner planner;
    private final ProbeArea gridArea;
    private final int plannedPoints;
    private final String id;

    private State state = State.IDLE;
    private ReferenceFrame reference;
    private ProbePlan plan;
    private int operationIndex;
    private long token;
    private boolean isPauseRequested;
    private Failure failure;
    private double expectedX;
    private double expectedY;

    /**
     * @param checkpointSink is given the measurements whenever a point was measured
     */
    public ProbeSession(ProbeMachine machine, ProbeSettings settings, Consumer<ProbeCheckpoint> checkpointSink) {
        this.machine = machine;
        this.settings = settings;
        this.checkpointSink = checkpointSink;
        this.id = UUID.randomUUID().toString();
        if (settings.findProblems().isEmpty()) {
            planner = new GridProbePlanner(settings.stepMM());
            gridArea = planner.getGridArea(settings.area());
            plannedPoints = planner.getPointCount(settings.area().width()) * planner.getPointCount(settings.area().height());
        } else {
            planner = null;
            gridArea = settings.area();
            plannedPoints = 0;
        }
    }

    /**
     * Goes on with the measurements of an earlier session
     *
     * @throws ProbeSessionException if the checkpoint is not of the same points
     */
    public ProbeSession(ProbeMachine machine, ProbeSettings settings, Consumer<ProbeCheckpoint> checkpointSink, ProbeCheckpoint checkpoint) throws ProbeSessionException {
        this(machine, settings, checkpointSink);
        boolean isSamePoints = planner != null
                && Math.abs(checkpoint.stepMM() - settings.stepMM()) < 1e-9
                && checkpoint.plannedPoints() == plannedPoints
                && Math.abs(checkpoint.gridArea().minX() - gridArea.minX()) < 1e-6
                && Math.abs(checkpoint.gridArea().minY() - gridArea.minY()) < 1e-6
                && planner.nextPoints(settings.area(), checkpoint.measured()).size() == plannedPoints - checkpoint.measured().size();
        if (!isSamePoints) {
            throw new ProbeSessionException(ERROR_CHECKPOINT);
        }
        measured.addAll(checkpoint.measured());
        reference = checkpoint.reference();
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    private void notifyListeners() {
        listeners.forEach(listener -> listener.onSessionChanged(this));
    }

    public synchronized State getState() {
        return state;
    }

    public synchronized Failure getFailure() {
        return failure;
    }

    public synchronized List<Position> getMeasured() {
        return List.copyOf(measured);
    }

    public int getPlannedPoints() {
        return plannedPoints;
    }

    public ProbeArea getGridArea() {
        return gridArea;
    }

    public synchronized ProbePlan getPlan() {
        return plan;
    }

    public synchronized ReferenceFrame getReference() {
        return reference;
    }

    /**
     * Returns the operation that the machine is doing, null if none
     */
    public synchronized ProbeOperation getCurrentOperation() {
        boolean isMoving = state == State.RETRACTING || state == State.MOVING_XY || state == State.APPROACHING || state == State.PROBING;
        return isMoving ? plan.operations().get(operationIndex) : null;
    }

    public synchronized ProbeCheckpoint getCheckpoint() {
        return new ProbeCheckpoint(id, Instant.now(), reference, gridArea, settings.stepMM(), plannedPoints, measured);
    }

    private void require(State... allowed) throws ProbeSessionException {
        for (State candidate : allowed) {
            if (state == candidate) {
                return;
            }
        }
        throw new ProbeSessionException(ERROR_STATE + state);
    }

    /**
     * Plans what is left to measure and verifies it against the machine. Nothing moves.
     *
     * @return why the session can not be started, empty if the sensor test is next
     */
    public List<String> prepare() throws ProbeSessionException {
        List<String> blocks;
        synchronized (this) {
            require(State.IDLE);
            blocks = verify();
            if (blocks.isEmpty()) {
                reference = machine.getSnapshot().getReferenceFrame();
                state = State.SENSOR_TEST_AWAIT_CONTACT;
            }
        }
        notifyListeners();
        return blocks;
    }

    /**
     * Plans the points that are not measured yet, from where the tool is now, and tells why that
     * plan must not be carried out
     */
    private List<String> verify() {
        plan = ProbePlan.create(settings, measured);
        List<String> blocks = new ArrayList<>(PlanVerifier.findBlocks(plan, machine));
        ReferenceFrame current = machine.getSnapshot().getReferenceFrame();
        if (reference != null && !measured.isEmpty() && current != null && !reference.isSame(current)) {
            blocks.add(String.format(Locale.ROOT, ERROR_REFERENCE_CHANGED, reference.describe(), current.describe()));
        }
        return blocks;
    }

    /**
     * Looks at the sensor during its test. The operator makes the sensor have contact and ends it
     * again, which shows that the controller sees both, before the machine is trusted to stop on it.
     */
    public void pollSensor() {
        synchronized (this) {
            if (state != State.SENSOR_TEST_AWAIT_CONTACT && state != State.SENSOR_TEST_AWAIT_RELEASE) {
                return;
            }
            MachineSnapshot snapshot = machine.getSnapshot();
            if (!snapshot.isConnected() || snapshot.probeContact() == null) {
                state = State.IDLE;
            } else if (state == State.SENSOR_TEST_AWAIT_CONTACT && snapshot.probeContact()) {
                state = State.SENSOR_TEST_AWAIT_RELEASE;
            } else if (state == State.SENSOR_TEST_AWAIT_RELEASE && !snapshot.probeContact()) {
                state = State.READY;
            }
        }
        notifyListeners();
    }

    /**
     * Starts measuring, after everything has been verified once more
     *
     * @throws ProbeSessionException if it was not started. The session then has to be prepared again.
     */
    public void start(OperatorAuthorization authorization) throws ProbeSessionException {
        begin(authorization, State.READY);
    }

    /**
     * Goes on after a pause. The tool may have been moved meanwhile, so what is left is planned and
     * verified again from where it is now.
     *
     * @throws ProbeSessionException if it did not go on. The session stays paused, unless the
     *                               reference was changed, which ends it with a failure.
     */
    public void resume(OperatorAuthorization authorization) throws ProbeSessionException {
        begin(authorization, State.PAUSED);
    }

    private void begin(OperatorAuthorization authorization, State from) throws ProbeSessionException {
        try {
            synchronized (this) {
                require(from);
                if (authorization == null || !authorization.isSupervised()) {
                    throw new ProbeSessionException(ERROR_NOT_AUTHORIZED);
                }

                ReferenceFrame current = machine.getSnapshot().getReferenceFrame();
                if (current != null && !reference.isSame(current)) {
                    fail(FailureKind.REFERENCE_CHANGED, String.format(Locale.ROOT, ERROR_REFERENCE_CHANGED, reference.describe(), current.describe()), null);
                    throw new ProbeSessionException(failure.message());
                }
                List<String> blocks = verify();
                if (!blocks.isEmpty()) {
                    if (from == State.READY) {
                        state = State.IDLE;
                    }
                    throw new ProbeSessionException(blocks);
                }

                isPauseRequested = false;
                operationIndex = 0;
                Position position = machine.getSnapshot().workPosition();
                expectedX = position.getX();
                expectedY = position.getY();
                issueNext();
            }
        } finally {
            notifyListeners();
        }
    }

    /**
     * Stops the sequence when the tool is next at the height it is moved at. What the machine is
     * doing is finished first: a tool is never left on the board by a pause.
     */
    public void pause() throws ProbeSessionException {
        synchronized (this) {
            require(State.RETRACTING, State.MOVING_XY, State.APPROACHING, State.PROBING);
            isPauseRequested = true;
        }
        notifyListeners();
    }

    /**
     * Lets a failed session be prepared again. Nothing is verified or moved by this: the session
     * goes back to where it has to be prepared and its sensor tested, as when it was new.
     */
    public void recover(RecoveryConfirmation confirmation) throws ProbeSessionException {
        synchronized (this) {
            require(State.FAILED);
            if (confirmation == null || !confirmation.isCauseFound() || !confirmation.isReferenceRestored() || !confirmation.isAuthorized()) {
                throw new ProbeSessionException(ERROR_RECOVERY);
            }
            state = State.IDLE;
        }
        notifyListeners();
    }

    /**
     * Forgets the measurements of the session, which is needed before it can be prepared with
     * another reference than they were made from
     */
    public void discardMeasurements() throws ProbeSessionException {
        synchronized (this) {
            require(State.IDLE);
            measured.clear();
            reference = null;
        }
        notifyListeners();
    }

    /**
     * Returns the measured surface, which there only is when all points were measured
     */
    public synchronized GridHeightMap getHeightMap() throws ProbeSessionException {
        if (state != State.COMPLETED || measured.size() != plannedPoints) {
            throw new ProbeSessionException(ERROR_INCOMPLETE + measured.size() + " of " + plannedPoints);
        }
        try {
            return GridHeightMap.of(measured);
        } catch (HeightMapException e) {
            throw new ProbeSessionException(e.getMessage());
        }
    }

    private static State stateOf(Kind kind) {
        return switch (kind) {
            case RETRACT -> State.RETRACTING;
            case MOVE_XY -> State.MOVING_XY;
            case APPROACH -> State.APPROACHING;
            case PROBE -> State.PROBING;
        };
    }

    private void fail(FailureKind kind, String message, ProbeOperation operation) {
        failure = new Failure(kind, message, state, operation);
        state = State.FAILED;
        token++;
    }

    /**
     * Tells what is wrong with the machine, if anything, before an operation is given to it and
     * after one has ended
     *
     * @return true if nothing is wrong
     */
    private boolean verifyMachine(MachineSnapshot snapshot, ProbeOperation operation) {
        if (!snapshot.isConnected()) {
            fail(FailureKind.DISCONNECTED, "The connection to the machine was lost.", operation);
        } else if (snapshot.condition() == Condition.ALARM) {
            fail(FailureKind.ALARM, "The controller is in an alarm.", operation);
        } else if (snapshot.condition() != Condition.IDLE) {
            fail(FailureKind.NOT_IDLE, "The machine is not idle between two operations, it is: " + snapshot.condition(), operation);
        } else if (snapshot.getReferenceFrame() == null || !reference.isSame(snapshot.getReferenceFrame())) {
            String current = snapshot.getReferenceFrame() == null ? "unknown" : snapshot.getReferenceFrame().describe();
            fail(FailureKind.REFERENCE_CHANGED, String.format(Locale.ROOT, ERROR_REFERENCE_CHANGED, reference.describe(), current), operation);
        } else if (snapshot.probeContact() == null) {
            fail(FailureKind.SENSOR_UNKNOWN, "The controller no longer tells whether the sensor has contact.", operation);
        }
        return state != State.FAILED;
    }

    private void issueNext() {
        // The tool is at the travel height when the next operation moves in X and Y or when all is done
        boolean isAtTravelHeight = operationIndex >= plan.operations().size() || plan.operations().get(operationIndex).kind() == Kind.MOVE_XY;
        if (operationIndex >= plan.operations().size()) {
            state = State.COMPLETED;
            return;
        }
        if (isPauseRequested && isAtTravelHeight && operationIndex > 0) {
            state = State.PAUSED;
            return;
        }

        ProbeOperation operation = plan.operations().get(operationIndex);
        MachineSnapshot snapshot = machine.getSnapshot();
        if (!verifyMachine(snapshot, operation)) {
            return;
        }
        // The sensor only has contact when the tool is on the board after a probing, and is then raised from it
        boolean isRaisedFromBoard = operation.kind() == Kind.RETRACT && operationIndex > 0;
        if (snapshot.probeContact() && !isRaisedFromBoard) {
            fail(FailureKind.UNEXPECTED_CONTACT, "The sensor has contact while nothing is being probed.", operation);
            return;
        }

        state = stateOf(operation.kind());
        long current = ++token;
        machine.execute(operation, result -> onResult(current, operation, result));
    }

    private void onResult(long resultToken, ProbeOperation operation, OperationResult result) {
        synchronized (this) {
            // The result of an operation that the session has given up on
            if (resultToken != token || state != stateOf(operation.kind())) {
                return;
            }
            handle(operation, result);
        }
        notifyListeners();
    }

    private void handle(ProbeOperation operation, OperationResult result) {
        switch (result.outcome()) {
            case DISCONNECTED -> fail(FailureKind.DISCONNECTED, "The connection to the machine was lost. " + text(result), operation);
            case ALARM -> fail(FailureKind.ALARM, "The controller went into an alarm. " + text(result), operation);
            case ERROR -> fail(FailureKind.CONTROLLER_ERROR, "The controller did not do what was asked. " + text(result), operation);
            default -> {
                // Decided below
            }
        }
        if (state == State.FAILED) {
            return;
        }

        boolean isProbing = operation.kind() == Kind.PROBE;
        if (!isProbing && result.outcome() != Outcome.COMPLETED) {
            fail(FailureKind.UNEXPECTED_CONTACT, "The controller told of a probing while the tool was only being moved.", operation);
            return;
        }
        if (isProbing && result.outcome() == Outcome.NO_CONTACT) {
            fail(FailureKind.NO_CONTACT, String.format(Locale.ROOT, "The sensor had no contact down to Z%.3f. The tool was left where it stopped.", operation.z()), operation);
            return;
        }
        if (isProbing && result.outcome() != Outcome.CONTACT) {
            fail(FailureKind.CONTROLLER_ERROR, "The controller did not tell how the probing ended.", operation);
            return;
        }

        MachineSnapshot snapshot = machine.getSnapshot();
        if (!verifyMachine(snapshot, operation)) {
            return;
        }

        Position position = result.workPosition();
        if (position == null || !isWhereExpected(operation, position)) {
            fail(FailureKind.INCONSISTENT_POSITION, "The controller tells that the tool is at " + describe(position) + ", which is not where it was sent: " + operation.describe() + ".", operation);
            return;
        }

        if (operation.kind() == Kind.MOVE_XY) {
            expectedX = operation.x();
            expectedY = operation.y();
        }
        if (!isProbing && snapshot.probeContact()) {
            boolean isAfterProbing = operation.kind() == Kind.RETRACT && operationIndex > 0;
            fail(isAfterProbing ? FailureKind.CONTACT_NOT_RELEASED : FailureKind.UNEXPECTED_CONTACT,
                    isAfterProbing ? "The sensor still has contact after the tool was raised from the board." : "The sensor has contact while nothing is being probed.", operation);
            return;
        }
        if (isProbing && !record(operation, position)) {
            return;
        }

        operationIndex++;
        issueNext();
    }

    private boolean isWhereExpected(ProbeOperation operation, Position position) {
        boolean isXYExpected = Math.abs(position.getX() - expectedXAfter(operation)) <= POSITION_TOLERANCE_MM
                && Math.abs(position.getY() - expectedYAfter(operation)) <= POSITION_TOLERANCE_MM;
        return switch (operation.kind()) {
            // The first retraction leaves a tool that is higher where it is
            case RETRACT -> isXYExpected && position.getZ() >= operation.z() - POSITION_TOLERANCE_MM
                    && (operationIndex == 0 || position.getZ() <= operation.z() + POSITION_TOLERANCE_MM);
            // A tool that was above the travel height when the session was started is moved at its own height
            case MOVE_XY -> isXYExpected && position.getZ() >= operation.z() - POSITION_TOLERANCE_MM;
            case APPROACH -> isXYExpected && Math.abs(position.getZ() - operation.z()) <= POSITION_TOLERANCE_MM;
            case PROBE -> isXYExpected && position.getZ() >= operation.z() - POSITION_TOLERANCE_MM
                    && position.getZ() <= settings.probeStartZ() + POSITION_TOLERANCE_MM;
        };
    }

    private double expectedXAfter(ProbeOperation operation) {
        return operation.kind() == Kind.MOVE_XY ? operation.x() : expectedX;
    }

    private double expectedYAfter(ProbeOperation operation) {
        return operation.kind() == Kind.MOVE_XY ? operation.y() : expectedY;
    }

    /**
     * Keeps a measurement, unless the contact was where the board can not be
     */
    private boolean record(ProbeOperation operation, Position contact) {
        double highestContact = settings.maxBoardZ() + settings.touchPlateThicknessMM();
        if (contact.getZ() > highestContact + POSITION_TOLERANCE_MM) {
            fail(FailureKind.PREMATURE_CONTACT, String.format(Locale.ROOT,
                    "The sensor had contact at Z%.3f, above the highest that the board is expected to be (Z%.3f). The tool was left where it stopped.",
                    contact.getZ(), highestContact), operation);
            return false;
        }

        // The point is kept at the position that was planned, which the controller has confirmed within its tolerance
        measured.add(new Position(operation.x(), operation.y(), contact.getZ() - settings.touchPlateThicknessMM(), Units.MM));
        if (checkpointSink != null) {
            checkpointSink.accept(getCheckpoint());
        }
        return true;
    }

    private static String text(OperationResult result) {
        return result.message() == null ? "" : result.message();
    }

    private static String describe(Position position) {
        return position == null ? "an unknown position" : String.format(Locale.ROOT, "X%.3f Y%.3f Z%.3f", position.getX(), position.getY(), position.getZ());
    }
}
