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
import com.willwinder.ugs.nbp.pcb.service.PcbProbingService;
import com.willwinder.universalgcodesender.model.BackendAPI;
import com.willwinder.universalgcodesender.services.LookupService;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.modules.OnStart;
import org.openide.windows.TopComponent;

import java.awt.BorderLayout;

/**
 * The window for planning and simulating the probing of a PCB
 */
@TopComponent.Description(preferredID = PcbProbingTopComponent.PREFERRED_ID)
@TopComponent.Registration(mode = Mode.OUTPUT, openAtStartup = false)
@ActionID(category = PcbProbingTopComponent.CATEGORY, id = PcbProbingTopComponent.ACTION_ID)
@ActionReference(path = LocalizingService.MENU_WINDOW_PLUGIN)
@TopComponent.OpenActionRegistration(
        displayName = PcbProbingTopComponent.TITLE,
        preferredID = PcbProbingTopComponent.PREFERRED_ID
)
public final class PcbProbingTopComponent extends TopComponent {
    public static final String PREFERRED_ID = "PcbProbingTopComponent";
    public static final String TITLE = "PCB Probing";
    public static final String TOOLTIP = "Plans and simulates the measuring of the surface of a PCB";
    public static final String ACTION_ID = "com.willwinder.ugs.nbp.pcb.ui.PcbProbingTopComponent";
    public static final String CATEGORY = LocalizingService.CATEGORY_WINDOW;

    private transient BackendAPI backend;
    private transient PcbProbingPanel panel;

    public PcbProbingTopComponent() {
        setName(TITLE);
        setToolTipText(TOOLTIP);
        setLayout(new BorderLayout());
    }

    @Override
    public void componentOpened() {
        backend = LookupService.lookup(BackendAPI.class);
        panel = new PcbProbingPanel(
                () -> LookupService.lookupOptional(PcbProbingService.class),
                command -> new Thread(command, "PCB probing simulation").start());
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
