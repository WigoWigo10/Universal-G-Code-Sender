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

import com.willwinder.ugs.nbp.lib.Mode;
import com.willwinder.ugs.nbp.lib.services.LocalizingService;
import com.willwinder.ugs.nbp.lib.services.TopComponentLocalizer;
import com.willwinder.ugs.nbp.pcb.heightmap.HeightMapFile;
import com.willwinder.ugs.nbp.pcb.service.PcbHeightMapService;
import com.willwinder.universalgcodesender.model.BackendAPI;
import com.willwinder.universalgcodesender.services.LookupService;
import com.willwinder.universalgcodesender.utils.GUIHelpers;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.modules.OnStart;
import org.openide.windows.TopComponent;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.io.File;
import java.util.Optional;

/**
 * The window for the height map of a PCB. It uses the height map service that is registered when
 * the application starts, so the height map is kept while the window is closed.
 */
@TopComponent.Description(preferredID = PcbHeightMapTopComponent.PREFERRED_ID)
@TopComponent.Registration(mode = Mode.OUTPUT, openAtStartup = false)
@ActionID(category = PcbHeightMapTopComponent.CATEGORY, id = PcbHeightMapTopComponent.ACTION_ID)
@ActionReference(path = LocalizingService.MENU_WINDOW_PLUGIN)
@TopComponent.OpenActionRegistration(
        displayName = PcbHeightMapTopComponent.TITLE,
        preferredID = PcbHeightMapTopComponent.PREFERRED_ID
)
public final class PcbHeightMapTopComponent extends TopComponent implements PcbHeightMapPanel.FileChooser {
    public static final String PREFERRED_ID = "PcbHeightMapTopComponent";
    public static final String TITLE = "PCB Height Map";
    public static final String TOOLTIP = "Applies the measured heights of a PCB to its aligned program";
    public static final String ACTION_ID = "com.willwinder.ugs.nbp.pcb.ui.PcbHeightMapTopComponent";
    public static final String CATEGORY = LocalizingService.CATEGORY_WINDOW;

    private final transient JFileChooser fileChooser = new JFileChooser();
    private transient PcbHeightMapPanel panel;
    private transient HeightMapVisualization visualization;

    public PcbHeightMapTopComponent() {
        setName(TITLE);
        setToolTipText(TOOLTIP);
        setLayout(new BorderLayout());
        fileChooser.setFileFilter(new FileNameExtensionFilter("Height maps (*." + HeightMapFile.EXTENSION + ", *.xyz)", HeightMapFile.EXTENSION, "xyz"));
    }

    @Override
    public void componentOpened() {
        BackendAPI backend = LookupService.lookup(BackendAPI.class);
        panel = new PcbHeightMapPanel(
                backend,
                () -> LookupService.lookupOptional(PcbHeightMapService.class),
                command -> new Thread(command, "PCB height map").start(),
                message -> JOptionPane.showConfirmDialog(this, message, TITLE, JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) == JOptionPane.OK_OPTION,
                GUIHelpers::displayErrorDialog,
                this);
        add(panel, BorderLayout.CENTER);

        // The height map is shown in the 3D visualizer while this window is open
        LookupService.lookupOptional(PcbHeightMapService.class).ifPresent(service -> {
            visualization = new HeightMapVisualization(service);
            visualization.show();
        });
    }

    @Override
    public void componentClosed() {
        if (visualization != null) {
            visualization.hide();
            visualization = null;
        }
        panel.dispose();
        removeAll();
        panel = null;
    }

    @Override
    public Optional<File> chooseFileToOpen() {
        File loadedFile = LookupService.lookup(BackendAPI.class).getGcodeFile();
        if (loadedFile != null && fileChooser.getSelectedFile() == null) {
            fileChooser.setCurrentDirectory(loadedFile.getParentFile());
        }
        return fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION ? Optional.ofNullable(fileChooser.getSelectedFile()) : Optional.empty();
    }

    @Override
    public Optional<File> chooseFileToSave(String suggestedName) {
        File folder = fileChooser.getCurrentDirectory();
        File loadedFile = LookupService.lookup(BackendAPI.class).getGcodeFile();
        if (loadedFile != null && fileChooser.getSelectedFile() == null) {
            folder = loadedFile.getParentFile();
        }
        fileChooser.setSelectedFile(new File(folder, suggestedName));
        if (fileChooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return Optional.empty();
        }

        File file = fileChooser.getSelectedFile();
        if (!file.getName().toLowerCase().endsWith("." + HeightMapFile.EXTENSION)) {
            file = new File(file.getParentFile(), file.getName() + "." + HeightMapFile.EXTENSION);
        }
        boolean isReplaceConfirmed = !file.exists() || JOptionPane.showConfirmDialog(this, file.getName() + " exists. Replace it?", TITLE,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
        return isReplaceConfirmed ? Optional.of(file) : Optional.empty();
    }

    @OnStart
    public static class Localizer extends TopComponentLocalizer {
        public Localizer() {
            super(CATEGORY, ACTION_ID, TITLE);
        }
    }
}
