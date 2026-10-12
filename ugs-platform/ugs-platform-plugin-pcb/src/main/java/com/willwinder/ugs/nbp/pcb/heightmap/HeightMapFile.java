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

import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes height maps. Two kinds of files are read:
 * <ul>
 * <li>The files of the PCB tools, which begin with lines that tell what the height map was
 * measured for and how many points it has, so a file that was cut short or changed is not read.</li>
 * <li>The files that the surface scanner of the application saves, with the X, Y and Z of a point
 * on every line. They tell nothing else, so their height map has to be associated with an
 * alignment by the operator before it can be applied.</li>
 * </ul>
 */
public final class HeightMapFile {
    public static final String EXTENSION = "pcbmap";
    public static final String MARKER = "# UGS-PCB-HEIGHTMAP version=1";

    public static final String ERROR_READ = "The height map could not be read: ";
    public static final String ERROR_POINT = "The height map has a line that is not the X, Y and Z of a point: ";
    public static final String ERROR_HEADER = "The height map has no valid ";
    public static final String ERROR_INCOMPLETE = "The height map file is incomplete or was changed: it tells that it has %d points and has %d.";
    public static final String ERROR_CHANGED = "The points of the height map file are not the ones that were saved, the file was changed or damaged.";
    public static final String ERROR_WRITE = "The height map could not be saved: ";

    private static final String MARKER_PREFIX = "# UGS-PCB-HEIGHTMAP";
    private static final String CREATED = "created";
    private static final String NAME = "name";
    private static final String WORK_COORDINATE_SYSTEM = "wcs";
    private static final String ROTATION = "rotation-rad";
    private static final String TRANSLATION_X = "translation-x-mm";
    private static final String TRANSLATION_Y = "translation-y-mm";
    private static final String POINTS = "points";
    private static final String POINTS_SHA256 = "points-sha256";

    private HeightMapFile() {
    }

    /**
     * @param file            a file of the PCB tools or of the surface scanner
     * @param scannerUnits    the units of the points if the file is of the surface scanner, which does not tell them
     * @throws HeightMapException if the file is not a complete height map
     */
    public static PcbHeightMap read(File file, Units scannerUnits) throws HeightMapException {
        List<String> lines;
        try {
            lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new HeightMapException(ERROR_READ + file, e);
        }

        boolean isOfPcbTools = !lines.isEmpty() && lines.get(0).startsWith(MARKER_PREFIX);
        if (!isOfPcbTools) {
            GridHeightMap surface = GridHeightMap.of(readPoints(lines, scannerUnits));
            return new PcbHeightMap(surface, null, Instant.ofEpochMilli(file.lastModified()), file.getName());
        }
        if (!lines.get(0).trim().equals(MARKER)) {
            throw new HeightMapException(ERROR_HEADER + "version: " + lines.get(0));
        }

        Map<String, String> header = new LinkedHashMap<>();
        for (String line : lines) {
            int separator = line.indexOf('=');
            if (line.startsWith("#") && separator > 0) {
                header.put(line.substring(1, separator).trim(), line.substring(separator + 1).trim());
            }
        }

        List<Position> points = readPoints(lines, Units.MM);
        int expectedPoints = (int) number(header, POINTS);
        if (points.size() != expectedPoints) {
            throw new HeightMapException(String.format(ERROR_INCOMPLETE, expectedPoints, points.size()));
        }
        GridHeightMap surface = GridHeightMap.of(points);
        if (!surface.sha256().equals(header.get(POINTS_SHA256))) {
            throw new HeightMapException(ERROR_CHANGED);
        }

        HeightMapBinding binding = null;
        if (header.containsKey(WORK_COORDINATE_SYSTEM)) {
            binding = new HeightMapBinding(
                    new RigidTransform2D(number(header, ROTATION), number(header, TRANSLATION_X), number(header, TRANSLATION_Y)),
                    workCoordinateSystem(header));
        }
        return new PcbHeightMap(surface, binding, created(header), URLDecoder.decode(header.getOrDefault(NAME, file.getName()), StandardCharsets.UTF_8));
    }

    private static List<Position> readPoints(List<String> lines, Units units) throws HeightMapException {
        List<Position> result = new ArrayList<>();
        for (String line : lines) {
            String text = line.trim();
            if (text.isEmpty() || text.startsWith("#")) {
                continue;
            }
            String[] values = text.split("[\\s,;]+");
            if (values.length != 3) {
                throw new HeightMapException(ERROR_POINT + text);
            }
            try {
                result.add(new Position(Double.parseDouble(values[0]), Double.parseDouble(values[1]), Double.parseDouble(values[2]), units));
            } catch (NumberFormatException e) {
                throw new HeightMapException(ERROR_POINT + text, e);
            }
        }
        return result;
    }

    private static double number(Map<String, String> header, String key) throws HeightMapException {
        try {
            double result = Double.parseDouble(header.getOrDefault(key, ""));
            if (!Double.isFinite(result)) {
                throw new HeightMapException(ERROR_HEADER + key);
            }
            return result;
        } catch (NumberFormatException e) {
            throw new HeightMapException(ERROR_HEADER + key, e);
        }
    }

    private static Code workCoordinateSystem(Map<String, String> header) throws HeightMapException {
        try {
            return Code.valueOf(header.get(WORK_COORDINATE_SYSTEM));
        } catch (IllegalArgumentException e) {
            throw new HeightMapException(ERROR_HEADER + WORK_COORDINATE_SYSTEM, e);
        }
    }

    private static Instant created(Map<String, String> header) throws HeightMapException {
        try {
            return Instant.parse(header.getOrDefault(CREATED, ""));
        } catch (DateTimeParseException e) {
            throw new HeightMapException(ERROR_HEADER + CREATED, e);
        }
    }

    /**
     * Writes the height map to a file of the PCB tools. An existing file is only replaced when all
     * of the height map has been written.
     */
    public static void write(PcbHeightMap heightMap, File file) throws HeightMapException {
        List<String> lines = new ArrayList<>();
        lines.add(MARKER);
        lines.add("# units=mm");
        lines.add("# " + CREATED + "=" + heightMap.created());
        lines.add("# " + NAME + "=" + URLEncoder.encode(heightMap.name(), StandardCharsets.UTF_8));
        if (heightMap.binding() != null) {
            lines.add("# " + WORK_COORDINATE_SYSTEM + "=" + heightMap.binding().workCoordinateSystem());
            lines.add("# " + ROTATION + "=" + heightMap.binding().transform().rotation());
            lines.add("# " + TRANSLATION_X + "=" + heightMap.binding().transform().translationX());
            lines.add("# " + TRANSLATION_Y + "=" + heightMap.binding().transform().translationY());
        }
        List<String> points = heightMap.surface().toLines();
        lines.add("# " + POINTS + "=" + points.size());
        lines.add("# " + POINTS_SHA256 + "=" + heightMap.surface().sha256());
        lines.addAll(points);

        Path target = file.getAbsoluteFile().toPath();
        Path temporary = null;
        try {
            temporary = Files.createTempFile(target.getParent(), file.getName(), ".tmp");
            Files.write(temporary, lines, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new HeightMapException(ERROR_WRITE + file, e);
        } finally {
            try {
                if (temporary != null) {
                    Files.deleteIfExists(temporary);
                }
            } catch (IOException e) {
                // The failure to write is the one that is reported
            }
        }
    }
}
