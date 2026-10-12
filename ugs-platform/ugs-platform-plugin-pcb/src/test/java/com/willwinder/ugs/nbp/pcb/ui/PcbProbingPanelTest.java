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
package com.willwinder.ugs.nbp.pcb.ui;

import com.willwinder.ugs.nbp.pcb.geometry.RigidTransform2D;
import com.willwinder.ugs.nbp.pcb.probing.BackendMachineInspector;
import com.willwinder.ugs.nbp.pcb.probing.PlanVerifier;
import com.willwinder.ugs.nbp.pcb.probing.ProbePlan;
import com.willwinder.ugs.nbp.pcb.probing.ProbeSettings;
import com.willwinder.ugs.nbp.pcb.service.PcbAlignmentService;
import com.willwinder.ugs.nbp.pcb.service.PcbHeightMapService;
import com.willwinder.ugs.nbp.pcb.service.PcbProbingService;
import com.willwinder.universalgcodesender.gcode.util.Code;
import com.willwinder.universalgcodesender.model.GUIBackend;
import com.willwinder.universalgcodesender.services.BackendFileLoader;
import com.willwinder.universalgcodesender.utils.Settings;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PcbProbingPanelTest {
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

    private GUIBackend backend;
    private PcbProbingPanel panel;

    @Before
    public void setUp() throws Exception {
        backend = new GUIBackend();
        backend.applySettings(new Settings());
        PcbAlignmentService alignmentService = new PcbAlignmentService(backend, new BackendFileLoader(backend), e -> {
        });
        PcbProbingService service = new PcbProbingService(backend, new PcbHeightMapService(backend, alignmentService));

        File source = new File(folder.getRoot(), "board.nc");
        Files.write(source.toPath(), PROGRAM, StandardCharsets.UTF_8);
        backend.setGcodeFile(source);
        alignmentService.alignLoadedFile(new RigidTransform2D(Math.toRadians(30), 10, 5), Code.G54);

        SwingUtilities.invokeAndWait(() -> panel = new PcbProbingPanel(() -> Optional.of(service), Runnable::run));
    }

    private interface UserInterfaceAction {
        void run() throws Exception;
    }

    private void onUserInterface(UserInterfaceAction action) throws Exception {
        Exception[] failure = new Exception[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                action.run();
            } catch (Exception e) {
                failure[0] = e;
            }
        });
        if (failure[0] != null) {
            throw failure[0];
        }
        for (int i = 0; i < 4; i++) {
            SwingUtilities.invokeAndWait(() -> {
            });
        }
    }

    private void enterSettings() throws Exception {
        onUserInterface(() -> panel.setSettingsText("5", "2", "0.5", "3", "2", "1", "-1", "0", "50", "300", "1", "V-bit 30", ""));
    }

    @Test
    public void noSettingShouldHaveAValueUntilTheOperatorGivesIt() {
        for (JTextField field : panel.getSettingFields()) {
            assertEquals("", field.getText());
        }
        assertEquals(PcbProbingPanel.TEXT_NO_PLAN, panel.getStateLabel().getText());
        assertFalse(panel.getSimulateButton().isEnabled());
    }

    @Test
    public void startingAProbingShouldNotBePossible() throws Exception {
        assertFalse(panel.getStartButton().isEnabled());
        assertEquals(PcbProbingPanel.TEXT_EXECUTION_DISABLED, panel.getStartButton().getToolTipText());

        enterSettings();
        onUserInterface(() -> panel.plan());
        onUserInterface(() -> panel.simulate());

        assertFalse(panel.getStartButton().isEnabled());
    }

    @Test
    public void planShouldNotBeMadeWhileASettingIsMissing() throws Exception {
        onUserInterface(() -> panel.setSettingsText("5", "2", "0.5", "3", "", "1", "-1", "0", "50", "300", "1", "V-bit 30", ""));

        onUserInterface(() -> panel.plan());

        assertNull(panel.getPlan());
        assertEquals("The plan can not be made", panel.getStateLabel().getText());
        assertEquals(PcbProbingPanel.ERROR_NOT_A_NUMBER + "Clearance mm", panel.getBlocksArea().getText());
        assertFalse(panel.getSimulateButton().isEnabled());
    }

    @Test
    public void planShouldNotBeMadeFromSettingsThatAreNotSafe() throws Exception {
        onUserInterface(() -> panel.setSettingsText("5", "2", "0.5", "3", "2", "1", "0.5", "0", "50", "300", "1", "", ""));

        onUserInterface(() -> panel.plan());

        assertNull(panel.getPlan());
        assertEquals(ProbeSettings.ERROR_MIN_PROBE_Z + "\n" + ProbeSettings.ERROR_TOOL, panel.getBlocksArea().getText());
    }

    @Test
    public void planShouldBeShownWithItsPointsItsHeightsAndWhyItIsNotCarriedOut() throws Exception {
        enterSettings();

        onUserInterface(() -> panel.plan());

        ProbePlan plan = panel.getPlan();
        assertNotNull(plan);
        assertEquals(PcbProbingPanel.TEXT_PLAN_VALID, panel.getStateLabel().getText());
        String text = panel.getPlanArea().getText();
        assertTrue(text, text.startsWith(plan.points().size() + " points ("));
        assertTrue(text, text.contains("every 5 mm"));
        assertTrue(text, text.contains("Moved between points at Z5.000"));
        assertTrue(text, text.contains("Probing from Z1.500 down to at most Z-1.000"));
        assertTrue(panel.getBlocksArea().getText(), panel.getBlocksArea().getText().contains(PlanVerifier.ERROR_UNSUPPORTED + BackendMachineInspector.REASON_NOT_CONNECTED));
        assertTrue(panel.getSimulateButton().isEnabled());
        assertEquals("Machine: not connected", panel.getMachineArea().getText());
    }

    @Test
    public void obstaclesShouldRaiseTheTravelHeightAndBlockPointsOnThem() throws Exception {
        onUserInterface(() -> panel.setSettingsText("5", "2", "0.5", "3", "2", "1", "-1", "0", "50", "300", "1", "V-bit 30", "100, 100, 110, 110, 9"));
        onUserInterface(() -> panel.plan());
        assertTrue(panel.getPlanArea().getText(), panel.getPlanArea().getText().contains("Moved between points at Z11.000"));

        onUserInterface(() -> panel.setSettingsText("5", "2", "0.5", "3", "2", "1", "-1", "0", "50", "300", "1", "V-bit 30", "0, 0, 100, 100, 9"));
        onUserInterface(() -> panel.plan());
        assertNull(panel.getPlan());
        assertTrue(panel.getBlocksArea().getText(), panel.getBlocksArea().getText().startsWith(ProbePlan.ERROR_ON_OBSTACLE));

        onUserInterface(() -> panel.setSettingsText("5", "2", "0.5", "3", "2", "1", "-1", "0", "50", "300", "1", "V-bit 30", "1, 2, 3"));
        onUserInterface(() -> panel.plan());
        assertEquals(PcbProbingPanel.ERROR_OBSTACLES, panel.getBlocksArea().getText());
    }

    @Test
    public void simulationShouldShowEveryOperationAndWhatItTookForGranted() throws Exception {
        enterSettings();
        onUserInterface(() -> panel.plan());
        int points = panel.getPlan().points().size();

        onUserInterface(() -> panel.simulate());

        String[] lines = panel.getSequenceArea().getText().split("\n");
        assertEquals(1 + 4 * points, lines.length);
        assertTrue(lines[0], lines[0].endsWith("Retract to Z5.000"));
        assertTrue(lines[3], lines[3].endsWith("Probe down to at most Z-1.000 at F50"));
        assertEquals("Simulation completed: " + points + " points in " + lines.length + " operations", panel.getStateLabel().getText());
        assertEquals(points, panel.getProgressBar().getValue());
        assertEquals(points + " of " + points + " points", panel.getProgressBar().getString());
        assertTrue(panel.getBlocksArea().getText().contains(PcbProbingService.ASSUMPTION_LIMITS));
    }

    @Test
    public void simulationShouldShowWhereTheSequenceWouldEnd() throws Exception {
        // The board is expected lower than where the simulated sensor has contact
        onUserInterface(() -> panel.setSettingsText("5", "2", "-0.5", "3", "2", "1", "-2", "0", "50", "300", "1", "V-bit 30", ""));
        onUserInterface(() -> panel.plan());

        onUserInterface(() -> panel.simulate());

        assertTrue(panel.getStateLabel().getText(), panel.getStateLabel().getText().startsWith("Simulation failed: The sensor had contact at Z0.000"));
        assertEquals(0, panel.getProgressBar().getValue());
    }

    @Test
    public void previewShouldShowThePointsAndThePath() throws Exception {
        enterSettings();
        onUserInterface(() -> panel.plan());
        ProbePlanPreviewPanel preview = panel.getPreview();

        BufferedImage image = new BufferedImage(480, 360, BufferedImage.TYPE_INT_RGB);
        onUserInterface(() -> {
            preview.setSize(480, 360);
            preview.paint(image.getGraphics());
        });

        boolean hasPath = false;
        boolean hasFirstPoint = false;
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                int color = image.getRGB(x, y) & 0xFFFFFF;
                hasPath |= color == (ProbePlanPreviewPanel.PATH_COLOR.getRGB() & 0xFFFFFF);
                hasFirstPoint |= color == (ProbePlanPreviewPanel.FIRST_POINT_COLOR.getRGB() & 0xFFFFFF);
            }
        }
        assertTrue(hasPath);
        assertTrue(hasFirstPoint);
        assertTrue(preview.getWorldBounds().contains(panel.getPlan().gridArea().minX() + 0.1, panel.getPlan().gridArea().minY() + 0.1));
    }
}
