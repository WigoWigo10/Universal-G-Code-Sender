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
import com.willwinder.ugs.nbp.pcb.heightmap.HeightMapException;
import com.willwinder.ugs.nbp.pcb.heightmap.HeightMapFile;
import com.willwinder.ugs.nbp.pcb.heightmap.ProbeArea;
import com.willwinder.ugs.nbp.pcb.heightmap.SyntheticSurface;
import com.willwinder.ugs.nbp.pcb.probing.MachineSnapshot.Condition;
import com.willwinder.ugs.nbp.pcb.probing.OperationResult.Outcome;
import com.willwinder.ugs.nbp.pcb.probing.ProbeOperation.Kind;
import com.willwinder.ugs.nbp.pcb.probing.ProbeSession.FailureKind;
import com.willwinder.ugs.nbp.pcb.probing.ProbeSession.OperatorAuthorization;
import com.willwinder.ugs.nbp.pcb.probing.ProbeSession.RecoveryConfirmation;
import com.willwinder.ugs.nbp.pcb.probing.ProbeSession.State;
import com.willwinder.universalgcodesender.gcode.util.Code;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UnitUtils.Units;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleBinaryOperator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Tests the probing sequence on a simulated machine, with a failure in every phase of it
 */
public class ProbeSessionTest {
    private static final ProbeArea AREA = new ProbeArea(10, 20, 30, 40);
    private static final int POINTS = 25;
    private static final double TRAVEL_Z = 5;
    private static final double PROBE_START_Z = 1.5;
    private static final OperatorAuthorization AUTHORIZED = new OperatorAuthorization(true);
    private static final RecoveryConfirmation CONFIRMED = new RecoveryConfirmation(true, true, true);
    private static final Kind[] PHASES = Kind.values();

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private SimulatedProbeMachine machine;
    private ProbeSession session;
    private final List<ProbeCheckpoint> checkpoints = new ArrayList<>();

    @Before
    public void setUp() {
        createSession(settings(0), SyntheticSurface.BENT_BOARD);
    }

    static ProbeSettings settings(double touchPlateThickness) {
        return new ProbeSettings(AREA, 5, 0.5, 3, List.of(), 2, 1, -1, 50, 300, touchPlateThickness, 1, "V-bit 30 degrees");
    }

    /**
     * A machine with its origin of the work coordinate system at machine X-200 Y-150 Z-40
     */
    static SimulatedProbeMachine machine(DoubleBinaryOperator contactZ) {
        SimulatedProbeMachine result = new SimulatedProbeMachine(contactZ, new MachineEnvelope(-300, -200, -60, 0, 0, 0), new Position(0, 0, 10, Units.MM));
        result.setWorkOffset(new Position(-200, -150, -40, Units.MM));
        return result;
    }

    private void createSession(ProbeSettings settings, DoubleBinaryOperator contactZ) {
        machine = machine(contactZ);
        checkpoints.clear();
        session = new ProbeSession(machine, settings, checkpoints::add);
    }

    private void testSensor() {
        machine.setProbeContact(true);
        session.pollSensor();
        machine.setProbeContact(false);
        session.pollSensor();
    }

    private void prepareAndStart() throws Exception {
        assertEquals(List.of(), session.prepare());
        testSensor();
        assertEquals(State.READY, session.getState());
        session.start(AUTHORIZED);
    }

    /**
     * Lets the machine work until it is given an operation of the kind for the point
     */
    private void runUntil(Kind kind, int pointIndex) {
        while (machine.hasPendingOperation()
                && !(machine.getPendingOperation().kind() == kind && machine.getPendingOperation().pointIndex() == pointIndex && !machine.getExecuted().isEmpty())) {
            machine.completeNext();
        }
        assertTrue("The machine was never given " + kind + " for point " + pointIndex, machine.hasPendingOperation());
    }

    private void assertFailed(FailureKind kind, State phase) {
        assertEquals(State.FAILED, session.getState());
        assertNotNull(session.getFailure());
        assertEquals(session.getFailure().message(), kind, session.getFailure().kind());
        assertEquals(phase, session.getFailure().phase());
        assertFalse("Nothing may be commanded after a failure", machine.hasPendingOperation());
    }

    private static State stateOf(Kind kind) {
        return switch (kind) {
            case RETRACT -> State.RETRACTING;
            case MOVE_XY -> State.MOVING_XY;
            case APPROACH -> State.APPROACHING;
            case PROBE -> State.PROBING;
        };
    }

    private void assertBlocked(String expectedStart) throws Exception {
        List<String> blocks = session.prepare();
        assertTrue(blocks.toString(), blocks.stream().anyMatch(block -> block.startsWith(expectedStart)));
        assertEquals(State.IDLE, session.getState());
        assertFalse(machine.hasPendingOperation());
        assertTrue(machine.getExecuted().isEmpty());
    }

    // The sequence

    @Test
    public void sessionShouldMeasureEveryPoint() throws Exception {
        prepareAndStart();
        int operations = machine.runUntilIdle();

        assertEquals(State.COMPLETED, session.getState());
        assertEquals(1 + 4 * POINTS, operations);
        assertEquals(POINTS, session.getMeasured().size());
        GridHeightMap heightMap = session.getHeightMap();
        assertEquals(AREA, heightMap.getArea());
        for (Position point : heightMap.getPoints()) {
            assertEquals(SyntheticSurface.BENT_BOARD.applyAsDouble(point.getX(), point.getY()), point.getZ(), 1e-9);
        }
        assertNull(session.getFailure());
    }

    @Test
    public void everyPointShouldBeMovedToApproachedProbedAndRetractedFrom() throws Exception {
        prepareAndStart();
        machine.runUntilIdle();

        List<ProbeOperation> executed = machine.getExecuted();
        assertEquals(new ProbeOperation(Kind.RETRACT, 0, Double.NaN, Double.NaN, TRAVEL_Z, 0), executed.get(0));
        for (int point = 0; point < POINTS; point++) {
            ProbeOperation move = executed.get(1 + 4 * point);
            assertEquals(Kind.MOVE_XY, move.kind());
            assertEquals(TRAVEL_Z, move.z(), 0);
            assertEquals(new ProbeOperation(Kind.APPROACH, point, move.x(), move.y(), PROBE_START_Z, 300), executed.get(2 + 4 * point));
            assertEquals(new ProbeOperation(Kind.PROBE, point, move.x(), move.y(), -1, 50), executed.get(3 + 4 * point));
            assertEquals(new ProbeOperation(Kind.RETRACT, point, move.x(), move.y(), TRAVEL_Z, 0), executed.get(4 + 4 * point));
        }
    }

    @Test
    public void machineShouldOnlyBeGivenOneOperationAtATime() throws Exception {
        prepareAndStart();

        // The simulated machine refuses an operation while another is not done
        State[] expected = {State.RETRACTING, State.MOVING_XY, State.APPROACHING, State.PROBING, State.RETRACTING, State.MOVING_XY};
        for (State state : expected) {
            assertEquals(state, session.getState());
            assertTrue(machine.hasPendingOperation());
            assertEquals(session.getCurrentOperation(), machine.getPendingOperation());
            machine.completeNext();
        }
        assertEquals(6, machine.getExecuted().size());
    }

    @Test
    public void toolAboveTheTravelHeightShouldNotBeLoweredBeforeItIsOverAPoint() throws Exception {
        machine.setWorkPosition(0, 0, 18);
        prepareAndStart();

        machine.completeNext();
        assertEquals(18, machine.getSnapshot().workPosition().getZ(), 0);
        machine.completeNext();
        assertEquals(18, machine.getSnapshot().workPosition().getZ(), 0);
        machine.runUntilIdle();

        assertEquals(State.COMPLETED, session.getState());
    }

    @Test
    public void thicknessOfTheTouchPlateShouldBeTakenFromTheContact() throws Exception {
        createSession(settings(1.5), (x, y) -> SyntheticSurface.BENT_BOARD.applyAsDouble(x, y) + 1.5);
        prepareAndStart();
        machine.runUntilIdle();

        assertEquals(State.COMPLETED, session.getState());
        for (Position point : session.getHeightMap().getPoints()) {
            assertEquals(SyntheticSurface.BENT_BOARD.applyAsDouble(point.getX(), point.getY()), point.getZ(), 1e-9);
        }
        // The plate is between the tool and the board, so everything is that much higher
        assertEquals(TRAVEL_Z, machine.getExecuted().get(0).z(), 0);
        assertEquals(PROBE_START_Z + 1.5, machine.getExecuted().get(2).z(), 0);
    }

    // What is verified before anything moves

    @Test
    public void sessionShouldNotBePreparedForAnUnsupportedController() throws Exception {
        machine.setCapabilities(new ProbeCapabilities(false, "unknown firmware", false));
        assertBlocked(PlanVerifier.ERROR_UNSUPPORTED + "unknown firmware");
    }

    @Test
    public void sessionShouldNotBePreparedWithoutConnection() throws Exception {
        machine.setConnected(false);
        assertBlocked(PlanVerifier.ERROR_NOT_CONNECTED);
    }

    @Test
    public void sessionShouldNotBePreparedWhileTheMachineIsNotIdle() throws Exception {
        machine.setCondition(Condition.ALARM);
        assertBlocked(PlanVerifier.ERROR_NOT_IDLE + "ALARM");
    }

    @Test
    public void sessionShouldNotBePreparedWithoutKnownLimits() throws Exception {
        machine.setEnvelope(null);
        assertBlocked(PlanVerifier.ERROR_ENVELOPE_UNKNOWN);
    }

    @Test
    public void sessionShouldNotBePreparedWithoutKnownReference() throws Exception {
        machine.setWorkCoordinateSystem(null);
        assertBlocked(PlanVerifier.ERROR_REFERENCE_UNKNOWN);
        machine.setWorkCoordinateSystem(Code.G54);
        machine.setWorkOffset(null);
        assertBlocked(PlanVerifier.ERROR_REFERENCE_UNKNOWN);
    }

    @Test
    public void sessionShouldNotBePreparedWhenTheSensorCanNotBeRead() throws Exception {
        machine.setProbeContact(null);
        assertBlocked(PlanVerifier.ERROR_SENSOR_UNKNOWN);
        machine.setProbeContact(false);
        machine.setCapabilities(new ProbeCapabilities(true, "Simulated", false));
        assertBlocked(PlanVerifier.ERROR_SENSOR_UNKNOWN);
    }

    @Test
    public void sessionShouldNotBePreparedWhileTheSensorHasContact() throws Exception {
        machine.setProbeContact(true);
        assertBlocked(PlanVerifier.ERROR_SENSOR_CONTACT);
    }

    @Test
    public void sessionShouldNotBePreparedWhenAPointIsOutsideOfTheLimits() throws Exception {
        // The last column is at machine X-170, which is within 1 mm of the limit
        machine.setEnvelope(new MachineEnvelope(-300, -200, -60, -170.5, 0, 0));
        assertBlocked(PlanVerifier.ERROR_OUTSIDE_LIMITS + "X30.000 Y20.000 Z5.000 (machine X-170.000 Y-130.000 Z-35.000), to move point 21");
    }

    @Test
    public void sessionShouldNotBePreparedWhenTheTravelHeightIsOutsideOfTheLimits() throws Exception {
        machine.setEnvelope(new MachineEnvelope(-300, -200, -60, 0, 0, -34.5));
        machine.setWorkPosition(0, 0, 4);
        assertBlocked(PlanVerifier.ERROR_OUTSIDE_LIMITS + "X0.000 Y0.000 Z5.000");
    }

    @Test
    public void sessionShouldNotBePreparedWhenTheProbingGoesBelowTheLimits() throws Exception {
        machine.setEnvelope(new MachineEnvelope(-300, -200, -41.5, 0, 0, 0));
        assertBlocked(PlanVerifier.ERROR_OUTSIDE_LIMITS + "X10.000 Y20.000 Z-1.000 (machine X-190.000 Y-130.000 Z-41.000), to probe point 1");
    }

    @Test
    public void sessionShouldNotBePreparedWhenTheToolIsOutsideOfTheLimits() throws Exception {
        machine.setWorkPosition(250, 0, 10);
        assertBlocked(PlanVerifier.ERROR_TOOL_OUTSIDE_LIMITS);
    }

    @Test
    public void sessionShouldNotBePreparedWithSettingsThatAreNotComplete() throws Exception {
        createSession(new ProbeSettings(AREA, 5, 0.5, 3, List.of(), 2, 1, -1, 0, 300, 0, 1, " "), SyntheticSurface.BENT_BOARD);

        List<String> blocks = session.prepare();

        assertTrue(blocks.contains(ProbeSettings.ERROR_FEED));
        assertTrue(blocks.contains(ProbeSettings.ERROR_TOOL));
        assertEquals(State.IDLE, session.getState());
    }

    @Test
    public void sessionShouldNotBePreparedWhenAPointIsOnAnObstacle() throws Exception {
        createSession(new ProbeSettings(AREA, 5, 0.5, 3, List.of(new Obstacle(new ProbeArea(24, 34, 26, 36), 8)), 2, 1, -1, 50, 300, 0, 1, "V-bit"), SyntheticSurface.BENT_BOARD);

        assertBlocked(ProbePlan.ERROR_ON_OBSTACLE + "X25.000 Y35.000");
    }

    @Test
    public void toolShouldBeMovedAboveEveryObstacle() throws Exception {
        createSession(new ProbeSettings(AREA, 5, 0.5, 3, List.of(new Obstacle(new ProbeArea(50, 50, 60, 60), 8)), 2, 1, -1, 50, 300, 0, 1, "V-bit"), SyntheticSurface.BENT_BOARD);
        prepareAndStart();
        machine.runUntilIdle();

        assertEquals(State.COMPLETED, session.getState());
        assertTrue(machine.getExecuted().stream().filter(operation -> operation.kind() == Kind.MOVE_XY).allMatch(operation -> operation.z() == 10));
    }

    // The sensor test and the authorization

    @Test
    public void sessionShouldNotBeStartedBeforeTheSensorWasTested() throws Exception {
        assertEquals(List.of(), session.prepare());
        assertEquals(State.SENSOR_TEST_AWAIT_CONTACT, session.getState());
        assertThrows(ProbeSessionException.class, () -> session.start(AUTHORIZED));

        machine.setProbeContact(true);
        session.pollSensor();
        assertEquals(State.SENSOR_TEST_AWAIT_RELEASE, session.getState());
        assertThrows(ProbeSessionException.class, () -> session.start(AUTHORIZED));

        machine.setProbeContact(false);
        session.pollSensor();
        assertEquals(State.READY, session.getState());
        assertFalse(machine.hasPendingOperation());
    }

    @Test
    public void sensorTestShouldEndWhenTheSensorCanNoLongerBeRead() throws Exception {
        session.prepare();
        machine.setProbeContact(null);

        session.pollSensor();

        assertEquals(State.IDLE, session.getState());
    }

    @Test
    public void sessionShouldNotBeStartedWithoutAuthorization() throws Exception {
        session.prepare();
        testSensor();

        assertEquals(ProbeSession.ERROR_NOT_AUTHORIZED, assertThrows(ProbeSessionException.class, () -> session.start(new OperatorAuthorization(false))).getMessage());
        assertEquals(ProbeSession.ERROR_NOT_AUTHORIZED, assertThrows(ProbeSessionException.class, () -> session.start(null)).getMessage());

        assertEquals(State.READY, session.getState());
        assertFalse(machine.hasPendingOperation());
    }

    @Test
    public void everythingShouldBeVerifiedAgainWhenTheSessionIsStarted() throws Exception {
        session.prepare();
        testSensor();
        machine.setEnvelope(null);

        ProbeSessionException exception = assertThrows(ProbeSessionException.class, () -> session.start(AUTHORIZED));

        assertEquals(List.of(PlanVerifier.ERROR_ENVELOPE_UNKNOWN), exception.getReasons());
        assertEquals(State.IDLE, session.getState());
        assertFalse(machine.hasPendingOperation());
    }

    // Failures in every phase

    @Test
    public void disconnectionShouldEndTheSequenceInEveryPhase() throws Exception {
        for (Kind phase : PHASES) {
            createSession(settings(0), SyntheticSurface.BENT_BOARD);
            prepareAndStart();
            runUntil(phase, 2);
            int executed = machine.getExecuted().size();
            machine.setFault(operation -> new OperationResult(Outcome.DISCONNECTED, null, "Port closed"));

            machine.completeNext();

            assertFailed(FailureKind.DISCONNECTED, stateOf(phase));
            assertEquals(executed + 1, machine.getExecuted().size());
            // The point is measured when the tool is raised from it
            assertEquals(phase.toString(), phase == Kind.RETRACT ? 3 : 2, session.getMeasured().size());
        }
    }

    @Test
    public void lostConnectionBetweenOperationsShouldEndTheSequenceInEveryPhase() throws Exception {
        for (Kind phase : PHASES) {
            createSession(settings(0), SyntheticSurface.BENT_BOARD);
            prepareAndStart();
            runUntil(phase, 2);
            machine.setAfterOperation(operation -> machine.setConnected(false));

            machine.completeNext();

            assertFailed(FailureKind.DISCONNECTED, stateOf(phase));
        }
    }

    @Test
    public void alarmShouldEndTheSequenceInEveryPhase() throws Exception {
        for (Kind phase : PHASES) {
            createSession(settings(0), SyntheticSurface.BENT_BOARD);
            prepareAndStart();
            runUntil(phase, 2);
            machine.setFault(operation -> {
                machine.setCondition(Condition.ALARM);
                return new OperationResult(Outcome.ALARM, null, "ALARM:1");
            });

            machine.completeNext();

            assertFailed(FailureKind.ALARM, stateOf(phase));
            assertTrue(session.getFailure().message().contains("ALARM:1"));
        }
    }

    @Test
    public void alarmThatIsOnlySeenInTheStateOfTheMachineShouldEndTheSequenceInEveryPhase() throws Exception {
        for (Kind phase : PHASES) {
            createSession(settings(0), SyntheticSurface.BENT_BOARD);
            prepareAndStart();
            runUntil(phase, 2);
            machine.setAfterOperation(operation -> machine.setCondition(Condition.ALARM));

            machine.completeNext();

            assertFailed(FailureKind.ALARM, stateOf(phase));
        }
    }

    @Test
    public void errorOfTheControllerShouldEndTheSequenceInEveryPhase() throws Exception {
        for (Kind phase : PHASES) {
            createSession(settings(0), SyntheticSurface.BENT_BOARD);
            prepareAndStart();
            runUntil(phase, 2);
            machine.setFault(operation -> new OperationResult(Outcome.ERROR, null, "error:20"));

            machine.completeNext();

            assertFailed(FailureKind.CONTROLLER_ERROR, stateOf(phase));
        }
    }

    @Test
    public void machineOnHoldShouldEndTheSequenceInEveryPhase() throws Exception {
        for (Kind phase : new Kind[]{Kind.RETRACT, Kind.MOVE_XY, Kind.APPROACH}) {
            createSession(settings(0), SyntheticSurface.BENT_BOARD);
            prepareAndStart();
            runUntil(phase, 2);
            machine.setAfterOperation(operation -> machine.setCondition(Condition.HOLD));

            machine.completeNext();

            assertFailed(FailureKind.NOT_IDLE, stateOf(phase));
        }
    }

    @Test
    public void changedWorkCoordinateSystemShouldEndTheSequenceInEveryPhase() throws Exception {
        for (Kind phase : PHASES) {
            createSession(settings(0), SyntheticSurface.BENT_BOARD);
            prepareAndStart();
            runUntil(phase, 2);
            int executed = machine.getExecuted().size();
            machine.setAfterOperation(operation -> machine.setWorkCoordinateSystem(Code.G55));

            machine.completeNext();

            // After a probing it is found when the next operation is about to be given
            assertEquals(State.FAILED, session.getState());
            assertEquals(phase.toString(), FailureKind.REFERENCE_CHANGED, session.getFailure().kind());
            assertFalse(machine.hasPendingOperation());
            assertEquals(executed + 1, machine.getExecuted().size());
            assertTrue(session.getFailure().message(), session.getFailure().message().contains("G55"));
        }
    }

    @Test
    public void changedOriginShouldEndTheSequenceInEveryPhase() throws Exception {
        for (Kind phase : PHASES) {
            createSession(settings(0), SyntheticSurface.BENT_BOARD);
            prepareAndStart();
            runUntil(phase, 2);
            // Z zero was set again, by 0.3 mm
            machine.setAfterOperation(operation -> machine.setWorkOffset(new Position(-200, -150, -40.3, Units.MM)));

            machine.completeNext();

            assertEquals(State.FAILED, session.getState());
            assertEquals(phase.toString(), FailureKind.REFERENCE_CHANGED, session.getFailure().kind());
            assertFalse(machine.hasPendingOperation());
        }
    }

    @Test
    public void positionThatIsNotWhereTheToolWasSentShouldEndTheSequenceInEveryPhase() throws Exception {
        for (Kind phase : PHASES) {
            createSession(settings(0), SyntheticSurface.BENT_BOARD);
            prepareAndStart();
            runUntil(phase, 2);
            ProbeOperation operation = machine.getPendingOperation();
            Outcome outcome = phase == Kind.PROBE ? Outcome.CONTACT : Outcome.COMPLETED;
            double z = phase == Kind.PROBE ? 0.1 : operation.z();
            machine.setFault(ignored -> new OperationResult(outcome, new Position(operation.x() + 0.5, operation.y(), z, Units.MM), null));

            machine.completeNext();

            assertFailed(FailureKind.INCONSISTENT_POSITION, stateOf(phase));
            assertEquals(phase == Kind.RETRACT ? 3 : 2, session.getMeasured().size());
        }
    }

    @Test
    public void unknownPositionShouldEndTheSequence() throws Exception {
        prepareAndStart();
        runUntil(Kind.APPROACH, 0);
        machine.setFault(operation -> new OperationResult(Outcome.COMPLETED, null, null));

        machine.completeNext();

        assertFailed(FailureKind.INCONSISTENT_POSITION, State.APPROACHING);
    }

    @Test
    public void contactWhileMovingShouldEndTheSequence() throws Exception {
        for (Kind phase : new Kind[]{Kind.MOVE_XY, Kind.APPROACH}) {
            createSession(settings(0), SyntheticSurface.BENT_BOARD);
            prepareAndStart();
            runUntil(phase, 2);
            machine.setAfterOperation(operation -> machine.setProbeContact(true));

            machine.completeNext();

            assertFailed(FailureKind.UNEXPECTED_CONTACT, stateOf(phase));
        }
    }

    @Test
    public void toolThatIsLoweredOntoSomethingBeforeProbingShouldEndTheSequence() throws Exception {
        // Something is 2 mm high where the board was expected to be at most 0.5 mm
        createSession(settings(0), (x, y) -> 2);
        prepareAndStart();
        machine.runUntilIdle();

        assertFailed(FailureKind.UNEXPECTED_CONTACT, State.APPROACHING);
        assertEquals(Kind.APPROACH, machine.getExecuted().get(machine.getExecuted().size() - 1).kind());
        assertTrue(session.getMeasured().isEmpty());
    }

    @Test
    public void contactAboveTheHighestExpectedSurfaceShouldEndTheSequence() throws Exception {
        createSession(settings(0), (x, y) -> 1.2);
        prepareAndStart();
        machine.runUntilIdle();

        assertFailed(FailureKind.PREMATURE_CONTACT, State.PROBING);
        // The tool is left where it stopped
        assertEquals(Kind.PROBE, machine.getExecuted().get(machine.getExecuted().size() - 1).kind());
        assertEquals(1.2, machine.getSnapshot().workPosition().getZ(), 0);
        assertTrue(session.getMeasured().isEmpty());
        assertTrue(checkpoints.isEmpty());
    }

    @Test
    public void probingWithoutContactShouldEndTheSequence() throws Exception {
        createSession(settings(0), (x, y) -> x > 12 ? -5 : 0.1);
        prepareAndStart();
        machine.runUntilIdle();

        assertFailed(FailureKind.NO_CONTACT, State.PROBING);
        assertEquals(Kind.PROBE, machine.getExecuted().get(machine.getExecuted().size() - 1).kind());
        assertEquals(-1, machine.getSnapshot().workPosition().getZ(), 0);
        // The first column was measured before the board ended
        assertEquals(5, session.getMeasured().size());
    }

    @Test
    public void contactThatIsNotReleasedShouldEndTheSequence() throws Exception {
        prepareAndStart();
        runUntil(Kind.PROBE, 1);
        machine.setContactStuck(true);
        machine.runUntilIdle();

        assertFailed(FailureKind.CONTACT_NOT_RELEASED, State.RETRACTING);
        assertEquals(2, session.getMeasured().size());
    }

    @Test
    public void sensorThatCanNoLongerBeReadShouldEndTheSequence() throws Exception {
        prepareAndStart();
        runUntil(Kind.MOVE_XY, 1);
        machine.setAfterOperation(operation -> machine.setProbeContact(null));

        machine.completeNext();

        assertFailed(FailureKind.SENSOR_UNKNOWN, State.MOVING_XY);
    }

    @Test
    public void resultOfAnOperationThatWasGivenUpOnShouldBeIgnored() throws Exception {
        prepareAndStart();
        runUntil(Kind.MOVE_XY, 1);
        machine.setFault(operation -> new OperationResult(Outcome.ALARM, null, "ALARM:2"));
        machine.completeNext();
        assertFailed(FailureKind.ALARM, State.MOVING_XY);

        // Nothing that the machine tells afterwards moves the session on
        assertEquals(1, session.getMeasured().size());
        assertFalse(machine.completeNext());
        assertEquals(State.FAILED, session.getState());
    }

    // Pause and resume

    @Test
    public void pauseShouldFinishThePointAndStopAtTheTravelHeight() throws Exception {
        prepareAndStart();
        runUntil(Kind.APPROACH, 1);

        session.pause();
        int operations = machine.runUntilIdle();

        assertEquals(State.PAUSED, session.getState());
        assertEquals(3, operations);
        assertEquals(2, session.getMeasured().size());
        assertEquals(TRAVEL_Z, machine.getSnapshot().workPosition().getZ(), 0);
        assertFalse(machine.getSnapshot().probeContact());
        assertFalse(machine.hasPendingOperation());
    }

    @Test
    public void resumeShouldMeasureTheRemainingPointsOnce() throws Exception {
        prepareAndStart();
        runUntil(Kind.APPROACH, 1);
        session.pause();
        machine.runUntilIdle();

        session.resume(AUTHORIZED);
        machine.runUntilIdle();

        assertEquals(State.COMPLETED, session.getState());
        assertEquals(POINTS, machine.getExecuted().stream().filter(operation -> operation.kind() == Kind.PROBE).count());
        assertEquals(AREA, session.getHeightMap().getArea());
    }

    @Test
    public void resumeShouldNeedAuthorization() throws Exception {
        prepareAndStart();
        runUntil(Kind.PROBE, 1);
        session.pause();
        machine.runUntilIdle();

        assertEquals(ProbeSession.ERROR_NOT_AUTHORIZED, assertThrows(ProbeSessionException.class, () -> session.resume(new OperatorAuthorization(false))).getMessage());

        assertEquals(State.PAUSED, session.getState());
        assertFalse(machine.hasPendingOperation());
    }

    @Test
    public void resumeShouldStartFromWhereTheToolWasLeft() throws Exception {
        prepareAndStart();
        runUntil(Kind.PROBE, 1);
        session.pause();
        machine.runUntilIdle();
        // The operator moved the tool while the session was paused
        machine.setWorkPosition(-20, 5, 2);

        session.resume(AUTHORIZED);
        assertEquals(new ProbeOperation(Kind.RETRACT, 0, Double.NaN, Double.NaN, TRAVEL_Z, 0), machine.getPendingOperation());
        machine.runUntilIdle();

        assertEquals(State.COMPLETED, session.getState());
        assertEquals(POINTS, session.getMeasured().size());
    }

    @Test
    public void resumeShouldEndTheSessionWhenTheWorkCoordinateSystemWasChanged() throws Exception {
        prepareAndStart();
        runUntil(Kind.PROBE, 1);
        session.pause();
        machine.runUntilIdle();
        machine.setWorkCoordinateSystem(Code.G56);

        assertThrows(ProbeSessionException.class, () -> session.resume(AUTHORIZED));

        assertEquals(State.FAILED, session.getState());
        assertEquals(FailureKind.REFERENCE_CHANGED, session.getFailure().kind());
        assertFalse(machine.hasPendingOperation());
    }

    @Test
    public void resumeShouldBeRefusedWhileSomethingIsWrongWithTheMachine() throws Exception {
        prepareAndStart();
        runUntil(Kind.PROBE, 1);
        session.pause();
        machine.runUntilIdle();

        machine.setProbeContact(true);
        assertEquals(List.of(PlanVerifier.ERROR_SENSOR_CONTACT), assertThrows(ProbeSessionException.class, () -> session.resume(AUTHORIZED)).getReasons());
        machine.setProbeContact(false);
        machine.setEnvelope(new MachineEnvelope(-300, -200, -60, -175, 0, 0));
        assertTrue(assertThrows(ProbeSessionException.class, () -> session.resume(AUTHORIZED)).getMessage().startsWith(PlanVerifier.ERROR_OUTSIDE_LIMITS));
        machine.setEnvelope(null);
        assertEquals(List.of(PlanVerifier.ERROR_ENVELOPE_UNKNOWN), assertThrows(ProbeSessionException.class, () -> session.resume(AUTHORIZED)).getReasons());

        assertEquals(State.PAUSED, session.getState());
        assertFalse(machine.hasPendingOperation());
    }

    @Test
    public void pauseShouldOnlyBePossibleWhileMeasuring() throws Exception {
        assertThrows(ProbeSessionException.class, () -> session.pause());
        session.prepare();
        assertThrows(ProbeSessionException.class, () -> session.pause());
        assertThrows(ProbeSessionException.class, () -> session.resume(AUTHORIZED));
    }

    // Recovery

    private void failAtPoint(int pointIndex) throws Exception {
        prepareAndStart();
        runUntil(Kind.PROBE, pointIndex);
        machine.setFault(operation -> {
            machine.setCondition(Condition.ALARM);
            return new OperationResult(Outcome.ALARM, null, "ALARM:4");
        });
        machine.completeNext();
        assertEquals(State.FAILED, session.getState());
        machine.setFault(operation -> null);
    }

    @Test
    public void failedSessionShouldOnlyGoOnWhenTheOperatorConfirmsEverything() throws Exception {
        failAtPoint(3);

        for (RecoveryConfirmation incomplete : List.of(new RecoveryConfirmation(false, true, true), new RecoveryConfirmation(true, false, true), new RecoveryConfirmation(true, true, false))) {
            assertEquals(ProbeSession.ERROR_RECOVERY, assertThrows(ProbeSessionException.class, () -> session.recover(incomplete)).getMessage());
            assertEquals(State.FAILED, session.getState());
        }
        assertThrows(ProbeSessionException.class, () -> session.prepare());
        assertThrows(ProbeSessionException.class, () -> session.start(AUTHORIZED));
        assertThrows(ProbeSessionException.class, () -> session.resume(AUTHORIZED));
        assertFalse(machine.hasPendingOperation());
    }

    @Test
    public void recoveredSessionShouldBeVerifiedAndTestedAgainBeforeItMoves() throws Exception {
        failAtPoint(3);

        session.recover(CONFIRMED);

        assertEquals(State.IDLE, session.getState());
        assertFalse(machine.hasPendingOperation());
        assertThrows(ProbeSessionException.class, () -> session.start(AUTHORIZED));
        // The alarm is still there
        assertEquals(List.of(PlanVerifier.ERROR_NOT_IDLE + "ALARM"), session.prepare());

        machine.setCondition(Condition.IDLE);
        assertEquals(List.of(), session.prepare());
        assertEquals(State.SENSOR_TEST_AWAIT_CONTACT, session.getState());
        assertFalse(machine.hasPendingOperation());
    }

    @Test
    public void recoveredSessionShouldMeasureTheRemainingPoints() throws Exception {
        failAtPoint(3);
        session.recover(CONFIRMED);
        machine.setCondition(Condition.IDLE);

        prepareAndStart();
        machine.runUntilIdle();

        assertEquals(State.COMPLETED, session.getState());
        assertEquals(POINTS, session.getMeasured().size());
        assertEquals(AREA, session.getHeightMap().getArea());
    }

    @Test
    public void recoveredSessionShouldNotGoOnWithAnotherReferenceThanItsMeasurements() throws Exception {
        failAtPoint(3);
        session.recover(CONFIRMED);
        machine.setCondition(Condition.IDLE);
        // Homing again gave another origin
        machine.setWorkOffset(new Position(-200, -150, -40.2, Units.MM));

        List<String> blocks = session.prepare();

        assertEquals(1, blocks.size());
        assertTrue(blocks.get(0), blocks.get(0).startsWith("The work coordinate system or its origin is not what the earlier points of this session were measured from"));
        assertEquals(State.IDLE, session.getState());

        session.discardMeasurements();
        prepareAndStart();
        machine.runUntilIdle();
        assertEquals(State.COMPLETED, session.getState());
        assertEquals(POINTS, machine.getExecuted().stream().filter(operation -> operation.kind() == Kind.PROBE && operation.pointIndex() >= 0).count() - 4);
    }

    // Partial height maps and checkpoints

    @Test
    public void incompleteSessionShouldHaveNoHeightMap() throws Exception {
        assertEquals(ProbeSession.ERROR_INCOMPLETE + "0 of 25", assertThrows(ProbeSessionException.class, () -> session.getHeightMap()).getMessage());

        prepareAndStart();
        // Two whole columns, which are a complete grid of a part of the board
        runUntil(Kind.MOVE_XY, 10);
        assertEquals(10, session.getMeasured().size());
        assertEquals(ProbeSession.ERROR_INCOMPLETE + "10 of 25", assertThrows(ProbeSessionException.class, () -> session.getHeightMap()).getMessage());

        session.pause();
        machine.runUntilIdle();
        assertEquals(State.PAUSED, session.getState());
        assertThrows(ProbeSessionException.class, () -> session.getHeightMap());
        assertFalse(session.getCheckpoint().isComplete());
    }

    @Test
    public void failedSessionShouldHaveNoHeightMap() throws Exception {
        failAtPoint(24);

        assertEquals(24, session.getMeasured().size());
        assertEquals(ProbeSession.ERROR_INCOMPLETE + "24 of 25", assertThrows(ProbeSessionException.class, () -> session.getHeightMap()).getMessage());
    }

    @Test
    public void checkpointShouldBeMadeForEveryMeasuredPoint() throws Exception {
        prepareAndStart();
        machine.runUntilIdle();

        assertEquals(POINTS, checkpoints.size());
        for (int i = 0; i < POINTS; i++) {
            assertEquals(i + 1, checkpoints.get(i).measured().size());
            assertEquals(POINTS, checkpoints.get(i).plannedPoints());
            assertEquals(i == POINTS - 1, checkpoints.get(i).isComplete());
            assertEquals(checkpoints.get(0).sessionId(), checkpoints.get(i).sessionId());
            assertEquals(new ReferenceFrame(Code.G54, -200, -150, -40), checkpoints.get(i).reference());
        }
    }

    @Test
    public void checkpointShouldNeverBeReadAsAHeightMap() throws Exception {
        prepareAndStart();
        runUntil(Kind.MOVE_XY, 10);
        File file = new File(folder.getRoot(), "board." + ProbeCheckpointFile.EXTENSION);
        ProbeCheckpointFile.write(session.getCheckpoint(), file);

        // Its ten points are a complete grid of two columns, which a height map could be made of
        GridHeightMap.of(session.getMeasured());
        HeightMapException exception = assertThrows(HeightMapException.class, () -> HeightMapFile.read(file, Units.MM));
        assertTrue(exception.getMessage(), exception.getMessage().startsWith(HeightMapFile.ERROR_POINT));
    }

    @Test
    public void checkpointShouldBeTheSameWhenItIsReadAgain() throws Exception {
        prepareAndStart();
        runUntil(Kind.MOVE_XY, 7);
        ProbeCheckpoint checkpoint = session.getCheckpoint();
        File file = new File(folder.getRoot(), "board.pcbprobe");

        ProbeCheckpointFile.write(checkpoint, file);
        ProbeCheckpoint read = ProbeCheckpointFile.read(file);

        assertEquals(checkpoint.sessionId(), read.sessionId());
        assertEquals(checkpoint.reference(), read.reference());
        assertEquals(checkpoint.gridArea(), read.gridArea());
        assertEquals(checkpoint.plannedPoints(), read.plannedPoints());
        assertEquals(7, read.measured().size());
        for (int i = 0; i < 7; i++) {
            assertEquals(checkpoint.measured().get(i).getZ(), read.measured().get(i).getZ(), 1e-6);
        }
    }

    @Test
    public void damagedCheckpointShouldNotBeRead() throws Exception {
        prepareAndStart();
        runUntil(Kind.MOVE_XY, 7);
        File file = new File(folder.getRoot(), "board.pcbprobe");
        ProbeCheckpointFile.write(session.getCheckpoint(), file);
        List<String> lines = java.nio.file.Files.readAllLines(file.toPath());

        java.nio.file.Files.write(file.toPath(), lines.subList(0, lines.size() - 2));
        assertTrue(assertThrows(ProbeSessionException.class, () -> ProbeCheckpointFile.read(file)).getMessage().startsWith(ProbeCheckpointFile.ERROR_INVALID));

        java.nio.file.Files.write(file.toPath(), lines.subList(1, lines.size()));
        assertTrue(assertThrows(ProbeSessionException.class, () -> ProbeCheckpointFile.read(file)).getMessage().startsWith(ProbeCheckpointFile.ERROR_INVALID));

        assertTrue(assertThrows(ProbeSessionException.class, () -> ProbeCheckpointFile.read(new File(folder.getRoot(), "missing.pcbprobe"))).getMessage().startsWith(ProbeCheckpointFile.ERROR_READ));
    }

    @Test
    public void sessionShouldGoOnFromACheckpoint() throws Exception {
        prepareAndStart();
        runUntil(Kind.MOVE_XY, 7);
        ProbeCheckpoint checkpoint = session.getCheckpoint();

        // The application was started again
        SimulatedProbeMachine newMachine = machine(SyntheticSurface.BENT_BOARD);
        ProbeSession restored = new ProbeSession(newMachine, settings(0), null, checkpoint);
        assertEquals(7, restored.getMeasured().size());
        assertThrows(ProbeSessionException.class, restored::getHeightMap);

        assertEquals(List.of(), restored.prepare());
        newMachine.setProbeContact(true);
        restored.pollSensor();
        newMachine.setProbeContact(false);
        restored.pollSensor();
        restored.start(AUTHORIZED);
        newMachine.runUntilIdle();

        assertEquals(State.COMPLETED, restored.getState());
        assertEquals(POINTS - 7, newMachine.getExecuted().stream().filter(operation -> operation.kind() == Kind.PROBE).count());
        assertEquals(AREA, restored.getHeightMap().getArea());
    }

    @Test
    public void sessionShouldNotGoOnFromACheckpointWithAnotherReference() throws Exception {
        prepareAndStart();
        runUntil(Kind.MOVE_XY, 7);
        ProbeCheckpoint checkpoint = session.getCheckpoint();
        SimulatedProbeMachine newMachine = machine(SyntheticSurface.BENT_BOARD);
        newMachine.setWorkCoordinateSystem(Code.G55);

        ProbeSession restored = new ProbeSession(newMachine, settings(0), null, checkpoint);
        List<String> blocks = restored.prepare();

        assertEquals(1, blocks.size());
        assertTrue(blocks.get(0), blocks.get(0).contains("G55"));
        assertEquals(State.IDLE, restored.getState());
    }

    @Test
    public void sessionShouldNotGoOnFromACheckpointOfOtherPoints() throws Exception {
        prepareAndStart();
        runUntil(Kind.MOVE_XY, 7);
        ProbeCheckpoint checkpoint = session.getCheckpoint();
        ProbeSettings otherStep = new ProbeSettings(AREA, 4, 0.5, 3, List.of(), 2, 1, -1, 50, 300, 0, 1, "V-bit");
        ProbeSettings otherArea = new ProbeSettings(new ProbeArea(11, 20, 31, 40), 5, 0.5, 3, List.of(), 2, 1, -1, 50, 300, 0, 1, "V-bit");

        assertEquals(ProbeSession.ERROR_CHECKPOINT, assertThrows(ProbeSessionException.class, () -> new ProbeSession(machine, otherStep, null, checkpoint)).getMessage());
        assertEquals(ProbeSession.ERROR_CHECKPOINT, assertThrows(ProbeSessionException.class, () -> new ProbeSession(machine, otherArea, null, checkpoint)).getMessage());
    }
}
