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

import com.willwinder.ugs.nbp.pcb.gcode.AlignmentException;
import com.willwinder.ugs.nbp.pcb.gcode.AlignmentMetadata;
import com.willwinder.ugs.nbp.pcb.gcode.ToolPathValidator;
import com.willwinder.ugs.nbp.pcb.geometry.AlignmentResult;
import com.willwinder.ugs.nbp.pcb.geometry.Point2D;
import com.willwinder.ugs.nbp.pcb.geometry.RigidTransform2D;
import com.willwinder.ugs.nbp.pcb.service.PcbAlignmentService;
import com.willwinder.ugs.nbp.pcb.service.PcbAlignmentService.AlignmentPlan;
import com.willwinder.ugs.nbp.pcb.service.PcbAlignmentService.ValidationState;
import com.willwinder.ugs.nbp.pcb.ui.AlignmentModel.Criteria;
import com.willwinder.ugs.nbp.pcb.ui.AlignmentModel.Evaluation;
import com.willwinder.ugs.nbp.pcb.ui.PositionCapture.CaptureException;
import com.willwinder.ugs.nbp.pcb.ui.PositionCapture.CapturedPosition;
import com.willwinder.universalgcodesender.gcode.util.Code;
import com.willwinder.universalgcodesender.gcode.util.GcodeParserException;
import com.willwinder.universalgcodesender.listeners.UGSEventListener;
import com.willwinder.universalgcodesender.model.BackendAPI;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UGSEvent;
import com.willwinder.universalgcodesender.model.events.ControllerStateEvent;
import com.willwinder.universalgcodesender.model.events.ControllerStatusEvent;
import com.willwinder.universalgcodesender.model.events.FileStateEvent;
import net.miginfocom.swing.MigLayout;

import javax.swing.AbstractCellEditor;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;
import java.awt.Color;
import java.awt.Component;
import java.awt.EventQueue;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EventObject;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The window for aligning the loaded program to where a PCB is on the machine: the reference
 * points are entered or captured, the alignment is calculated and shown, and when it is good
 * enough it can be applied and the resulting program validated.
 * <p>
 * Nothing here moves the machine. Positions are read from the backend, and applying the alignment
 * writes a new program file and loads it.
 */
public class PcbAlignmentPanel extends JPanel implements UGSEventListener {
    public static final String NO_PROGRAM = "No program loaded";
    public static final String NOT_CONNECTED = "Machine: not connected";
    public static final String ERROR_NO_SERVICE = "The PCB alignment service is not available, so the alignment can not be applied.";
    public static final String ERROR_NO_PROGRAM = "Load the program to align first.";
    public static final String ERROR_NOT_VALID = "The alignment can not be applied: ";
    public static final String ERROR_SENDING = "The alignment can not be applied while a program is being sent.";
    public static final String ERROR_CRITERIA = "The acceptance criteria and the accepted change of Z must be numbers that are not negative.";

    private static final Color VALID_COLOR = new Color(0, 130, 60);
    private static final Color INVALID_COLOR = new Color(190, 40, 30);
    private static final Color PENDING_COLOR = new Color(190, 120, 0);
    private static final int MAX_PREVIEW_POINTS = 20000;
    private static final Code[] WORK_COORDINATE_SYSTEMS = {Code.G54, Code.G55, Code.G56, Code.G57, Code.G58, Code.G59, Code.G59_1, Code.G59_2, Code.G59_3};

    private final transient BackendAPI backend;
    private final transient Supplier<Optional<PcbAlignmentService>> alignmentService;
    private final transient Executor backgroundExecutor;
    private final transient Predicate<String> confirmation;
    private final transient Consumer<String> errorReporter;
    private final transient PositionCapture positionCapture;
    private final transient AlignmentModel model;

    private final AlignmentTableModel tableModel;
    private final JTable table;
    private final AlignmentPreviewPanel preview = new AlignmentPreviewPanel();
    private final JLabel programLabel = new JLabel(NO_PROGRAM);
    private final JLabel validationLabel = new JLabel(" ");
    private final JLabel machineLabel = new JLabel(NOT_CONNECTED);
    private final JLabel rotationLabel = new JLabel("-");
    private final JLabel translationLabel = new JLabel("-");
    private final JLabel maxResidualLabel = new JLabel("-");
    private final JLabel rmsResidualLabel = new JLabel("-");
    private final JLabel scaleErrorLabel = new JLabel("-");
    private final JLabel statusLabel = new JLabel(" ");
    private final JComboBox<Code> workCoordinateSystem = new JComboBox<>(WORK_COORDINATE_SYSTEMS);
    private final JTextField maxResidualField = new JTextField(6);
    private final JTextField maxScaleErrorField = new JTextField(6);
    private final JTextField minPointDistanceField = new JTextField(6);
    private final JTextField maxZChangeField = new JTextField("0", 6);
    private final JButton addPointButton = new JButton("Add point");
    private final JButton removePointButton = new JButton("Remove point");
    private final JButton clearMeasuredButton = new JButton("Clear measured");
    private final JButton previewButton = new JButton("Preview");
    private final JButton applyButton = new JButton("Apply alignment");
    private final JButton validateButton = new JButton("Validate program");

    private final transient Runnable modelListener = this::refreshAlignment;
    private boolean isBusy;
    private boolean isUpdatingControls;

    /**
     * Counts the times that the loaded program has been described. What is found out about the program
     * in the background is only shown if the program has not been described again meanwhile, so that
     * something about a program that is no longer loaded never replaces what is shown about the new one.
     */
    private int programGeneration;
    private int previewGeneration;

    /**
     * @param model              the reference points, which are kept by the window while it is closed
     * @param alignmentService   gives the alignment service that is registered when the application starts
     * @param backgroundExecutor runs what takes time, so that the user interface is not blocked
     * @param confirmation       asks the operator to confirm applying the alignment that is described
     * @param errorReporter      shows a message about something that could not be done
     */
    public PcbAlignmentPanel(BackendAPI backend, AlignmentModel model, Supplier<Optional<PcbAlignmentService>> alignmentService,
                             Executor backgroundExecutor, Predicate<String> confirmation, Consumer<String> errorReporter) {
        this.backend = backend;
        this.model = model;
        this.tableModel = new AlignmentTableModel(model);
        this.table = new JTable(tableModel);
        this.alignmentService = alignmentService;
        this.backgroundExecutor = backgroundExecutor;
        this.confirmation = confirmation;
        this.errorReporter = errorReporter;
        this.positionCapture = new PositionCapture(backend, backend::getControllerState);

        initComponents();
        initListeners();
        refreshAlignment();
        refreshProgram();
    }

    private void initComponents() {
        // The criteria are kept with the reference points while the window is closed
        maxResidualField.setText(format(model.getCriteria().maxResidualMM()));
        maxScaleErrorField.setText(format(model.getCriteria().maxScaleError() * 100));
        minPointDistanceField.setText(format(model.getCriteria().minPointDistanceMM()));

        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        for (int column = AlignmentTableModel.COLUMN_NOMINAL_X; column <= AlignmentTableModel.COLUMN_MEASURED_Y; column++) {
            table.getColumnModel().getColumn(column).setCellEditor(new CoordinateEditor());
        }
        table.setRowSelectionAllowed(true);
        table.getColumnModel().getColumn(AlignmentTableModel.COLUMN_NUMBER).setMaxWidth(30);
        table.getColumnModel().getColumn(AlignmentTableModel.COLUMN_CAPTURE).setCellRenderer(new CaptureButtonRenderer());
        table.getColumnModel().getColumn(AlignmentTableModel.COLUMN_CAPTURE).setCellEditor(new CaptureButtonEditor());
        table.setRowHeight(24);

        JPanel points = new JPanel(new MigLayout("fill, insets 0", "[grow]", "[][grow][]"));
        points.add(new JLabel("Reference points in millimeters. Measured positions are work coordinates in"), "split 2");
        points.add(workCoordinateSystem, "wrap");
        points.add(new JScrollPane(table), "grow, hmin 90, wrap");
        points.add(addPointButton, "split 4");
        points.add(removePointButton);
        points.add(clearMeasuredButton);
        points.add(machineLabel, "gapleft 16");

        JPanel results = new JPanel(new MigLayout("insets 0, wrap 2", "[][grow]"));
        results.add(new JLabel("Rotation:"));
        results.add(rotationLabel);
        results.add(new JLabel("Translation:"));
        results.add(translationLabel);
        results.add(new JLabel("Largest residual:"));
        results.add(maxResidualLabel);
        results.add(new JLabel("RMS residual:"));
        results.add(rmsResidualLabel);
        results.add(new JLabel("Scale error:"));
        results.add(scaleErrorLabel);
        results.add(new JLabel("Accept residual up to (mm):"), "gaptop 8");
        results.add(maxResidualField);
        results.add(new JLabel("Accept scale error up to (%):"));
        results.add(maxScaleErrorField);
        results.add(new JLabel("Points at least apart (mm):"));
        results.add(minPointDistanceField);
        results.add(new JLabel("Accept Z changed up to (mm):"));
        results.add(maxZChangeField);

        JPanel actions = new JPanel(new MigLayout("insets 0"));
        actions.add(previewButton);
        actions.add(applyButton);
        actions.add(validateButton);

        setLayout(new MigLayout("fill", "[grow 60][][grow 40]", "[][grow][][]"));
        add(new JLabel("Program:"), "split 2, span 3");
        add(programLabel, "growx, wrap");
        add(points, "grow");
        add(results, "top");
        add(preview, "grow, wrap");
        add(statusLabel, "span 3, growx, wrap");
        add(actions, "split 2, span 3");
        add(validationLabel, "growx, gapleft 16");
    }

    /**
     * Stops showing the reference points, which are kept while the window is closed
     */
    public void dispose() {
        model.removeListener(modelListener);
        tableModel.dispose();
    }

    private void initListeners() {
        model.addListener(modelListener);
        addPointButton.addActionListener(event -> model.addPoint());
        removePointButton.addActionListener(event -> removeSelectedPoint());
        clearMeasuredButton.addActionListener(event -> model.clearMeasured());
        workCoordinateSystem.addActionListener(event -> changeWorkCoordinateSystem());
        previewButton.addActionListener(event -> loadPreview());
        applyButton.addActionListener(event -> applyAlignment());
        validateButton.addActionListener(event -> validateProgram());

        for (JTextField field : List.of(maxResidualField, maxScaleErrorField, minPointDistanceField)) {
            field.addActionListener(event -> updateCriteria());
            field.addFocusListener(new java.awt.event.FocusAdapter() {
                @Override
                public void focusLost(java.awt.event.FocusEvent event) {
                    updateCriteria();
                }
            });
        }
    }

    AlignmentModel getModel() {
        return model;
    }

    // The reference points

    private void removeSelectedPoint() {
        stopEditing();
        int row = table.getSelectedRow() >= 0 ? table.getSelectedRow() : model.getPoints().size() - 1;
        try {
            model.removePoint(row);
        } catch (IllegalStateException e) {
            errorReporter.accept(e.getMessage());
        }
    }

    private void changeWorkCoordinateSystem() {
        if (isUpdatingControls) {
            return;
        }

        try {
            model.setWorkCoordinateSystem((Code) workCoordinateSystem.getSelectedItem());
        } catch (IllegalStateException | IllegalArgumentException e) {
            errorReporter.accept(e.getMessage());
            refreshAlignment();
        }
    }

    /**
     * Sets the measured position of a reference point to where the machine is. This only reads the position.
     */
    void capturePoint(int row) {
        stopEditing();
        try {
            CapturedPosition position = positionCapture.capture();
            model.setCaptured(row, position.x(), position.y(), position.workCoordinateSystem());
        } catch (CaptureException | IllegalArgumentException e) {
            errorReporter.accept(e.getMessage());
        }
    }

    private void stopEditing() {
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
    }

    void updateCriteria() {
        Double maxResidual = AlignmentTableModel.parse(maxResidualField.getText());
        Double maxScaleError = AlignmentTableModel.parse(maxScaleErrorField.getText());
        Double minPointDistance = AlignmentTableModel.parse(minPointDistanceField.getText());
        Criteria criteria = new Criteria(
                maxResidual == null ? Double.NaN : maxResidual,
                maxScaleError == null ? Double.NaN : maxScaleError / 100,
                minPointDistance == null ? Double.NaN : minPointDistance);
        if (!criteria.equals(model.getCriteria())) {
            model.setCriteria(criteria);
        }
    }

    private void refreshAlignment() {
        Evaluation evaluation = model.evaluate();
        Optional<AlignmentResult> result = evaluation.result();

        rotationLabel.setText(result.map(r -> format(Math.toDegrees(r.transform().rotation())) + "°").orElse("-"));
        translationLabel.setText(result.map(r -> "X" + format(r.transform().translationX()) + " Y" + format(r.transform().translationY()) + " mm").orElse("-"));
        maxResidualLabel.setText(result.map(r -> format(r.maxResidual()) + " mm").orElse("-"));
        rmsResidualLabel.setText(result.map(r -> format(r.rmsResidual()) + " mm").orElse("-"));
        scaleErrorLabel.setText(result.map(r -> format(r.scaleError() * 100) + " %").orElse("-"));

        statusLabel.setText(evaluation.status().getDescription());
        statusLabel.setForeground(evaluation.isValid() ? VALID_COLOR : INVALID_COLOR);

        isUpdatingControls = true;
        try {
            workCoordinateSystem.setSelectedItem(model.getWorkCoordinateSystem());
        } finally {
            isUpdatingControls = false;
        }

        preview.setAlignment(model.getPoints(), result.map(AlignmentResult::transform).orElse(null));
        refreshActions();
    }

    // The loaded program

    @Override
    public void UGSEvent(UGSEvent event) {
        if (event instanceof FileStateEvent) {
            runOnUserInterface(() -> {
                previewGeneration++;
                preview.setProgramPath(List.of());
                refreshProgram();
            });
        } else if (event instanceof ControllerStateEvent) {
            runOnUserInterface(this::refreshActions);
        } else if (event instanceof ControllerStatusEvent) {
            runOnUserInterface(this::refreshMachine);
        }
    }

    /**
     * Describes the loaded program. The program is read to find out if it is aligned, which is not
     * done on the thread of the user interface.
     */
    void refreshProgram() {
        refreshMachine();
        int generation = ++programGeneration;
        File file = backend.getGcodeFile();
        Optional<PcbAlignmentService> service = alignmentService.get();
        backgroundExecutor.execute(() -> {
            String description = describeProgram(file);
            boolean isAligned = isAligned(file);
            runOnUserInterface(() -> {
                if (generation != programGeneration) {
                    return;
                }

                // The state is read last, so that it is the state of the program as it is now
                ValidationState state = service.map(PcbAlignmentService::getValidationState).orElse(ValidationState.NOT_VALIDATED);
                programLabel.setText(description);
                showValidation(file, isAligned, state);
                refreshActions();
            });
        });
    }

    private static boolean isAligned(File file) {
        try {
            return file != null && AlignmentMetadata.hasAlignmentHeader(file);
        } catch (IOException e) {
            return false;
        }
    }

    private static String describeProgram(File file) {
        if (file == null) {
            return NO_PROGRAM;
        }

        try {
            if (!AlignmentMetadata.hasAlignmentHeader(file)) {
                return file.getName() + " (original program)";
            }

            Optional<AlignmentMetadata> metadata = AlignmentMetadata.read(file);
            if (metadata.isEmpty()) {
                return file.getName() + " (aligned program that has been changed, it can not be sent)";
            }
            RigidTransform2D transform = metadata.get().transform();
            return file.getName() + " (aligned from " + metadata.get().sourceName() + " in " + metadata.get().workCoordinateSystem()
                    + ": " + format(Math.toDegrees(transform.rotation())) + "°, X" + format(transform.translationX()) + " Y" + format(transform.translationY()) + ")";
        } catch (IOException e) {
            return file.getName() + " (can not be read)";
        }
    }

    private void showValidation(File file, boolean isAligned, ValidationState state) {
        if (file == null) {
            validationLabel.setText(" ");
        } else if (!isAligned) {
            validationLabel.setText("This program is not aligned. It is sent as any other program.");
            validationLabel.setForeground(Color.DARK_GRAY);
        } else if (state == ValidationState.VALID) {
            validationLabel.setText("VALIDATED: the aligned program can be sent.");
            validationLabel.setForeground(VALID_COLOR);
        } else if (state == ValidationState.INVALID) {
            validationLabel.setText("INVALID: the aligned program can not be sent.");
            validationLabel.setForeground(INVALID_COLOR);
        } else if (state == ValidationState.PENDING) {
            validationLabel.setText("The aligned program is being loaded and validated.");
            validationLabel.setForeground(PENDING_COLOR);
        } else {
            validationLabel.setText("NOT VALIDATED: validate the aligned program before it can be sent.");
            validationLabel.setForeground(PENDING_COLOR);
        }
    }

    private void refreshMachine() {
        if (!backend.isConnected()) {
            machineLabel.setText(NOT_CONNECTED);
            return;
        }

        Position position = backend.getWorkPosition();
        Code machineWorkCoordinateSystem = backend.getGcodeState() == null ? null : backend.getGcodeState().offset;
        if (position == null) {
            machineLabel.setText("Machine: position unknown");
        } else {
            Position millimeters = position.getPositionIn(com.willwinder.universalgcodesender.model.UnitUtils.Units.MM);
            machineLabel.setText("Machine: X" + format(millimeters.getX()) + " Y" + format(millimeters.getY())
                    + " (" + (machineWorkCoordinateSystem == null ? "unknown" : machineWorkCoordinateSystem) + ")");
        }
    }

    private void refreshActions() {
        boolean hasProgram = backend.getGcodeFile() != null;
        boolean canChange = !isBusy && !backend.isSendingFile();
        previewButton.setEnabled(hasProgram && !isBusy);
        applyButton.setEnabled(hasProgram && canChange && model.evaluate().isValid());
        validateButton.setEnabled(hasProgram && canChange);
        removePointButton.setEnabled(model.getPoints().size() > AlignmentModel.MIN_POINTS);
    }

    private void setBusy(boolean busy) {
        isBusy = busy;
        refreshActions();
    }

    // The actions

    /**
     * Shows the original program in the preview, together with how the alignment moves it
     */
    void loadPreview() {
        File file = backend.getGcodeFile();
        if (file == null) {
            errorReporter.accept(ERROR_NO_PROGRAM);
            return;
        }

        Optional<PcbAlignmentService> service = alignmentService.get();
        int generation = ++previewGeneration;
        setBusy(true);
        backgroundExecutor.execute(() -> {
            try {
                File original = service.isPresent() ? service.get().findOriginalFile(file) : file;
                List<Position> positions = ToolPathValidator.trace(Files.readAllLines(original.toPath(), StandardCharsets.UTF_8));
                List<Point2D> path = new ArrayList<>();
                int step = Math.max(1, positions.size() / MAX_PREVIEW_POINTS);
                for (int i = 0; i < positions.size(); i += step) {
                    path.add(new Point2D(positions.get(i).getX(), positions.get(i).getY()));
                }
                runOnUserInterface(() -> {
                    // Another program may have been loaded while this one was read
                    if (generation == previewGeneration) {
                        preview.setProgramPath(path);
                    }
                });
            } catch (IOException | GcodeParserException | AlignmentException | RuntimeException e) {
                runOnUserInterface(() -> {
                    if (generation == previewGeneration) {
                        errorReporter.accept("The program could not be previewed: " + e.getMessage());
                    }
                });
            } finally {
                runOnUserInterface(() -> setBusy(false));
            }
        });
    }

    /**
     * Applies the alignment to the original of the loaded program, after the operator has confirmed
     * it. The aligned program is written to a file of its own and loaded, nothing is sent to the machine.
     */
    void applyAlignment() {
        stopEditing();
        updateCriteria();
        Evaluation evaluation = model.evaluate();
        if (!evaluation.isValid() || evaluation.result().isEmpty()) {
            errorReporter.accept(ERROR_NOT_VALID + evaluation.status().getDescription());
            return;
        }

        Optional<PcbAlignmentService> service = alignmentService.get();
        File file = backend.getGcodeFile();
        if (service.isEmpty() || file == null || backend.isSendingFile()) {
            errorReporter.accept(service.isEmpty() ? ERROR_NO_SERVICE : file == null ? ERROR_NO_PROGRAM : ERROR_SENDING);
            return;
        }

        AlignmentResult result = evaluation.result().get();
        Code measuredIn = model.getWorkCoordinateSystem();
        setBusy(true);
        backgroundExecutor.execute(() -> {
            try {
                // What is confirmed is a plan, and applying the plan fails if another program has been
                // loaded or the original has been changed while the confirmation was open
                AlignmentPlan plan = service.get().plan(result.transform(), measuredIn);
                if (!confirm(describeAlignment(plan.source(), plan.target(), plan.workCoordinateSystem(), result))) {
                    return;
                }
                service.get().apply(plan);
            } catch (AlignmentException | RuntimeException e) {
                runOnUserInterface(() -> errorReporter.accept(e.getMessage()));
            } finally {
                runOnUserInterface(() -> {
                    setBusy(false);
                    refreshProgram();
                });
            }
        });
    }

    private boolean confirm(String summary) {
        if (EventQueue.isDispatchThread()) {
            return confirmation.test(summary);
        }

        boolean[] confirmed = new boolean[1];
        try {
            SwingUtilities.invokeAndWait(() -> confirmed[0] = confirmation.test(summary));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.lang.reflect.InvocationTargetException e) {
            return false;
        }
        return confirmed[0];
    }

    static String describeAlignment(File original, File target, Code workCoordinateSystem, AlignmentResult result) {
        return "Apply this alignment?\n\n"
                + "Original program: " + original.getAbsolutePath() + "\n"
                + "Aligned program: " + target.getAbsolutePath() + "\n"
                + "Work coordinate system: " + workCoordinateSystem + "\n"
                + "Rotation: " + format(Math.toDegrees(result.transform().rotation())) + "°\n"
                + "Translation: X" + format(result.transform().translationX()) + " Y" + format(result.transform().translationY()) + " mm\n"
                + "Largest residual: " + format(result.maxResidual()) + " mm\n"
                + "RMS residual: " + format(result.rmsResidual()) + " mm\n"
                + "Scale error: " + format(result.scaleError() * 100) + " %\n\n"
                + "The original program is not changed. The aligned program is written to its own file and loaded.\n"
                + "Applying a height map is turned off and has to be turned on again deliberately.\n"
                + "Nothing is sent to the machine.";
    }

    /**
     * Validates the program that will be sent against the aligned program, which approves it for sending
     */
    void validateProgram() {
        Optional<PcbAlignmentService> service = alignmentService.get();
        Double maxZChange = AlignmentTableModel.parse(maxZChangeField.getText());
        if (service.isEmpty()) {
            errorReporter.accept(ERROR_NO_SERVICE);
            return;
        }
        if (maxZChange == null || !Double.isFinite(maxZChange) || maxZChange < 0) {
            errorReporter.accept(ERROR_CRITERIA);
            return;
        }

        setBusy(true);
        backgroundExecutor.execute(() -> {
            try {
                service.get().validateProcessedFile(maxZChange);
            } catch (AlignmentException | RuntimeException e) {
                runOnUserInterface(() -> errorReporter.accept(e.getMessage()));
            } finally {
                runOnUserInterface(() -> {
                    setBusy(false);
                    refreshProgram();
                });
            }
        });
    }

    private static void runOnUserInterface(Runnable runnable) {
        if (EventQueue.isDispatchThread()) {
            runnable.run();
        } else {
            SwingUtilities.invokeLater(runnable);
        }
    }

    static String format(double value) {
        return AlignmentTableModel.format(value);
    }

    // For reading the state of the panel

    String getProgramText() {
        return programLabel.getText();
    }

    String getValidationText() {
        return validationLabel.getText();
    }

    String getStatusText() {
        return statusLabel.getText();
    }

    String getMachineText() {
        return machineLabel.getText();
    }

    String getRotationText() {
        return rotationLabel.getText();
    }

    String getTranslationText() {
        return translationLabel.getText();
    }

    int getPreviewPathSize() {
        return preview.getProgramPath().size();
    }

    boolean isApplyEnabled() {
        return applyButton.isEnabled();
    }

    JTable getTable() {
        return table;
    }

    void setCriteriaText(String maxResidual, String maxScaleErrorPercent, String minPointDistance) {
        maxResidualField.setText(maxResidual);
        maxScaleErrorField.setText(maxScaleErrorPercent);
        minPointDistanceField.setText(minPointDistance);
        updateCriteria();
    }

    void setMaxZChangeText(String maxZChange) {
        maxZChangeField.setText(maxZChange);
    }

    /**
     * Edits a coordinate. Typing in a cell replaces what is in it, instead of adding to it as a table does
     * by default: a digit added to a coordinate by mistake would still be a coordinate.
     */
    static class CoordinateEditor extends DefaultCellEditor {
        private boolean isStartedByTyping;

        CoordinateEditor() {
            super(new JTextField());
        }

        @Override
        public boolean isCellEditable(EventObject event) {
            isStartedByTyping = event instanceof KeyEvent;
            return super.isCellEditable(event);
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
            JTextField field = (JTextField) super.getTableCellEditorComponent(table, value, isSelected, row, column);
            if (isStartedByTyping) {
                field.setText("");
            } else {
                SwingUtilities.invokeLater(field::selectAll);
            }
            return field;
        }
    }

    /**
     * Shows a button for capturing the position of the machine
     */
    private static class CaptureButtonRenderer implements TableCellRenderer {
        private final JButton button = new JButton(AlignmentTableModel.CAPTURE);

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            return button;
        }
    }

    /**
     * Captures the position of the machine for the reference point of the row whose button is clicked
     */
    private class CaptureButtonEditor extends AbstractCellEditor implements TableCellEditor {
        private final JButton button = new JButton(AlignmentTableModel.CAPTURE);
        private int row;

        CaptureButtonEditor() {
            button.addActionListener(event -> {
                int capturedRow = row;
                fireEditingStopped();
                capturePoint(capturedRow);
            });
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
            this.row = row;
            return button;
        }

        @Override
        public Object getCellEditorValue() {
            return AlignmentTableModel.CAPTURE;
        }
    }
}
