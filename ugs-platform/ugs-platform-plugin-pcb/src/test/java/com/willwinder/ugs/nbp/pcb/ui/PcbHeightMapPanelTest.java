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
import com.willwinder.ugs.nbp.pcb.heightmap.HeightMapBinding;
import com.willwinder.ugs.nbp.pcb.heightmap.HeightMapFile;
import com.willwinder.ugs.nbp.pcb.heightmap.PcbHeightMap;
import com.willwinder.ugs.nbp.pcb.heightmap.ProbeArea;
import com.willwinder.ugs.nbp.pcb.heightmap.SyntheticSurface;
import com.willwinder.ugs.nbp.pcb.service.PcbAlignmentService;
import com.willwinder.ugs.nbp.pcb.service.PcbAlignmentService.ValidationState;
import com.willwinder.ugs.nbp.pcb.service.PcbHeightMapService;
import com.willwinder.universalgcodesender.gcode.util.Code;
import com.willwinder.universalgcodesender.model.GUIBackend;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.services.BackendFileLoader;
import com.willwinder.universalgcodesender.utils.Settings;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PcbHeightMapPanelTest {
    private static final RigidTransform2D TRANSFORM = new RigidTransform2D(Math.toRadians(30), 10, 5);
    private static final ProbeArea AREA = new ProbeArea(-5, 5, 35, 40);

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
    private PcbAlignmentService alignmentService;
    private PcbHeightMapService service;
    private PcbHeightMapPanel panel;
    private File source;
    private File fileToSave;
    private final List<String> errors = new ArrayList<>();
    private final List<String> confirmations = new ArrayList<>();
    private boolean confirm = true;

    @Before
    public void setUp() throws Exception {
        backend = new GUIBackend();
        backend.applySettings(new Settings());
        alignmentService = new PcbAlignmentService(backend, new BackendFileLoader(backend), e -> errors.add(e.getMessage()));
        service = new PcbHeightMapService(backend, alignmentService);

        source = new File(folder.getRoot(), "board.nc");
        Files.write(source.toPath(), PROGRAM, StandardCharsets.UTF_8);
        backend.setGcodeFile(source);
        alignmentService.alignLoadedFile(TRANSFORM, Code.G54);
        fileToSave = new File(folder.getRoot(), "saved.pcbmap");

        SwingUtilities.invokeAndWait(() -> panel = new PcbHeightMapPanel(backend, () -> Optional.of(service), Runnable::run,
                message -> {
                    confirmations.add(message);
                    return confirm;
                },
                errors::add,
                new PcbHeightMapPanel.FileChooser() {
                    @Override
                    public Optional<File> chooseFileToOpen() {
                        return Optional.empty();
                    }

                    @Override
                    public Optional<File> chooseFileToSave(String suggestedName) {
                        return Optional.of(fileToSave);
                    }
                }));
        flush();
    }

    private interface UserInterfaceAction {
        void run() throws Exception;
    }

    /**
     * Does something as the operator would and waits until the window shows what came of it
     */
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
        flush();
    }

    private static void flush() throws Exception {
        for (int i = 0; i < 4; i++) {
            SwingUtilities.invokeAndWait(() -> {
            });
        }
    }

    private File writeScannerFile() throws Exception {
        StringBuilder output = new StringBuilder();
        for (Position point : SyntheticSurface.measure(AREA, 5, SyntheticSurface.BENT_BOARD)) {
            output.append(point.getX()).append(" ").append(point.getY()).append(" ").append(point.getZ()).append("\n");
        }
        File file = new File(folder.getRoot(), "scan.xyz");
        Files.writeString(file.toPath(), output);
        return file;
    }

    private void assertButtons(boolean load, boolean save, boolean associate, boolean discard, boolean apply, boolean remove) {
        assertEquals("load", load, panel.getLoadButton().isEnabled());
        assertEquals("save", save, panel.getSaveButton().isEnabled());
        assertEquals("associate", associate, panel.getAssociateButton().isEnabled());
        assertEquals("discard", discard, panel.getDiscardButton().isEnabled());
        assertEquals("apply", apply, panel.getApplyButton().isEnabled());
        assertEquals("remove", remove, panel.getRemoveButton().isEnabled());
    }

    @Test
    public void windowShouldTellThatThereIsNoHeightMap() {
        assertEquals(PcbHeightMapPanel.TEXT_NO_HEIGHT_MAP, panel.getStateLabel().getText());
        assertEquals(PcbHeightMapPanel.ABSENT_COLOR, panel.getStateLabel().getForeground());
        assertEquals(PcbHeightMapService.MESSAGE_ABSENT, panel.getMessageArea().getText());
        assertEquals(PcbHeightMapPanel.TEXT_NO_HEIGHT_MAP, panel.getHeightMapLabel().getText());
        assertButtons(true, false, false, false, false, false);
    }

    @Test
    public void windowShouldTellTheAreaToMeasureForTheLoadedProgram() {
        // The cuts of the aligned program and 2 mm around them
        assertEquals("X 9.8 to 31.2, Y 9.8 to 36.9 mm (the cuts and 2 mm around them)", panel.getAreaLabel().getText());
    }

    @Test
    public void heightMapOfSurfaceScannerShouldBeShownAsIncompatibleUntilItIsAssociated() throws Exception {
        File file = writeScannerFile();

        onUserInterface(() -> panel.loadHeightMap(file));

        assertEquals("Height map incompatible", panel.getStateLabel().getText());
        assertEquals(PcbHeightMapPanel.INCOMPATIBLE_COLOR, panel.getStateLabel().getForeground());
        assertEquals(PcbHeightMapService.ERROR_NOT_ASSOCIATED, panel.getMessageArea().getText());
        assertTrue(panel.getHeightMapLabel().getText(), panel.getHeightMapLabel().getText().startsWith("scan.xyz: 9 x 8 points every 5 mm, heights "));
        assertEquals(PcbHeightMapPanel.TEXT_NOT_ASSOCIATED, panel.getBindingLabel().getText());
        assertButtons(true, true, true, true, false, false);
        assertTrue(errors.toString(), errors.isEmpty());
    }

    @Test
    public void associatingShouldAskTheOperatorAndMakeTheHeightMapAvailable() throws Exception {
        File file = writeScannerFile();
        onUserInterface(() -> panel.loadHeightMap(file));

        onUserInterface(() -> panel.associate());

        assertEquals(1, confirmations.size());
        assertTrue(confirmations.get(0), confirmations.get(0).contains("G54, rotation 30.0000°, translation X10.000 Y5.000 mm"));
        assertEquals("Height map available, not applied", panel.getStateLabel().getText());
        assertEquals(PcbHeightMapPanel.AVAILABLE_COLOR, panel.getStateLabel().getForeground());
        assertEquals("G54, rotation 30.0000°, translation X10.000 Y5.000 mm", panel.getBindingLabel().getText());
        assertButtons(true, true, true, true, true, false);
    }

    @Test
    public void heightMapShouldNotBeAssociatedWhenTheOperatorDeclines() throws Exception {
        File file = writeScannerFile();
        onUserInterface(() -> panel.loadHeightMap(file));
        confirm = false;

        onUserInterface(() -> panel.associate());

        assertEquals(1, confirmations.size());
        assertTrue(service.getHeightMap().orElseThrow().getBinding().isEmpty());
        assertEquals("Height map incompatible", panel.getStateLabel().getText());
    }

    @Test
    public void applyingAndRemovingShouldBeShown() throws Exception {
        service.setHeightMap(new PcbHeightMap(SyntheticSurface.heightMap(AREA, 5, SyntheticSurface.BENT_BOARD), new HeightMapBinding(TRANSFORM, Code.G54), Instant.now(), "synthetic"));
        flush();
        assertButtons(true, true, true, true, true, false);

        onUserInterface(() -> panel.apply());

        assertEquals("Height map applied", panel.getStateLabel().getText());
        assertEquals(PcbHeightMapPanel.APPLIED_COLOR, panel.getStateLabel().getForeground());
        assertEquals(PcbHeightMapService.MESSAGE_APPLIED, panel.getMessageArea().getText());
        assertButtons(false, true, false, false, false, true);
        assertTrue(service.isApplied());
        assertEquals(ValidationState.VALID, alignmentService.getValidationState());

        onUserInterface(() -> panel.remove());

        assertEquals("Height map available, not applied", panel.getStateLabel().getText());
        assertButtons(true, true, true, true, true, false);
        assertFalse(service.isApplied());
        assertTrue(service.getHeightMap().isPresent());
        assertTrue(errors.toString(), errors.isEmpty());
    }

    @Test
    public void incompatibleHeightMapShouldTellWhyAndNotBeApplicable() throws Exception {
        service.setHeightMap(new PcbHeightMap(SyntheticSurface.heightMap(AREA, 5, SyntheticSurface.BENT_BOARD), new HeightMapBinding(TRANSFORM, Code.G55), Instant.now(), "synthetic"));
        flush();

        assertEquals("Height map incompatible", panel.getStateLabel().getText());
        assertEquals("The height map was measured in G55 and the program is aligned in G54.", panel.getMessageArea().getText());
        assertButtons(true, true, true, true, false, false);

        onUserInterface(() -> panel.apply());

        assertEquals(List.of("The height map was measured in G55 and the program is aligned in G54."), errors);
        assertFalse(service.isApplied());
    }

    @Test
    public void windowShouldFollowTheProgramThatIsLoaded() throws Exception {
        service.setHeightMap(new PcbHeightMap(SyntheticSurface.heightMap(AREA, 5, SyntheticSurface.BENT_BOARD), new HeightMapBinding(TRANSFORM, Code.G54), Instant.now(), "synthetic"));
        onUserInterface(() -> panel.apply());

        backend.setGcodeFile(source);
        flush();

        assertEquals("Height map incompatible", panel.getStateLabel().getText());
        assertEquals(PcbHeightMapService.ERROR_NOT_ALIGNED, panel.getMessageArea().getText());
        assertButtons(true, true, true, true, false, false);
    }

    @Test
    public void fileThatIsNotAHeightMapShouldBeReportedAndKeepTheHeightMap() throws Exception {
        File file = writeScannerFile();
        onUserInterface(() -> panel.loadHeightMap(file));

        onUserInterface(() -> panel.loadHeightMap(source));

        assertEquals(1, errors.size());
        assertTrue(errors.get(0), errors.get(0).startsWith(HeightMapFile.ERROR_POINT));
        assertTrue(panel.getHeightMapLabel().getText(), panel.getHeightMapLabel().getText().startsWith("scan.xyz"));
    }

    @Test
    public void savedHeightMapShouldHaveItsAlignment() throws Exception {
        File file = writeScannerFile();
        onUserInterface(() -> panel.loadHeightMap(file));
        onUserInterface(() -> panel.associate());

        onUserInterface(() -> panel.saveHeightMap());

        assertEquals(new HeightMapBinding(TRANSFORM, Code.G54), HeightMapFile.read(fileToSave, com.willwinder.universalgcodesender.model.UnitUtils.Units.MM).binding());
    }

    @Test
    public void discardShouldAskTheOperator() throws Exception {
        File file = writeScannerFile();
        onUserInterface(() -> panel.loadHeightMap(file));
        confirm = false;
        onUserInterface(() -> panel.discard());
        assertTrue(service.getHeightMap().isPresent());

        confirm = true;
        onUserInterface(() -> panel.discard());

        assertTrue(service.getHeightMap().isEmpty());
        assertEquals(PcbHeightMapPanel.TEXT_NO_HEIGHT_MAP, panel.getStateLabel().getText());
    }

    @Test
    public void previewShouldShowHeightsCutsAndWhatIsNotCovered() throws Exception {
        ProbeArea small = new ProbeArea(-5, 5, 15, 40);
        service.setHeightMap(new PcbHeightMap(SyntheticSurface.heightMap(small, 5, SyntheticSurface.BENT_BOARD), new HeightMapBinding(TRANSFORM, Code.G54), Instant.now(), "synthetic"));
        flush();
        HeightMapPreviewPanel preview = panel.getPreview();

        BufferedImage image = new BufferedImage(480, 360, BufferedImage.TYPE_INT_RGB);
        SwingUtilities.invokeAndWait(() -> {
            preview.setSize(480, 360);
            preview.paint(image.getGraphics());
        });

        boolean hasUncovered = false;
        boolean hasLow = false;
        boolean hasHigh = false;
        boolean hasCut = false;
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                int color = image.getRGB(x, y) & 0xFFFFFF;
                hasUncovered |= color == (HeightMapPreviewPanel.UNCOVERED_COLOR.getRGB() & 0xFFFFFF);
                hasLow |= color == (HeightMapPreviewPanel.LOW_COLOR.getRGB() & 0xFFFFFF);
                hasHigh |= color == (HeightMapPreviewPanel.HIGH_COLOR.getRGB() & 0xFFFFFF);
                hasCut |= color == (HeightMapPreviewPanel.CUT_COLOR.getRGB() & 0xFFFFFF);
            }
        }
        assertTrue("uncovered", hasUncovered);
        assertTrue("low", hasLow);
        assertTrue("high", hasHigh);
        assertTrue("cut", hasCut);
        assertTrue(preview.getWorldBounds().contains(-5, 5) && preview.getWorldBounds().contains(15, 39.9));
    }

    @Test
    public void colorsShouldGoFromLowToHigh() {
        assertEquals(HeightMapPreviewPanel.LOW_COLOR, HeightMapPreviewPanel.getColor(-0.2, -0.2, 0.3));
        assertEquals(HeightMapPreviewPanel.MIDDLE_COLOR, HeightMapPreviewPanel.getColor(0.05, -0.2, 0.3));
        assertEquals(HeightMapPreviewPanel.HIGH_COLOR, HeightMapPreviewPanel.getColor(0.3, -0.2, 0.3));
        assertEquals(HeightMapPreviewPanel.HIGH_COLOR, HeightMapPreviewPanel.getColor(9, -0.2, 0.3));
        // A flat surface has a single color
        assertEquals(HeightMapPreviewPanel.MIDDLE_COLOR, HeightMapPreviewPanel.getColor(0.1, 0.1, 0.1));
    }
}
