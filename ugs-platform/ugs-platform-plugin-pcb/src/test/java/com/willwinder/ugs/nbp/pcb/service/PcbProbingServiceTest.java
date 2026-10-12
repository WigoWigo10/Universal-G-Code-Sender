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

import com.willwinder.ugs.nbp.pcb.geometry.RigidTransform2D;
import com.willwinder.ugs.nbp.pcb.heightmap.ProbeArea;
import com.willwinder.ugs.nbp.pcb.probing.BackendMachineInspector;
import com.willwinder.ugs.nbp.pcb.probing.OperationResult;
import com.willwinder.ugs.nbp.pcb.probing.OperationResult.Outcome;
import com.willwinder.ugs.nbp.pcb.probing.PlanVerifier;
import com.willwinder.ugs.nbp.pcb.probing.ProbeOperation;
import com.willwinder.ugs.nbp.pcb.probing.ProbePlan;
import com.willwinder.ugs.nbp.pcb.probing.ProbeSettings;
import com.willwinder.ugs.nbp.pcb.service.PcbProbingService.Simulation;
import com.willwinder.universalgcodesender.AbstractController;
import com.willwinder.universalgcodesender.IController;
import com.willwinder.universalgcodesender.firmware.IFirmwareSettings;
import com.willwinder.universalgcodesender.gcode.DefaultCommandCreator;
import com.willwinder.universalgcodesender.gcode.GcodeState;
import com.willwinder.universalgcodesender.gcode.util.Code;
import com.willwinder.universalgcodesender.listeners.ControllerState;
import com.willwinder.universalgcodesender.listeners.ControllerStatus;
import com.willwinder.universalgcodesender.model.GUIBackend;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UnitUtils.Units;
import com.willwinder.universalgcodesender.services.BackendFileLoader;
import com.willwinder.universalgcodesender.utils.Settings;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class PcbProbingServiceTest {
    private static final RigidTransform2D TRANSFORM = new RigidTransform2D(Math.toRadians(30), 10, 5);

    private static final List<String> PROGRAM = List.of(
            "G21 G90 G17",
            "G0 Z5",
            "G0 X5 Y5",
            "G1 Z-0.1 F50",
            "G1 X25 F100",
            "G1 Y20",
            "G1 X10 Y10",
            "G0 Z5",
            "M5");

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private AbstractController controller;
    private GUIBackend backend;
    private PcbProbingService service;

    @Before
    public void setUp() throws Exception {
        controller = mock(AbstractController.class);
        backend = new GUIBackend() {
            @Override
            protected IController fetchControllerFromFirmware(String firmware) {
                return controller;
            }
        };
        doReturn(mock(IFirmwareSettings.class)).when(controller).getFirmwareSettings();
        when(controller.getCommandCreator()).thenReturn(new DefaultCommandCreator());
        backend.applySettings(new Settings());

        PcbAlignmentService alignmentService = new PcbAlignmentService(backend, new BackendFileLoader(backend), e -> {
        });
        service = new PcbProbingService(backend, new PcbHeightMapService(backend, alignmentService));

        File source = new File(folder.getRoot(), "board.nc");
        Files.write(source.toPath(), PROGRAM, StandardCharsets.UTF_8);
        backend.setGcodeFile(source);
        alignmentService.alignLoadedFile(TRANSFORM, Code.G54);
    }

    private void connect() throws Exception {
        when(controller.isCommOpen()).thenReturn(true);
        GcodeState machineState = new GcodeState();
        machineState.offset = Code.G54;
        when(controller.getCurrentGcodeState()).thenReturn(machineState);
        when(controller.getControllerStatus()).thenReturn(new ControllerStatus(ControllerState.IDLE,
                new Position(-180, -140, -30, Units.MM), new Position(20, 10, 10, Units.MM)));
        backend.connect("GRBL", "/dev/ttyS0", 9600);
    }

    private ProbeSettings settings() {
        ProbeArea area = service.getAreaToMeasure(2).orElseThrow();
        return new ProbeSettings(area, 5, 0.5, 3, List.of(), 2, 1, -1, 50, 300, 0, 1, "V-bit");
    }

    private void assertNothingWasSent() throws Exception {
        verify(controller, never()).sendCommandImmediately(any());
        verify(controller, never()).probe(anyString(), anyDouble(), anyDouble(), any());
        verify(controller, never()).queueStream(any());
        verify(controller, never()).beginStreaming();
    }

    @Test
    public void areaToMeasureShouldBeWhatTheLoadedProgramCuts() {
        ProbeArea area = service.getAreaToMeasure(2).orElseThrow();

        assertTrue(area.toString(), area.width() > 20 && area.width() < 40);
        assertFalse(service.getToolPath().isEmpty());
    }

    @Test
    public void planShouldCoverTheAreaWithARegularGrid() {
        ProbePlan plan = service.plan(settings());

        assertTrue(plan.problems().toString(), plan.isValid());
        ProbeArea area = plan.settings().area();
        assertTrue(plan.gridArea().minX() <= area.minX() && plan.gridArea().maxX() >= area.maxX());
        assertTrue(plan.gridArea().minY() <= area.minY() && plan.gridArea().maxY() >= area.maxY());
        assertEquals(0, plan.gridArea().width() % 5, 1e-9);
        assertEquals(1 + 4 * plan.points().size(), plan.operations().size());
    }

    @Test
    public void probingShouldBeRefusedWithoutAMachine() throws Exception {
        ProbePlan plan = service.plan(settings());

        assertEquals(List.of(PlanVerifier.ERROR_UNSUPPORTED + BackendMachineInspector.REASON_NOT_CONNECTED), service.findBlocks(plan));
        assertNothingWasSent();
    }

    @Test
    public void probingOnARealMachineShouldNotBeEnabled() throws Exception {
        connect();
        ProbePlan plan = service.plan(settings());

        List<String> blocks = service.findBlocks(plan);

        assertEquals(1, blocks.size());
        assertTrue(blocks.get(0), blocks.get(0).startsWith(PlanVerifier.ERROR_UNSUPPORTED + "GRBL was recognized, but probing on a real machine is not enabled"));
        assertFalse(service.getMachine().getCapabilities().isSupported());
        assertTrue(service.getMachine().getEnvelope().isEmpty());
        assertNothingWasSent();
    }

    @Test
    public void otherFirmwareShouldNotBeSupported() throws Exception {
        connect();
        backend.getSettings().setFirmwareVersion("TinyG");

        List<String> blocks = service.findBlocks(service.plan(settings()));

        assertEquals(List.of(PlanVerifier.ERROR_UNSUPPORTED + "there is no probing support for the firmware TinyG"), blocks);
    }

    @Test
    public void inspectorShouldRefuseEveryOperationWithoutSendingAnything() throws Exception {
        connect();
        List<OperationResult> results = new ArrayList<>();

        for (ProbeOperation.Kind kind : ProbeOperation.Kind.values()) {
            service.getMachine().execute(new ProbeOperation(kind, 0, 10, 10, 1, 50), results::add);
        }

        assertEquals(4, results.size());
        assertTrue(results.stream().allMatch(result -> result.outcome() == Outcome.ERROR && result.message().equals(BackendMachineInspector.ERROR_READ_ONLY)));
        assertNothingWasSent();
    }

    @Test
    public void inspectorShouldTellWhatIsKnownOfTheConnectedMachine() throws Exception {
        connect();

        var snapshot = service.getMachine().getSnapshot();

        assertTrue(snapshot.isConnected());
        assertEquals(Code.G54, snapshot.workCoordinateSystem());
        assertEquals(20, snapshot.workPosition().getX(), 0);
        assertEquals(10, snapshot.workPosition().getZ(), 0);
        assertFalse(service.getMachine().describe().isEmpty());
        assertNothingWasSent();
    }

    @Test
    public void simulationShouldCarryOutAllOfThePlanWithoutAMachine() throws Exception {
        ProbePlan plan = service.plan(settings());

        Simulation simulation = service.simulate(plan);

        assertTrue(simulation.failure(), simulation.isCompleted());
        assertNull(simulation.failure());
        assertEquals(plan.points().size(), simulation.measured());
        assertEquals(plan.operations().size(), simulation.operations().size());
        assertTrue(simulation.operations().get(0), simulation.operations().get(0).endsWith("Retract to Z5.000"));
        assertTrue(simulation.assumptions().contains(PcbProbingService.ASSUMPTION_LIMITS));
        assertTrue(simulation.assumptions().contains(PcbProbingService.ASSUMPTION_POSITION));
        assertTrue(simulation.assumptions().contains(PcbProbingService.ASSUMPTION_REFERENCE));
        assertNothingWasSent();
    }

    @Test
    public void simulationShouldStartFromWhereTheConnectedMachineIs() throws Exception {
        connect();
        ProbePlan plan = service.plan(settings());

        Simulation simulation = service.simulate(plan);

        assertTrue(simulation.failure(), simulation.isCompleted());
        assertFalse(simulation.assumptions().contains(PcbProbingService.ASSUMPTION_POSITION));
        assertNothingWasSent();
    }

    @Test
    public void simulationShouldTellWhyAPlanCanNotBeCarriedOut() {
        ProbeArea area = service.getAreaToMeasure(2).orElseThrow();
        // The board is expected lower than where the sensor has contact in the simulation
        ProbePlan plan = service.plan(new ProbeSettings(area, 5, -0.5, 3, List.of(), 2, 1, -2, 50, 300, 0, 1, "V-bit"));

        Simulation simulation = service.simulate(plan);

        assertFalse(simulation.isCompleted());
        assertTrue(simulation.failure(), simulation.failure().startsWith("The sensor had contact at Z0.000, above the highest that the board is expected to be"));
        assertEquals(0, simulation.measured());

        Simulation invalid = service.simulate(service.plan(new ProbeSettings(area, 0, 0.5, 3, List.of(), 2, 1, -1, 50, 300, 0, 1, "V-bit")));
        assertFalse(invalid.isCompleted());
        assertEquals(ProbeSettings.ERROR_STEP, invalid.failure());
    }
}
