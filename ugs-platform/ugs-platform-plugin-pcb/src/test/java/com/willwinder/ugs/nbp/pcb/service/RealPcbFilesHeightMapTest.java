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

import com.willwinder.ugs.nbp.pcb.gcode.ToolPathValidator;
import com.willwinder.ugs.nbp.pcb.geometry.RigidTransform2D;
import com.willwinder.ugs.nbp.pcb.heightmap.GridHeightMap;
import com.willwinder.ugs.nbp.pcb.heightmap.HeightMapBinding;
import com.willwinder.ugs.nbp.pcb.heightmap.PcbHeightMap;
import com.willwinder.ugs.nbp.pcb.heightmap.ProbeArea;
import com.willwinder.ugs.nbp.pcb.heightmap.SyntheticSurface;
import com.willwinder.ugs.nbp.pcb.service.PcbAlignmentService.ValidationState;
import com.willwinder.ugs.nbp.pcb.service.PcbHeightMapService.State;
import com.willwinder.universalgcodesender.gcode.DefaultCommandCreator;
import com.willwinder.universalgcodesender.gcode.util.Code;
import com.willwinder.universalgcodesender.model.GUIBackend;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.services.BackendFileLoader;
import com.willwinder.universalgcodesender.utils.GcodeStreamReader;
import com.willwinder.universalgcodesender.utils.IGcodeStreamReader;
import com.willwinder.universalgcodesender.utils.Settings;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Applies a height map to real programs for PCBs. The programs are not part of the source: the
 * test only runs when the system property {@code ugs.pcb.realFiles} is the folder that has them,
 * and it works on copies of them.
 */
public class RealPcbFilesHeightMapTest {
    public static final String FOLDER_PROPERTY = "ugs.pcb.realFiles";
    private static final RigidTransform2D TRANSFORM = new RigidTransform2D(Math.toRadians(1.2), 3.25, -1.75);

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void heightMapShouldBeAppliedToAndRemovedFromEveryRealProgram() throws Exception {
        String realFolder = System.getProperty(FOLDER_PROPERTY);
        Assume.assumeTrue("No folder with real programs was given", realFolder != null);
        File[] programs = new File(realFolder).listFiles((directory, name) -> name.endsWith(".nc") && !name.contains(PcbAlignmentService.ALIGNED_FILE_SUFFIX));
        assertTrue("No programs in " + realFolder, programs != null && programs.length > 0);

        for (File program : programs) {
            File copy = new File(folder.newFolder(), program.getName());
            Files.copy(program.toPath(), copy.toPath());
            verify(copy);
        }
    }

    private void verify(File program) throws Exception {
        GUIBackend backend = new GUIBackend();
        backend.applySettings(new Settings());
        List<String> errors = new ArrayList<>();
        PcbAlignmentService alignmentService = new PcbAlignmentService(backend, new BackendFileLoader(backend), e -> errors.add(e.getMessage()));
        PcbHeightMapService service = new PcbHeightMapService(backend, alignmentService);
        backend.setGcodeFile(program);
        alignmentService.alignLoadedFile(TRANSFORM, Code.G54);
        assertEquals(program.getName(), ValidationState.VALID, alignmentService.getValidationState());

        List<Position> aligned = service.getToolPath();
        ProbeArea area = service.getAreaToMeasure(2).orElseThrow();
        GridHeightMap surface = SyntheticSurface.heightMap(area, 5, SyntheticSurface.BENT_BOARD);
        service.setHeightMap(new PcbHeightMap(surface, new HeightMapBinding(TRANSFORM, Code.G54), Instant.now(), "synthetic"));
        assertEquals(program.getName() + ": " + service.getStatus().message(), State.AVAILABLE, service.getStatus().state());

        service.apply();

        assertEquals(program.getName(), State.APPLIED, service.getStatus().state());
        List<Position> processed = processedPath(backend);
        ToolPathValidator.validate(aligned, processed, 0.005, 0, (x, y) -> surface.covers(x, y) ? surface.heightAt(x, y) : 0);
        ToolPathValidator.validateMoveLength(processed, 1.25 + 0.005, surface::covers);
        double deepest = aligned.stream().mapToDouble(Position::getZ).min().orElseThrow();
        for (Position position : processed) {
            if (surface.covers(position.getX(), position.getY())) {
                assertTrue(program.getName() + " cuts too deep at " + position,
                        position.getZ() - surface.heightAt(position.getX(), position.getY()) >= deepest - 0.005);
            }
        }
        System.out.println(program.getName() + ": " + aligned.size() + " positions aligned, " + processed.size()
                + " with the height map of " + surface.getColumns() + " x " + surface.getRows() + " points");

        service.remove();

        ToolPathValidator.validate(aligned, processedPath(backend), 0.005, 0);
        assertEquals(program.getName(), ValidationState.VALID, alignmentService.getValidationState());
        assertEquals(program.getName(), State.AVAILABLE, service.getStatus().state());
        assertTrue(errors.toString(), errors.isEmpty());
    }

    private static List<Position> processedPath(GUIBackend backend) throws Exception {
        List<String> commands = new ArrayList<>();
        try (IGcodeStreamReader reader = new GcodeStreamReader(backend.getProcessedGcodeFile(), new DefaultCommandCreator())) {
            while (reader.getNumRowsRemaining() > 0) {
                commands.add(reader.getNextCommand().getCommandString());
            }
        }
        return ToolPathValidator.trace(commands);
    }
}
