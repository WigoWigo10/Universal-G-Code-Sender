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
package com.willwinder.ugs.nbp.pcb.heightmap;

import com.willwinder.ugs.nbp.pcb.geometry.RigidTransform2D;
import com.willwinder.universalgcodesender.gcode.util.Code;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UnitUtils.Units;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class HeightMapFileTest {
    private static final ProbeArea AREA = new ProbeArea(10, 20, 30, 50);
    private static final HeightMapBinding BINDING = new HeightMapBinding(new RigidTransform2D(0.0123456789012, 10.25, -3.5), Code.G55);

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private PcbHeightMap heightMap(HeightMapBinding binding) throws Exception {
        return new PcbHeightMap(SyntheticSurface.heightMap(AREA, 5, SyntheticSurface.BENT_BOARD), binding, Instant.parse("2026-10-11T12:30:00Z"), "Board (top), scan 1");
    }

    private File write(PcbHeightMap heightMap) throws Exception {
        File file = new File(folder.getRoot(), "board." + HeightMapFile.EXTENSION);
        HeightMapFile.write(heightMap, file);
        return file;
    }

    /**
     * A file as the surface scanner of the application saves it
     */
    private File writeScannerFile(List<Position> points) throws Exception {
        StringBuilder output = new StringBuilder();
        for (Position point : points) {
            output.append(point.getX()).append(" ").append(point.getY()).append(" ").append(point.getZ()).append("\n");
        }
        File file = new File(folder.getRoot(), "scan.xyz");
        Files.writeString(file.toPath(), output, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    public void heightMapShouldBeTheSameWhenItIsReadAgain() throws Exception {
        PcbHeightMap original = heightMap(BINDING);

        PcbHeightMap read = HeightMapFile.read(write(original), Units.INCH);

        assertEquals(original.surface().toLines(), read.surface().toLines());
        assertEquals(BINDING, read.binding());
        assertEquals(original.created(), read.created());
        assertEquals("Board (top), scan 1", read.name());
        assertEquals(original.id(), read.id());
    }

    @Test
    public void heightMapWithoutBindingShouldBeReadWithoutBinding() throws Exception {
        PcbHeightMap read = HeightMapFile.read(write(heightMap(null)), Units.MM);

        assertNull(read.binding());
        assertTrue(read.getBinding().isEmpty());
    }

    @Test
    public void fileOfSurfaceScannerShouldBeReadWithoutBinding() throws Exception {
        List<Position> points = SyntheticSurface.measure(AREA, 5, SyntheticSurface.BENT_BOARD);
        File file = writeScannerFile(points);

        PcbHeightMap read = HeightMapFile.read(file, Units.MM);

        assertEquals(GridHeightMap.of(points).toLines(), read.surface().toLines());
        assertNull(read.binding());
        assertEquals("scan.xyz", read.name());
    }

    @Test
    public void fileOfSurfaceScannerShouldBeReadInTheGivenUnits() throws Exception {
        File file = writeScannerFile(List.of(
                new Position(0, 0, 0.001, Units.INCH),
                new Position(0, 1, 0.002, Units.INCH),
                new Position(1, 0, 0.003, Units.INCH),
                new Position(1, 1, 0.004, Units.INCH)));

        PcbHeightMap read = HeightMapFile.read(file, Units.INCH);

        assertEquals(new ProbeArea(0, 0, 25.4, 25.4), read.surface().getArea());
        assertEquals(0.004 * 25.4, read.surface().heightAt(25.4, 25.4), 1e-9);
    }

    @Test
    public void fileThatWasCutShortShouldNotBeRead() throws Exception {
        File file = write(heightMap(BINDING));
        List<String> lines = Files.readAllLines(file.toPath());
        Files.write(file.toPath(), lines.subList(0, lines.size() - 5));

        HeightMapException exception = assertThrows(HeightMapException.class, () -> HeightMapFile.read(file, Units.MM));

        assertEquals(String.format(HeightMapFile.ERROR_INCOMPLETE, 35, 30), exception.getMessage());
    }

    @Test
    public void fileThatWasCutInTheMiddleOfALineShouldNotBeRead() throws Exception {
        File file = write(heightMap(BINDING));
        String content = Files.readString(file.toPath());
        Files.writeString(file.toPath(), content.substring(0, content.length() - 12));

        HeightMapException exception = assertThrows(HeightMapException.class, () -> HeightMapFile.read(file, Units.MM));

        assertTrue(exception.getMessage(), exception.getMessage().startsWith(HeightMapFile.ERROR_POINT)
                || exception.getMessage().equals(HeightMapFile.ERROR_CHANGED));
    }

    @Test
    public void fileWithAChangedHeightShouldNotBeRead() throws Exception {
        File file = write(heightMap(BINDING));
        List<String> lines = new ArrayList<>(Files.readAllLines(file.toPath()));
        String last = lines.get(lines.size() - 1);
        lines.set(lines.size() - 1, last.substring(0, last.lastIndexOf(' ')) + " 0.123456");
        Files.write(file.toPath(), lines);

        HeightMapException exception = assertThrows(HeightMapException.class, () -> HeightMapFile.read(file, Units.MM));

        assertEquals(HeightMapFile.ERROR_CHANGED, exception.getMessage());
    }

    @Test
    public void fileWithoutItsAlignmentShouldNotBeReadAsIfItHadOne() throws Exception {
        File file = write(heightMap(BINDING));
        List<String> lines = new ArrayList<>(Files.readAllLines(file.toPath()));
        lines.removeIf(line -> line.startsWith("# rotation-rad"));
        Files.write(file.toPath(), lines);

        HeightMapException exception = assertThrows(HeightMapException.class, () -> HeightMapFile.read(file, Units.MM));

        assertTrue(exception.getMessage(), exception.getMessage().startsWith(HeightMapFile.ERROR_HEADER));
    }

    @Test
    public void fileOfAnotherVersionShouldNotBeRead() throws Exception {
        File file = write(heightMap(BINDING));
        List<String> lines = new ArrayList<>(Files.readAllLines(file.toPath()));
        lines.set(0, "# UGS-PCB-HEIGHTMAP version=2");
        Files.write(file.toPath(), lines);

        HeightMapException exception = assertThrows(HeightMapException.class, () -> HeightMapFile.read(file, Units.MM));

        assertTrue(exception.getMessage(), exception.getMessage().startsWith(HeightMapFile.ERROR_HEADER));
    }

    @Test
    public void incompleteFileOfSurfaceScannerShouldNotBeRead() throws Exception {
        List<Position> points = SyntheticSurface.measure(AREA, 5, SyntheticSurface.BENT_BOARD);
        File file = writeScannerFile(points.subList(0, points.size() - 3));

        HeightMapException exception = assertThrows(HeightMapException.class, () -> HeightMapFile.read(file, Units.MM));

        assertTrue(exception.getMessage(), exception.getMessage().startsWith(GridHeightMap.ERROR_MISSING_POINT));
    }

    @Test
    public void fileThatIsNotAHeightMapShouldNotBeRead() throws Exception {
        File file = new File(folder.getRoot(), "program.nc");
        Files.writeString(file.toPath(), "G21 G90\nG0 X1 Y2 Z3\n");

        HeightMapException exception = assertThrows(HeightMapException.class, () -> HeightMapFile.read(file, Units.MM));

        assertTrue(exception.getMessage(), exception.getMessage().startsWith(HeightMapFile.ERROR_POINT));
    }

    @Test
    public void missingFileShouldNotBeRead() {
        HeightMapException exception = assertThrows(HeightMapException.class,
                () -> HeightMapFile.read(new File(folder.getRoot(), "missing.xyz"), Units.MM));

        assertTrue(exception.getMessage(), exception.getMessage().startsWith(HeightMapFile.ERROR_READ));
    }

    @Test
    public void failedWriteShouldKeepTheExistingFile() throws Exception {
        File file = write(heightMap(BINDING));
        byte[] before = Files.readAllBytes(file.toPath());
        File missingFolder = new File(new File(folder.getRoot(), "missing"), "board.pcbmap");

        assertThrows(HeightMapException.class, () -> HeightMapFile.write(heightMap(null), missingFolder));

        assertArrayEquals(before, Files.readAllBytes(file.toPath()));
        assertEquals(1, folder.getRoot().listFiles().length);
    }
}
