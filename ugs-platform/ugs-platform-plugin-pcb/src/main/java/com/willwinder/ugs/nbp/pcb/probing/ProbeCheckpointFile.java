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
package com.willwinder.ugs.nbp.pcb.probing;

import com.willwinder.ugs.nbp.pcb.heightmap.ProbeArea;
import com.willwinder.universalgcodesender.gcode.util.Code;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UnitUtils.Units;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads and writes checkpoints of probing sessions. The points are written in another way than
 * in a height map, so that a checkpoint is never read as a height map: its measured points may
 * well be a complete grid of a part of the board.
 */
public final class ProbeCheckpointFile {
    public static final String EXTENSION = "pcbprobe";
    public static final String MARKER = "# UGS-PCB-PROBE-CHECKPOINT version=1";
    public static final String ERROR_READ = "The checkpoint could not be read: ";
    public static final String ERROR_INVALID = "The file is not a complete checkpoint of a probing session: ";
    public static final String ERROR_WRITE = "The checkpoint could not be saved: ";

    private static final String POINT_PREFIX = "P ";

    private ProbeCheckpointFile() {
    }

    public static void write(ProbeCheckpoint checkpoint, File file) throws ProbeSessionException {
        List<String> lines = new ArrayList<>();
        lines.add(MARKER);
        lines.add("# session=" + checkpoint.sessionId());
        lines.add("# created=" + checkpoint.created());
        lines.add("# wcs=" + checkpoint.reference().workCoordinateSystem());
        lines.add("# offset-x-mm=" + checkpoint.reference().offsetX());
        lines.add("# offset-y-mm=" + checkpoint.reference().offsetY());
        lines.add("# offset-z-mm=" + checkpoint.reference().offsetZ());
        lines.add("# area-min-x-mm=" + checkpoint.gridArea().minX());
        lines.add("# area-min-y-mm=" + checkpoint.gridArea().minY());
        lines.add("# area-max-x-mm=" + checkpoint.gridArea().maxX());
        lines.add("# area-max-y-mm=" + checkpoint.gridArea().maxY());
        lines.add("# step-mm=" + checkpoint.stepMM());
        lines.add("# planned=" + checkpoint.plannedPoints());
        lines.add("# measured=" + checkpoint.measured().size());
        for (Position point : checkpoint.measured()) {
            lines.add(String.format(Locale.ROOT, "%s%.6f %.6f %.6f", POINT_PREFIX, point.getX(), point.getY(), point.getZ()));
        }

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
            throw new ProbeSessionException(ERROR_WRITE + file);
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

    public static ProbeCheckpoint read(File file) throws ProbeSessionException {
        List<String> lines;
        try {
            lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ProbeSessionException(ERROR_READ + file);
        }
        if (lines.isEmpty() || !lines.get(0).trim().equals(MARKER)) {
            throw new ProbeSessionException(ERROR_INVALID + "it does not begin as one");
        }

        Map<String, String> header = new LinkedHashMap<>();
        List<Position> measured = new ArrayList<>();
        try {
            for (String line : lines) {
                int separator = line.indexOf('=');
                if (line.startsWith("#") && separator > 0) {
                    header.put(line.substring(1, separator).trim(), line.substring(separator + 1).trim());
                } else if (line.startsWith(POINT_PREFIX)) {
                    String[] values = line.substring(POINT_PREFIX.length()).trim().split("\\s+");
                    if (values.length != 3) {
                        throw new ProbeSessionException(ERROR_INVALID + line);
                    }
                    measured.add(new Position(Double.parseDouble(values[0]), Double.parseDouble(values[1]), Double.parseDouble(values[2]), Units.MM));
                } else if (!line.isBlank()) {
                    throw new ProbeSessionException(ERROR_INVALID + line);
                }
            }

            int planned = Integer.parseInt(value(header, "planned"));
            if (measured.size() != Integer.parseInt(value(header, "measured")) || measured.size() > planned) {
                throw new ProbeSessionException(ERROR_INVALID + "it does not have the points that it tells");
            }
            return new ProbeCheckpoint(
                    value(header, "session"),
                    Instant.parse(value(header, "created")),
                    new ReferenceFrame(Code.valueOf(value(header, "wcs")), number(header, "offset-x-mm"), number(header, "offset-y-mm"), number(header, "offset-z-mm")),
                    new ProbeArea(number(header, "area-min-x-mm"), number(header, "area-min-y-mm"), number(header, "area-max-x-mm"), number(header, "area-max-y-mm")),
                    number(header, "step-mm"),
                    planned,
                    measured);
        } catch (RuntimeException e) {
            throw new ProbeSessionException(ERROR_INVALID + e.getMessage());
        }
    }

    private static String value(Map<String, String> header, String key) throws ProbeSessionException {
        String result = header.get(key);
        if (result == null) {
            throw new ProbeSessionException(ERROR_INVALID + "it has no " + key);
        }
        return result;
    }

    private static double number(Map<String, String> header, String key) throws ProbeSessionException {
        double result = Double.parseDouble(value(header, key));
        if (!Double.isFinite(result)) {
            throw new ProbeSessionException(ERROR_INVALID + key);
        }
        return result;
    }
}
