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
package com.willwinder.ugs.nbp.pcb.service;

import com.willwinder.ugs.nbp.pcb.geometry.Point2D;
import com.willwinder.ugs.nbp.pcb.heightmap.ProbeArea;
import com.willwinder.ugs.nbp.pcb.probing.BackendMachineInspector;
import com.willwinder.ugs.nbp.pcb.probing.MachineEnvelope;
import com.willwinder.ugs.nbp.pcb.probing.MachineSnapshot;
import com.willwinder.ugs.nbp.pcb.probing.PlanVerifier;
import com.willwinder.ugs.nbp.pcb.probing.ProbeOperation;
import com.willwinder.ugs.nbp.pcb.probing.ProbePlan;
import com.willwinder.ugs.nbp.pcb.probing.ProbeSession;
import com.willwinder.ugs.nbp.pcb.probing.ProbeSession.OperatorAuthorization;
import com.willwinder.ugs.nbp.pcb.probing.ProbeSession.State;
import com.willwinder.ugs.nbp.pcb.probing.ProbeSessionException;
import com.willwinder.ugs.nbp.pcb.probing.ProbeSettings;
import com.willwinder.ugs.nbp.pcb.probing.SimulatedProbeMachine;
import com.willwinder.universalgcodesender.model.BackendAPI;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UnitUtils.Units;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Plans the probing of the board of the loaded program, tells why it can not be carried out on
 * the connected machine, and simulates all of its sequence. It does not move the machine.
 */
public class PcbProbingService {
    /**
     * What a simulation of a plan came to
     *
     * @param isCompleted  if all points were measured
     * @param failure      why not, null if they were
     * @param operations   every operation that the machine was given, in order
     * @param measured     the number of points that were measured
     * @param assumptions  what was taken for granted in the simulation and is not known of the real machine
     */
    public record Simulation(boolean isCompleted, String failure, List<String> operations, int measured, List<String> assumptions) {
    }

    public static final String ASSUMPTION_LIMITS = "The limits of the machine are not known, so the simulated machine has none: the path was not verified against them.";
    public static final String ASSUMPTION_REFERENCE = "The work coordinate system and its origin are not known, G54 with its origin at machine zero was simulated.";
    public static final String ASSUMPTION_POSITION = "The position of the tool is not known, it was simulated above the first point at the travel height.";
    public static final String ASSUMPTION_SURFACE = "The sensor was simulated to have contact at Z%.3f everywhere, and to work.";

    private final BackendMachineInspector inspector;
    private final PcbHeightMapService heightMapService;

    public PcbProbingService(BackendAPI backend, PcbHeightMapService heightMapService) {
        this.inspector = new BackendMachineInspector(backend);
        this.heightMapService = heightMapService;
    }

    public BackendMachineInspector getMachine() {
        return inspector;
    }

    /**
     * Returns the area that has to be measured for the loaded program: all that it cuts, with a margin
     */
    public Optional<ProbeArea> getAreaToMeasure(double marginMM) {
        return heightMapService.getAreaToMeasure(marginMM);
    }

    public List<Position> getToolPath() {
        return heightMapService.getToolPath();
    }

    public ProbePlan plan(ProbeSettings settings) {
        return ProbePlan.create(settings, List.of());
    }

    /**
     * Tells why the plan can not be carried out on the connected machine
     */
    public List<String> findBlocks(ProbePlan plan) {
        return PlanVerifier.findBlocks(plan, inspector);
    }

    /**
     * Carries out all of the plan on a machine that only exists in memory, with the same session
     * that would carry it out on a real one. What is known of the connected machine is used, and
     * what is not known is told as an assumption.
     */
    public Simulation simulate(ProbePlan plan) {
        if (!plan.isValid()) {
            return new Simulation(false, String.join("\n", plan.problems()), List.of(), 0, List.of());
        }

        ProbeSettings settings = plan.settings();
        List<String> assumptions = new ArrayList<>();
        MachineSnapshot real = inspector.getSnapshot();
        double contactZ = settings.touchPlateThicknessMM();
        assumptions.add(String.format(Locale.ROOT, ASSUMPTION_SURFACE, contactZ));
        assumptions.add(ASSUMPTION_LIMITS);

        Position start = real.workPosition();
        if (start == null) {
            Point2D first = plan.points().get(0);
            start = new Position(first.x(), first.y(), settings.travelZ(), Units.MM);
            assumptions.add(ASSUMPTION_POSITION);
        }
        SimulatedProbeMachine machine = new SimulatedProbeMachine((x, y) -> contactZ, null, start);
        // The limits are not known, but a machine without limits is the only one that every plan fits in
        machine.setEnvelope(new MachineEnvelope(-1e9, -1e9, -1e9, 1e9, 1e9, 1e9));
        if (real.getReferenceFrame() != null) {
            machine.setWorkCoordinateSystem(real.workCoordinateSystem());
            machine.setWorkOffset(real.workOffset());
        } else {
            assumptions.add(ASSUMPTION_REFERENCE);
        }

        ProbeSession session = new ProbeSession(machine, settings, null);
        try {
            List<String> blocks = session.prepare();
            if (!blocks.isEmpty()) {
                return new Simulation(false, String.join("\n", blocks), List.of(), 0, assumptions);
            }
            machine.setProbeContact(true);
            session.pollSensor();
            machine.setProbeContact(false);
            session.pollSensor();
            session.start(new OperatorAuthorization(true));
            machine.runUntilIdle();
        } catch (ProbeSessionException e) {
            return new Simulation(false, e.getMessage(), describe(machine.getExecuted()), session.getMeasured().size(), assumptions);
        }

        String failure = session.getState() == State.COMPLETED ? null
                : session.getFailure() != null ? session.getFailure().message() : "The simulation ended while the session was " + session.getState();
        return new Simulation(session.getState() == State.COMPLETED, failure, describe(machine.getExecuted()), session.getMeasured().size(), assumptions);
    }

    private static List<String> describe(List<ProbeOperation> operations) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < operations.size(); i++) {
            ProbeOperation operation = operations.get(i);
            result.add(String.format(Locale.ROOT, "%4d  #%-3d  %s", i + 1, operation.pointIndex() + 1, operation.describe()));
        }
        return result;
    }
}
