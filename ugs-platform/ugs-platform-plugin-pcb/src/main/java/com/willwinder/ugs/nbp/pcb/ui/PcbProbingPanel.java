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

import com.willwinder.ugs.nbp.pcb.heightmap.GridProbePlanner;
import com.willwinder.ugs.nbp.pcb.heightmap.ProbeArea;
import com.willwinder.ugs.nbp.pcb.probing.BackendMachineInspector;
import com.willwinder.ugs.nbp.pcb.probing.Obstacle;
import com.willwinder.ugs.nbp.pcb.probing.ProbePlan;
import com.willwinder.ugs.nbp.pcb.probing.ProbeSettings;
import com.willwinder.ugs.nbp.pcb.service.PcbProbingService;
import com.willwinder.ugs.nbp.pcb.service.PcbProbingService.Simulation;
import com.willwinder.universalgcodesender.listeners.UGSEventListener;
import com.willwinder.universalgcodesender.model.UGSEvent;
import com.willwinder.universalgcodesender.model.events.ControllerStateEvent;
import com.willwinder.universalgcodesender.model.events.FileStateEvent;
import net.miginfocom.swing.MigLayout;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.Color;
import java.awt.EventQueue;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Plans the probing of the board of the loaded program, shows why it can not be carried out on
 * the connected machine, and simulates all of its sequence.
 * <p>
 * No setting has a value until the operator gives it one: what is safe depends on the machine,
 * the sensor and how the board is held. Nothing here moves the machine, and the button that will
 * start a probing can not be pressed in this version.
 */
public class PcbProbingPanel extends JPanel implements UGSEventListener {
    public static final String ERROR_NO_SERVICE = "The PCB tools are not available, see the log of the application.";
    public static final String ERROR_NO_AREA = "The loaded program does not cut anything, so there is no area to measure. Load the aligned program of the board.";
    public static final String ERROR_NOT_A_NUMBER = "Give a number for: ";
    public static final String ERROR_OBSTACLES = "Give every obstacle as: min X, min Y, max X, max Y, top Z. Separate obstacles with a semicolon.";
    public static final String TEXT_NO_PLAN = "No plan. Give every setting and plan the probing.";
    public static final String TEXT_EXECUTION_DISABLED = "Probing on a real machine is not enabled in this version. It awaits the second safety review.";
    public static final String TEXT_PLAN_VALID = "The plan is complete. It can be simulated.";

    static final Color BLOCKED_COLOR = new Color(190, 60, 0);
    static final Color VALID_COLOR = new Color(0, 130, 40);

    private final transient Supplier<Optional<PcbProbingService>> serviceSupplier;
    private final transient Executor executor;

    private final JTextField stepField = new JTextField(6);
    private final JTextField marginField = new JTextField(6);
    private final JTextField maxBoardField = new JTextField(6);
    private final JTextField fixtureField = new JTextField(6);
    private final JTextField clearanceField = new JTextField(6);
    private final JTextField probeStartField = new JTextField(6);
    private final JTextField minProbeField = new JTextField(6);
    private final JTextField probeFeedField = new JTextField(6);
    private final JTextField approachFeedField = new JTextField(6);
    private final JTextField touchPlateField = new JTextField(6);
    private final JTextField limitMarginField = new JTextField(6);
    private final JTextField toolField = new JTextField(12);
    private final JTextField obstaclesField = new JTextField(12);
    private final JButton planButton = new JButton("Plan");
    private final JButton simulateButton = new JButton("Simulate sequence");
    private final JButton startButton = new JButton("Start probing");
    private final JLabel stateLabel = new JLabel(TEXT_NO_PLAN);
    private final JTextArea planArea = createTextArea(4);
    private final JTextArea machineArea = createTextArea(4);
    private final JTextArea blocksArea = createTextArea(5);
    private final JTextArea sequenceArea = createTextArea(8);
    private final JProgressBar progressBar = new JProgressBar();
    private final ProbePlanPreviewPanel preview = new ProbePlanPreviewPanel();

    private transient ProbePlan plan;
    private boolean isBusy;

    public PcbProbingPanel(Supplier<Optional<PcbProbingService>> serviceSupplier, Executor executor) {
        this.serviceSupplier = serviceSupplier;
        this.executor = executor;
        initComponents();
        showMachine();
        showToolPath();
        updateButtons();
    }

    private static JTextArea createTextArea(int rows) {
        JTextArea result = new JTextArea(rows, 20);
        result.setEditable(false);
        result.setLineWrap(true);
        result.setWrapStyleWord(true);
        result.setFont(new JLabel().getFont());
        return result;
    }

    private void initComponents() {
        stateLabel.setFont(stateLabel.getFont().deriveFont(Font.BOLD));
        sequenceArea.setLineWrap(false);
        sequenceArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, sequenceArea.getFont().getSize()));
        progressBar.setStringPainted(true);
        progressBar.setString("");
        startButton.setEnabled(false);
        startButton.setToolTipText(TEXT_EXECUTION_DISABLED);

        JPanel settings = new JPanel(new MigLayout("insets 0, wrap 2, fillx", "[][grow, fill]"));
        addSetting(settings, "Step mm", stepField, "The distance between the points");
        addSetting(settings, "Margin mm", marginField, "How far around the cuts of the program the board is measured");
        addSetting(settings, "Board max Z", maxBoardField, "The highest that the surface of the board is expected to be");
        addSetting(settings, "Fixtures top Z", fixtureField, "The highest point of what holds the board");
        addSetting(settings, "Clearance mm", clearanceField, "How far above the board, the fixtures and the obstacles the tool is moved");
        addSetting(settings, "Probe start mm", probeStartField, "How far above the highest expected surface the probing move starts");
        addSetting(settings, "Lowest probe Z", minProbeField, "The lowest that the tool is moved while probing");
        addSetting(settings, "Touch plate mm", touchPlateField, "The thickness of what is between the tool and the board, zero if nothing");
        addSetting(settings, "Probe feed", probeFeedField, "The feed rate of the probing move, in millimeters a minute");
        addSetting(settings, "Approach feed", approachFeedField, "The feed rate of the other moves in Z, in millimeters a minute");
        addSetting(settings, "Limit margin mm", limitMarginField, "How far the tool is kept from the limits of the machine");
        addSetting(settings, "Tool", toolField, "Which tool or probe the settings are for");
        settings.add(new JLabel("Obstacles"));
        obstaclesField.setToolTipText("min X, min Y, max X, max Y, top Z; for every obstacle");
        settings.add(obstaclesField, "wmin 30");

        JPanel actions = new JPanel(new MigLayout("insets 0, wrap 1, fillx", "[grow, fill]"));
        actions.add(planButton, "wmin 0");
        actions.add(simulateButton, "wmin 0");
        actions.add(startButton, "wmin 0");
        JTextArea disabledArea = createTextArea(2);
        disabledArea.setText(TEXT_EXECUTION_DISABLED);
        disabledArea.setOpaque(false);
        machineArea.setOpaque(false);

        // The window is usually low, so the settings are scrolled instead of cut off
        JPanel left = new PcbHeightMapPanel.WidthTrackingPanel(new MigLayout("insets 0, wrap 1, fillx", "[grow, fill]"));
        left.add(settings, "wmin 0");
        left.add(actions, "wmin 0");
        left.add(disabledArea, "wmin 0");
        left.add(new JLabel("Machine"), "gaptop 4");
        left.add(machineArea, "wmin 0");
        JScrollPane leftScroll = new JScrollPane(left, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        leftScroll.setBorder(null);
        leftScroll.getVerticalScrollBar().setUnitIncrement(12);

        JPanel middle = new JPanel(new MigLayout("insets 0, wrap 1, fill", "[grow, fill]", "[][][][grow, fill][]"));
        middle.add(stateLabel, "wmin 0");
        middle.add(planArea, "wmin 0");
        middle.add(new JScrollPane(blocksArea), "wmin 0, hmin 40");
        middle.add(new JScrollPane(sequenceArea), "wmin 0, hmin 40");
        middle.add(progressBar, "wmin 0");

        setLayout(new MigLayout("fill, insets 6", "[30%!, fill][38%!, fill][grow, fill]", "[grow, fill]"));
        add(leftScroll, "wmin 0, hmin 0");
        add(middle, "wmin 0, hmin 0");
        add(preview, "grow, wmin 0, hmin 0");

        planButton.addActionListener(event -> plan());
        simulateButton.addActionListener(event -> simulate());
    }

    private static void addSetting(JPanel panel, String name, JTextField field, String toolTip) {
        JLabel label = new JLabel(name);
        label.setToolTipText(toolTip);
        field.setToolTipText(toolTip);
        panel.add(label);
        panel.add(field, "wmin 30");
    }

    @Override
    public void UGSEvent(UGSEvent event) {
        if (event instanceof ControllerStateEvent || event instanceof FileStateEvent) {
            EventQueue.invokeLater(() -> {
                showMachine();
                if (plan == null) {
                    showToolPath();
                }
                showBlocks(List.of());
            });
        }
    }

    private void showToolPath() {
        preview.setData(null, serviceSupplier.get().map(PcbProbingService::getToolPath).orElse(List.of()));
    }

    private void showMachine() {
        Optional<PcbProbingService> service = serviceSupplier.get();
        machineArea.setText(service.map(PcbProbingService::getMachine).map(BackendMachineInspector::describe).map(lines -> String.join("\n", lines)).orElse(ERROR_NO_SERVICE));
    }

    private double number(JTextField field, String name) {
        try {
            double result = Double.parseDouble(field.getText().trim().replace(',', '.'));
            if (!Double.isFinite(result)) {
                throw new NumberFormatException();
            }
            return result;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(ERROR_NOT_A_NUMBER + name);
        }
    }

    private List<Obstacle> readObstacles() {
        List<Obstacle> result = new ArrayList<>();
        for (String text : obstaclesField.getText().split(";")) {
            if (text.isBlank()) {
                continue;
            }
            String[] values = text.trim().split("[\\s,]+");
            try {
                if (values.length != 5) {
                    throw new NumberFormatException();
                }
                double minX = Double.parseDouble(values[0]);
                double minY = Double.parseDouble(values[1]);
                double maxX = Double.parseDouble(values[2]);
                double maxY = Double.parseDouble(values[3]);
                result.add(new Obstacle(new ProbeArea(Math.min(minX, maxX), Math.min(minY, maxY), Math.max(minX, maxX), Math.max(minY, maxY)), Double.parseDouble(values[4])));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(ERROR_OBSTACLES);
            }
        }
        return result;
    }

    /**
     * Makes the plan from the settings and shows it, with what keeps it from being carried out
     */
    public void plan() {
        plan = null;
        sequenceArea.setText("");
        progressBar.setValue(0);
        progressBar.setString("");

        Optional<PcbProbingService> service = serviceSupplier.get();
        if (service.isEmpty()) {
            showProblem(ERROR_NO_SERVICE);
            return;
        }

        ProbeSettings settings;
        try {
            double margin = number(marginField, "Margin mm");
            Optional<ProbeArea> area = service.get().getAreaToMeasure(margin);
            if (area.isEmpty()) {
                showProblem(ERROR_NO_AREA);
                return;
            }
            settings = new ProbeSettings(area.get(),
                    number(stepField, "Step mm"),
                    number(maxBoardField, "Board max Z"),
                    number(fixtureField, "Fixtures top Z"),
                    readObstacles(),
                    number(clearanceField, "Clearance mm"),
                    number(probeStartField, "Probe start mm"),
                    number(minProbeField, "Lowest probe Z"),
                    number(probeFeedField, "Probe feed"),
                    number(approachFeedField, "Approach feed"),
                    number(touchPlateField, "Touch plate mm"),
                    number(limitMarginField, "Limit margin mm"),
                    toolField.getText().trim());
        } catch (IllegalArgumentException e) {
            showProblem(e.getMessage());
            return;
        }

        ProbePlan newPlan = service.get().plan(settings);
        preview.setData(newPlan, service.get().getToolPath());
        if (!newPlan.isValid()) {
            planArea.setText("");
            stateLabel.setText("The plan can not be made");
            stateLabel.setForeground(BLOCKED_COLOR);
            blocksArea.setText(String.join("\n", newPlan.problems()));
            updateButtons();
            return;
        }

        plan = newPlan;
        GridProbePlanner planner = new GridProbePlanner(settings.stepMM());
        ProbeArea grid = newPlan.gridArea();
        planArea.setText(String.format(Locale.ROOT,
                "%d points (%d x %d) every %s mm over X %.3f to %.3f, Y %.3f to %.3f.%n"
                        + "Moved between points at Z%.3f, %.0f mm in all. Probing from Z%.3f down to at most Z%.3f.%n"
                        + "%d operations: for every point move, approach, probe and retract.",
                newPlan.points().size(), planner.getPointCount(settings.area().width()), planner.getPointCount(settings.area().height()),
                AlignmentTableModel.format(settings.stepMM()), grid.minX(), grid.maxX(), grid.minY(), grid.maxY(),
                settings.travelZ(), newPlan.getTravelLength(), settings.probeStartZ(), settings.minProbeZ(), newPlan.operations().size()));
        stateLabel.setText(TEXT_PLAN_VALID);
        stateLabel.setForeground(VALID_COLOR);
        showBlocks(List.of());
        updateButtons();
    }

    private void showProblem(String message) {
        Optional<PcbProbingService> service = serviceSupplier.get();
        preview.setData(null, service.map(PcbProbingService::getToolPath).orElse(List.of()));
        planArea.setText("");
        stateLabel.setText("The plan can not be made");
        stateLabel.setForeground(BLOCKED_COLOR);
        blocksArea.setText(message);
        updateButtons();
    }

    /**
     * Shows why the plan can not be carried out on the connected machine, which is asked again
     * whenever the machine tells that something has changed
     */
    private void showBlocks(List<String> assumptions) {
        Optional<PcbProbingService> service = serviceSupplier.get();
        if (plan == null || service.isEmpty()) {
            return;
        }
        List<String> lines = new ArrayList<>();
        lines.add("Not carried out on the machine because:");
        service.get().findBlocks(plan).forEach(block -> lines.add("- " + block));
        if (!assumptions.isEmpty()) {
            lines.add("");
            lines.add("The simulation took for granted:");
            assumptions.forEach(assumption -> lines.add("- " + assumption));
        }
        blocksArea.setText(String.join("\n", lines));
        blocksArea.setCaretPosition(0);
    }

    /**
     * Carries out all of the plan on a machine that only exists in memory and shows every operation
     */
    public void simulate() {
        Optional<PcbProbingService> service = serviceSupplier.get();
        if (plan == null || service.isEmpty() || isBusy) {
            return;
        }

        ProbePlan simulated = plan;
        isBusy = true;
        updateButtons();
        stateLabel.setText("Simulating...");
        executor.execute(() -> {
            Simulation simulation = service.get().simulate(simulated);
            EventQueue.invokeLater(() -> {
                isBusy = false;
                if (simulated == plan) {
                    show(simulation);
                }
                updateButtons();
            });
        });
    }

    private void show(Simulation simulation) {
        sequenceArea.setText(String.join("\n", simulation.operations()));
        sequenceArea.setCaretPosition(0);
        progressBar.setMaximum(Math.max(1, plan.points().size()));
        progressBar.setValue(simulation.measured());
        progressBar.setString(simulation.measured() + " of " + plan.points().size() + " points");
        if (simulation.isCompleted()) {
            stateLabel.setText("Simulation completed: " + simulation.measured() + " points in " + simulation.operations().size() + " operations");
            stateLabel.setForeground(VALID_COLOR);
        } else {
            stateLabel.setText("Simulation failed: " + simulation.failure());
            stateLabel.setForeground(BLOCKED_COLOR);
        }
        showBlocks(simulation.assumptions());
    }

    private void updateButtons() {
        // The state may be longer than there is room for
        stateLabel.setToolTipText(stateLabel.getText());
        planButton.setEnabled(!isBusy);
        simulateButton.setEnabled(!isBusy && plan != null);
        // Probing on a real machine is not enabled
        startButton.setEnabled(false);
    }

    void setSettingsText(String step, String margin, String maxBoard, String fixture, String clearance, String probeStart,
                         String minProbe, String touchPlate, String probeFeed, String approachFeed, String limitMargin, String tool, String obstacles) {
        stepField.setText(step);
        marginField.setText(margin);
        maxBoardField.setText(maxBoard);
        fixtureField.setText(fixture);
        clearanceField.setText(clearance);
        probeStartField.setText(probeStart);
        minProbeField.setText(minProbe);
        touchPlateField.setText(touchPlate);
        probeFeedField.setText(probeFeed);
        approachFeedField.setText(approachFeed);
        limitMarginField.setText(limitMargin);
        toolField.setText(tool);
        obstaclesField.setText(obstacles);
    }

    List<JTextField> getSettingFields() {
        return List.of(stepField, marginField, maxBoardField, fixtureField, clearanceField, probeStartField, minProbeField,
                touchPlateField, probeFeedField, approachFeedField, limitMarginField, toolField, obstaclesField);
    }

    ProbePlan getPlan() {
        return plan;
    }

    JLabel getStateLabel() {
        return stateLabel;
    }

    JTextArea getPlanArea() {
        return planArea;
    }

    JTextArea getMachineArea() {
        return machineArea;
    }

    JTextArea getBlocksArea() {
        return blocksArea;
    }

    JTextArea getSequenceArea() {
        return sequenceArea;
    }

    JProgressBar getProgressBar() {
        return progressBar;
    }

    JButton getSimulateButton() {
        return simulateButton;
    }

    JButton getStartButton() {
        return startButton;
    }

    ProbePlanPreviewPanel getPreview() {
        return preview;
    }
}
