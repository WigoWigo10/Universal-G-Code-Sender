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

import com.google.common.collect.ImmutableList;
import com.willwinder.ugs.nbm.visualizer.shared.RenderableUtils;
import com.willwinder.ugs.nbp.pcb.heightmap.GridHeightMap;
import com.willwinder.ugs.nbp.pcb.heightmap.PcbHeightMap;
import com.willwinder.ugs.nbp.pcb.service.PcbHeightMapService;
import com.willwinder.ugs.platform.surfacescanner.renderable.AutoLevelPreview;

import java.util.Optional;

/**
 * Shows the height map of the PCB tools in the 3D visualizer, drawn in the same way as the auto
 * leveler of the application draws its scanned surface.
 */
public class HeightMapVisualization implements PcbHeightMapService.Listener {
    public static final String TITLE = "PCB height map";

    private final PcbHeightMapService service;
    private AutoLevelPreview preview;
    private String shownSurface;

    public HeightMapVisualization(PcbHeightMapService service) {
        this.service = service;
    }

    public synchronized void show() {
        service.addListener(this);
        update();
    }

    public synchronized void hide() {
        service.removeListener(this);
        remove();
    }

    @Override
    public synchronized void onHeightMapChanged() {
        update();
    }

    private void update() {
        Optional<PcbHeightMap> heightMap = service.getHeightMap();
        if (heightMap.isEmpty()) {
            remove();
            return;
        }

        GridHeightMap surface = heightMap.get().surface();
        if (surface.sha256().equals(shownSurface)) {
            return;
        }

        remove();
        preview = new AutoLevelPreview(TITLE);
        preview.updateSettings(ImmutableList.copyOf(surface.getPoints()), surface.toMesh(), surface.getMinHeight(), 0);
        shownSurface = surface.sha256();
        RenderableUtils.registerRenderable(preview);
    }

    private void remove() {
        if (preview != null) {
            RenderableUtils.removeRenderable(preview);
            preview = null;
            shownSurface = null;
        }
    }
}
