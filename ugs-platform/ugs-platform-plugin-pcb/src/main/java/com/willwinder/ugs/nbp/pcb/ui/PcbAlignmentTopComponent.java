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
import com.willwinder.ugs.nbp.pcb.service.PcbAlignmentService;
import com.willwinder.universalgcodesender.model.BackendAPI;
import com.willwinder.universalgcodesender.services.LookupService;
import com.willwinder.universalgcodesender.utils.GUIHelpers;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.modules.OnStart;
import org.openide.windows.TopComponent;

import javax.swing.JOptionPane;
import java.awt.BorderLayout;

/**
 * The window for aligning the loaded program to a PCB. It uses the alignment service that is
 * registered when the application starts, which is also the one that protects aligned programs
 * from being sent without validation.
 */
@TopComponent.Description(preferredID = PcbAlignmentTopComponent.PREFERRED_ID)
@TopComponent.Registration(mode = Mode.OUTPUT, openAtStartup = false)
@ActionID(category = PcbAlignmentTopComponent.CATEGORY, id = PcbAlignmentTopComponent.ACTION_ID)
@ActionReference(path = LocalizingService.MENU_WINDOW_PLUGIN)
@TopComponent.OpenActionRegistration(
        displayName = PcbAlignmentTopComponent.TITLE,
        preferredID = PcbAlignmentTopComponent.PREFERRED_ID
)
public final class PcbAlignmentTopComponent extends TopComponent {
    public static final String PREFERRED_ID = "PcbAlignmentTopComponent";
    public static final String TITLE = "PCB Alignment";
    public static final String TOOLTIP = "Aligns the loaded program to where a PCB is on the machine";
    public static final String ACTION_ID = "com.willwinder.ugs.nbp.pcb.ui.PcbAlignmentTopComponent";
    public static final String CATEGORY = LocalizingService.CATEGORY_WINDOW;

    private transient BackendAPI backend;
    private transient PcbAlignmentPanel panel;

    public PcbAlignmentTopComponent() {
        setName(TITLE);
        setToolTipText(TOOLTIP);
        setLayout(new BorderLayout());
    }

    @Override
    public void componentOpened() {
        backend = LookupService.lookup(BackendAPI.class);
        panel = new PcbAlignmentPanel(
                backend,
                () -> LookupService.lookupOptional(PcbAlignmentService.class),
                command -> new Thread(command, "PCB alignment").start(),
                message -> JOptionPane.showConfirmDialog(this, message, TITLE, JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) == JOptionPane.OK_OPTION,
                GUIHelpers::displayErrorDialog);
        backend.addUGSEventListener(panel);
        add(panel, BorderLayout.CENTER);
    }

    @Override
    public void componentClosed() {
        backend.removeUGSEventListener(panel);
        removeAll();
        panel = null;
    }

    @OnStart
    public static class Localizer extends TopComponentLocalizer {
        public Localizer() {
            super(CATEGORY, ACTION_ID, TITLE);
        }
    }
}
