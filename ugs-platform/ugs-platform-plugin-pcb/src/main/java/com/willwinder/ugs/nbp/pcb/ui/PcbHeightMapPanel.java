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

import com.willwinder.ugs.nbp.pcb.gcode.AlignmentMetadata;
import com.willwinder.ugs.nbp.pcb.heightmap.GridHeightMap;
import com.willwinder.ugs.nbp.pcb.heightmap.HeightMapBinding;
import com.willwinder.ugs.nbp.pcb.heightmap.HeightMapException;
import com.willwinder.ugs.nbp.pcb.heightmap.HeightMapFile;
import com.willwinder.ugs.nbp.pcb.heightmap.PcbHeightMap;
import com.willwinder.ugs.nbp.pcb.heightmap.ProbeArea;
import com.willwinder.ugs.nbp.pcb.service.PcbHeightMapService;
import com.willwinder.ugs.nbp.pcb.service.PcbHeightMapService.State;
import com.willwinder.ugs.nbp.pcb.service.PcbHeightMapService.Status;
import com.willwinder.universalgcodesender.model.BackendAPI;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UnitUtils.Units;
import net.miginfocom.swing.MigLayout;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ScrollPaneConstants;
import javax.swing.Scrollable;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.io.File;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Shows the height map of the board and what can be done with it for the loaded program, and
 * lets the operator load it, associate it with the alignment, and apply or remove its compensation.
 * <p>
 * Nothing here moves the machine. A height map is measured with the surface scanner of the
 * application and saved from there.
 */
public class PcbHeightMapPanel extends JPanel implements PcbHeightMapService.Listener {
    public static final String ERROR_NO_SERVICE = "The PCB tools are not available, see the log of the application.";
    public static final String TEXT_NO_HEIGHT_MAP = "No height map";
    public static final String TEXT_NOT_ASSOCIATED = "Not associated with an alignment";
    public static final String TEXT_NO_AREA = "-";

    /**
     * How far around the cuts of the program a height map should be measured
     */
    public static final double MEASURE_MARGIN_MM = 2;

    static final Color ABSENT_COLOR = new Color(110, 110, 110);
    static final Color AVAILABLE_COLOR = new Color(0, 90, 170);
    static final Color INCOMPATIBLE_COLOR = new Color(190, 60, 0);
    static final Color APPLIED_COLOR = new Color(0, 130, 40);

    /**
     * Lets the operator choose the files of height maps
     */
    public interface FileChooser {
        Optional<File> chooseFileToOpen();

        Optional<File> chooseFileToSave(String suggestedName);
    }

    /**
     * All that is shown, found out away from the user interface as the loaded program is read for it
     */
    record View(Status status, Optional<PcbHeightMap> heightMap, boolean isApplied, List<Position> toolPath,
                List<Position> uncovered, Optional<ProbeArea> areaToMeasure) {
    }

    private final transient BackendAPI backend;
    private final transient Supplier<Optional<PcbHeightMapService>> serviceSupplier;
    private final transient Executor executor;
    private final transient Predicate<String> confirmation;
    private final transient Consumer<String> errorReporter;
    private final transient FileChooser fileChooser;

    private final JLabel stateLabel = new JLabel(TEXT_NO_HEIGHT_MAP);
    private final JTextArea messageArea = new JTextArea(2, 20);
    private final JLabel heightMapLabel = new JLabel(TEXT_NO_HEIGHT_MAP);
    private final JLabel bindingLabel = new JLabel(TEXT_NO_AREA);
    private final JLabel areaLabel = new JLabel(TEXT_NO_AREA);
    private final JButton loadButton = new JButton("Load height map...");
    private final JButton associateButton = new JButton("Associate with this alignment...");
    private final JButton saveButton = new JButton("Save height map...");
    private final JButton discardButton = new JButton("Discard");
    private final JButton applyButton = new JButton("Apply compensation");
    private final JButton removeButton = new JButton("Remove compensation");
    private final HeightMapPreviewPanel preview = new HeightMapPreviewPanel();

    private transient View view;
    private int generation;
    private boolean isBusy;

    public PcbHeightMapPanel(BackendAPI backend, Supplier<Optional<PcbHeightMapService>> serviceSupplier, Executor executor,
                             Predicate<String> confirmation, Consumer<String> errorReporter, FileChooser fileChooser) {
        this.backend = backend;
        this.serviceSupplier = serviceSupplier;
        this.executor = executor;
        this.confirmation = confirmation;
        this.errorReporter = errorReporter;
        this.fileChooser = fileChooser;

        initComponents();
        serviceSupplier.get().ifPresent(service -> service.addListener(this));
        refresh();
    }

    private void initComponents() {
        stateLabel.setFont(stateLabel.getFont().deriveFont(Font.BOLD, stateLabel.getFont().getSize2D() + 2));
        messageArea.setEditable(false);
        messageArea.setLineWrap(true);
        messageArea.setWrapStyleWord(true);
        messageArea.setOpaque(false);
        messageArea.setFont(new JLabel().getFont());

        JPanel details = new JPanel(new MigLayout("insets 0, wrap 2", "[][grow, fill]"));
        details.add(new JLabel("Height map:"));
        details.add(heightMapLabel, "wmin 0");
        details.add(new JLabel("Measured for:"));
        details.add(bindingLabel, "wmin 0");
        details.add(new JLabel("Area to measure:"));
        details.add(areaLabel, "wmin 0");

        JPanel actions = new JPanel(new MigLayout("insets 0, wrap 2, fillx", "[grow, fill, sg][grow, fill, sg]"));
        actions.add(loadButton, "wmin 0");
        actions.add(saveButton, "wmin 0");
        actions.add(associateButton, "wmin 0");
        actions.add(discardButton, "wmin 0");
        actions.add(applyButton, "wmin 0");
        actions.add(removeButton, "wmin 0");

        JPanel left = new WidthTrackingPanel(new MigLayout("insets 0, wrap 1, fillx", "[grow, fill]"));
        left.add(stateLabel, "wmin 0");
        left.add(messageArea, "wmin 0");
        left.add(details, "gaptop 4, wmin 0");
        left.add(actions, "gaptop 4, wmin 0");

        // The window is usually low and wide, so what does not fit is scrolled instead of cut off
        JScrollPane scrollPane = new JScrollPane(left, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setBorder(null);
        scrollPane.getVerticalScrollBar().setUnitIncrement(12);

        setLayout(new MigLayout("fill, insets 6", "[58%!, fill][grow, fill]", "[grow, fill]"));
        add(scrollPane, "wmin 0, hmin 0");
        add(preview, "grow, wmin 0, hmin 0");

        loadButton.addActionListener(event -> fileChooser.chooseFileToOpen().ifPresent(this::loadHeightMap));
        saveButton.addActionListener(event -> saveHeightMap());
        associateButton.addActionListener(event -> associate());
        discardButton.addActionListener(event -> discard());
        applyButton.addActionListener(event -> apply());
        removeButton.addActionListener(event -> remove());
        updateButtons();
    }

    /**
     * Stops listening to the height map service, for when the window is closed
     */
    public void dispose() {
        serviceSupplier.get().ifPresent(service -> service.removeListener(this));
    }

    @Override
    public void onHeightMapChanged() {
        refresh();
    }

    /**
     * Finds out what there is to show and shows it. The latest call wins if they finish in another order.
     */
    public void refresh() {
        Optional<PcbHeightMapService> service = serviceSupplier.get();
        if (service.isEmpty()) {
            EventQueue.invokeLater(() -> show(null, ERROR_NO_SERVICE));
            return;
        }

        int current;
        synchronized (this) {
            current = ++generation;
        }
        executor.execute(() -> {
            View result = createView(service.get());
            EventQueue.invokeLater(() -> {
                synchronized (this) {
                    if (current != generation) {
                        return;
                    }
                }
                show(result, null);
            });
        });
    }

    private static View createView(PcbHeightMapService service) {
        return new View(service.getStatus(), service.getHeightMap(), service.isApplied(), service.getToolPath(),
                service.findUncoveredCuts(), service.getAreaToMeasure(MEASURE_MARGIN_MM));
    }

    private void show(View newView, String error) {
        view = newView;
        if (newView == null) {
            stateLabel.setText(TEXT_NO_HEIGHT_MAP);
            stateLabel.setForeground(ABSENT_COLOR);
            messageArea.setText(error);
            heightMapLabel.setText(TEXT_NO_HEIGHT_MAP);
            bindingLabel.setText(TEXT_NO_AREA);
            areaLabel.setText(TEXT_NO_AREA);
            preview.setData(List.of(), null, List.of());
            updateButtons();
            return;
        }

        stateLabel.setText(getStateText(newView.status().state()));
        stateLabel.setForeground(getStateColor(newView.status().state()));
        messageArea.setText(newView.status().message());
        heightMapLabel.setText(newView.heightMap().map(PcbHeightMapPanel::describe).orElse(TEXT_NO_HEIGHT_MAP));
        heightMapLabel.setToolTipText(heightMapLabel.getText());
        bindingLabel.setText(newView.heightMap().map(heightMap -> heightMap.getBinding().map(PcbHeightMapPanel::describe).orElse(TEXT_NOT_ASSOCIATED)).orElse(TEXT_NO_AREA));
        bindingLabel.setToolTipText(bindingLabel.getText());
        areaLabel.setText(newView.areaToMeasure().map(PcbHeightMapPanel::describe).orElse(TEXT_NO_AREA));
        areaLabel.setToolTipText(areaLabel.getText());
        preview.setData(newView.toolPath(), newView.heightMap().map(PcbHeightMap::surface).orElse(null), newView.uncovered());
        updateButtons();
    }

    static String getStateText(State state) {
        return switch (state) {
            case ABSENT -> TEXT_NO_HEIGHT_MAP;
            case AVAILABLE -> "Height map available, not applied";
            case INCOMPATIBLE -> "Height map incompatible";
            case APPLIED -> "Height map applied";
        };
    }

    static Color getStateColor(State state) {
        return switch (state) {
            case ABSENT -> ABSENT_COLOR;
            case AVAILABLE -> AVAILABLE_COLOR;
            case INCOMPATIBLE -> INCOMPATIBLE_COLOR;
            case APPLIED -> APPLIED_COLOR;
        };
    }

    static String describe(PcbHeightMap heightMap) {
        GridHeightMap surface = heightMap.surface();
        return String.format(Locale.ROOT, "%s: %d x %d points every %s mm, heights %+.3f to %+.3f mm, %s",
                heightMap.name(), surface.getColumns(), surface.getRows(), AlignmentTableModel.format(surface.getStep()),
                surface.getMinHeight(), surface.getMaxHeight(),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(heightMap.created()));
    }

    static String describe(HeightMapBinding binding) {
        return String.format(Locale.ROOT, "%s, rotation %.4f°, translation X%.3f Y%.3f mm", binding.workCoordinateSystem(),
                Math.toDegrees(binding.transform().rotation()), binding.transform().translationX(), binding.transform().translationY());
    }

    static String describe(ProbeArea area) {
        return String.format(Locale.ROOT, "X %.1f to %.1f, Y %.1f to %.1f mm (the cuts and %s mm around them)",
                Math.floor(area.minX() * 10) / 10, Math.ceil(area.maxX() * 10) / 10,
                Math.floor(area.minY() * 10) / 10, Math.ceil(area.maxY() * 10) / 10, AlignmentTableModel.format(MEASURE_MARGIN_MM));
    }

    private void updateButtons() {
        boolean hasHeightMap = view != null && view.heightMap().isPresent();
        boolean isApplied = view != null && view.isApplied();
        loadButton.setEnabled(view != null && !isBusy && !isApplied);
        saveButton.setEnabled(hasHeightMap && !isBusy);
        associateButton.setEnabled(hasHeightMap && !isBusy && !isApplied);
        discardButton.setEnabled(hasHeightMap && !isBusy && !isApplied);
        applyButton.setEnabled(view != null && !isBusy && view.status().state() == State.AVAILABLE);
        removeButton.setEnabled(!isBusy && isApplied);
    }

    /**
     * A panel that is as wide as the scroll pane it is in, so its text is wrapped instead of scrolled
     */
    static class WidthTrackingPanel extends JPanel implements Scrollable {
        WidthTrackingPanel(LayoutManager layout) {
            super(layout);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return 12;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(12, visible.height - 12);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    @FunctionalInterface
    private interface Operation {
        void run(PcbHeightMapService service) throws HeightMapException;
    }

    /**
     * Does something with the height map away from the user interface, as the program is processed for it
     */
    private void run(Operation operation) {
        Optional<PcbHeightMapService> service = serviceSupplier.get();
        if (service.isEmpty()) {
            errorReporter.accept(ERROR_NO_SERVICE);
            return;
        }

        isBusy = true;
        updateButtons();
        executor.execute(() -> {
            String error = null;
            try {
                operation.run(service.get());
            } catch (HeightMapException e) {
                error = e.getMessage();
            } catch (RuntimeException e) {
                error = String.valueOf(e.getMessage());
            }

            String message = error;
            EventQueue.invokeLater(() -> {
                isBusy = false;
                if (message != null) {
                    errorReporter.accept(message);
                }
                refresh();
            });
        });
    }

    /**
     * Loads a height map from a file. Files of the surface scanner do not tell their units, they are
     * read in the units that the application is set to.
     */
    public void loadHeightMap(File file) {
        Units units = backend.getSettings().getPreferredUnits();
        run(service -> service.load(file, units));
    }

    public void saveHeightMap() {
        if (view == null || view.heightMap().isEmpty()) {
            return;
        }
        String name = view.heightMap().get().name().replaceAll("\\.[^.]*$", "") + "." + HeightMapFile.EXTENSION;
        fileChooser.chooseFileToSave(name).ifPresent(file -> run(service -> service.save(file)));
    }

    public void associate() {
        if (view == null || view.heightMap().isEmpty()) {
            return;
        }

        Optional<AlignmentMetadata> metadata = readLoadedAlignment();
        if (metadata.isEmpty()) {
            errorReporter.accept(PcbHeightMapService.ERROR_NOT_ALIGNED);
            return;
        }
        String message = "Associate the height map \"" + view.heightMap().get().name() + "\" with the alignment of the loaded program?\n\n"
                + "Alignment: " + describe(HeightMapBinding.of(metadata.get())) + "\n\n"
                + "Only do this if the height map was measured in " + metadata.get().workCoordinateSystem() + " with the board where it is now,\n"
                + "and Z zero has not been set again since. The application can not verify this.";
        if (confirmation.test(message)) {
            run(PcbHeightMapService::associateWithLoadedProgram);
        }
    }

    private Optional<AlignmentMetadata> readLoadedAlignment() {
        File loadedFile = backend.getGcodeFile();
        try {
            return loadedFile == null ? Optional.empty() : AlignmentMetadata.read(loadedFile);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public void discard() {
        if (view != null && view.heightMap().isPresent() && confirmation.test("Discard the height map \"" + view.heightMap().get().name() + "\"? If it was not saved it is lost.")) {
            run(PcbHeightMapService::discard);
        }
    }

    public void apply() {
        run(PcbHeightMapService::apply);
    }

    public void remove() {
        run(PcbHeightMapService::remove);
    }

    JLabel getStateLabel() {
        return stateLabel;
    }

    JTextArea getMessageArea() {
        return messageArea;
    }

    JLabel getHeightMapLabel() {
        return heightMapLabel;
    }

    JLabel getBindingLabel() {
        return bindingLabel;
    }

    JLabel getAreaLabel() {
        return areaLabel;
    }

    JButton getLoadButton() {
        return loadButton;
    }

    JButton getAssociateButton() {
        return associateButton;
    }

    JButton getSaveButton() {
        return saveButton;
    }

    JButton getDiscardButton() {
        return discardButton;
    }

    JButton getApplyButton() {
        return applyButton;
    }

    JButton getRemoveButton() {
        return removeButton;
    }

    HeightMapPreviewPanel getPreview() {
        return preview;
    }
}
