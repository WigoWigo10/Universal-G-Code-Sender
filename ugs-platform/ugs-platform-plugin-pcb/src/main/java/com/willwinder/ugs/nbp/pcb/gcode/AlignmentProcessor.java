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
package com.willwinder.ugs.nbp.pcb.gcode;

import com.willwinder.ugs.nbp.pcb.geometry.Point2D;
import com.willwinder.ugs.nbp.pcb.geometry.RigidTransform2D;
import com.willwinder.universalgcodesender.gcode.GcodeParser.GcodeMeta;
import com.willwinder.universalgcodesender.gcode.GcodePreprocessorUtils;
import com.willwinder.universalgcodesender.gcode.GcodeState;
import com.willwinder.universalgcodesender.gcode.processors.ArcExpander;
import com.willwinder.universalgcodesender.gcode.processors.CommandProcessor;
import com.willwinder.universalgcodesender.gcode.util.Code;
import static com.willwinder.universalgcodesender.gcode.util.Code.G0;
import static com.willwinder.universalgcodesender.gcode.util.Code.G1;
import static com.willwinder.universalgcodesender.gcode.util.Code.G53;
import com.willwinder.universalgcodesender.gcode.util.GcodeParserException;
import com.willwinder.universalgcodesender.gcode.util.GcodeParserUtils;
import com.willwinder.universalgcodesender.gcode.util.Plane;
import com.willwinder.universalgcodesender.model.PartialPosition;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UnitUtils;
import com.willwinder.universalgcodesender.model.UnitUtils.Units;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Applies a rotation and translation in the XY plane to a program in a single pass, used for
 * aligning a program to where the PCB actually is on the machine.
 * <p>
 * Arcs are expanded to line segments. The processor only supports programs in absolute mode
 * in the XY plane, anything it can not transform safely is rejected with an error instead of
 * being passed through.
 */
public class AlignmentProcessor implements CommandProcessor {
    public static final String ERROR_RELATIVE_MODE = "Alignment does not support relative positioning (G91): ";
    public static final String ERROR_UNSUPPORTED_PLANE = "Alignment only supports the XY plane (G17): ";
    public static final String ERROR_UNSUPPORTED_COMMAND = "Alignment does not support the command: ";
    public static final String ERROR_MACHINE_COORDINATES = "Alignment does not support XY moves in machine coordinates (G53): ";
    public static final String ERROR_UNEXPANDED_ARC = "Alignment could not convert the arc to lines, it needs an end point in X or Y: ";
    public static final String ERROR_UNKNOWN_POSITION = "Alignment needs both X and Y to be known, start the program with a move to an XY position: ";

    public static final double DEFAULT_ARC_SEGMENT_LENGTH_MM = 0.1;

    private static final Set<Code> UNSUPPORTED_CODES = EnumSet.of(
            Code.G10, Code.G28, Code.G28_1, Code.G28_2, Code.G28_3, Code.G30,
            Code.G92, Code.G92_1, Code.G92_2, Code.G92_3,
            Code.G38_2, Code.G38_3, Code.G38_4, Code.G38_5,
            Code.G81, Code.G82, Code.G83, Code.G84, Code.G85, Code.G86, Code.G87, Code.G88, Code.G89);

    /**
     * Codes that change the coordinate system which the parser does not know about: scaling (G51),
     * local offset (G52) and coordinate rotation (G68)
     */
    private static final Set<Double> UNSUPPORTED_UNKNOWN_CODES = Set.of(51d, 52d, 68d);

    private final RigidTransform2D transform;
    private final Units translationUnits;
    private final ArcExpander arcExpander;

    /**
     * @param transform        the transform from the coordinates in the program to the aligned coordinates
     * @param translationUnits the units of the translation in the transform
     */
    public AlignmentProcessor(RigidTransform2D transform, Units translationUnits) {
        this(transform, translationUnits, DEFAULT_ARC_SEGMENT_LENGTH_MM);
    }

    public AlignmentProcessor(RigidTransform2D transform, Units translationUnits, double arcSegmentLengthMM) {
        this.transform = transform;
        this.translationUnits = translationUnits;
        this.arcExpander = new ArcExpander(true, arcSegmentLengthMM);
    }

    public RigidTransform2D getTransform() {
        return transform;
    }

    @Override
    public List<String> processCommand(String command, GcodeState state) throws GcodeParserException {
        if (transform.equals(RigidTransform2D.identity())) {
            return Collections.singletonList(command);
        }

        List<String> args = GcodePreprocessorUtils.splitCommand(GcodePreprocessorUtils.removeComment(command));
        if (args.isEmpty()) {
            return Collections.singletonList(command);
        }

        Set<Code> gCodes = GcodePreprocessorUtils.getGCodes(args);
        validateCodes(command, args, gCodes);

        // Arcs may be given with only a center offset, such as a full circle
        boolean hasPlanarWords = hasWord(args, 'X') || hasWord(args, 'Y') || hasWord(args, 'I') || hasWord(args, 'J');
        if (!hasPlanarWords) {
            return Collections.singletonList(alignMoveWithoutPlanarWords(command, args, gCodes, state));
        }

        if (gCodes.contains(G53)) {
            throw new GcodeParserException(ERROR_MACHINE_COORDINATES + command);
        }
        if (!state.inAbsoluteMode) {
            throw new GcodeParserException(ERROR_RELATIVE_MODE + command);
        }
        if (state.plane != Plane.XY) {
            throw new GcodeParserException(ERROR_UNSUPPORTED_PLANE + command);
        }

        List<String> result = new ArrayList<>();
        for (String line : arcExpander.processCommand(command, state)) {
            // The arc expander leaves arcs it does not recognize untouched, such as an arc without an end point
            List<String> lineArgs = GcodePreprocessorUtils.splitCommand(GcodePreprocessorUtils.removeComment(line));
            if (hasWord(lineArgs, 'I') || hasWord(lineArgs, 'J')) {
                throw new GcodeParserException(ERROR_UNEXPANDED_ARC + command);
            }
            result.add(transformLine(line, state));
        }
        return result;
    }

    private void validateCodes(String command, List<String> args, Set<Code> gCodes) throws GcodeParserException {
        if (gCodes.contains(Code.G91)) {
            throw new GcodeParserException(ERROR_RELATIVE_MODE + command);
        }
        if (gCodes.contains(Code.G18) || gCodes.contains(Code.G19)) {
            throw new GcodeParserException(ERROR_UNSUPPORTED_PLANE + command);
        }
        if (gCodes.stream().anyMatch(UNSUPPORTED_CODES::contains)) {
            throw new GcodeParserException(ERROR_UNSUPPORTED_COMMAND + command);
        }
        for (String code : GcodePreprocessorUtils.parseCodes(args, 'G')) {
            try {
                if (UNSUPPORTED_UNKNOWN_CODES.contains(Double.parseDouble(code))) {
                    throw new GcodeParserException(ERROR_UNSUPPORTED_COMMAND + command);
                }
            } catch (NumberFormatException e) {
                // Not a code we can interpret, leave it to the parser
            }
        }
    }

    /**
     * A move in only Z keeps its XY position, but the aligned XY position is added to the command
     * anyway. Processors that run after this one, such as the mesh leveler, only know the position
     * from the original program and would otherwise use the unaligned XY position for these moves.
     */
    private String alignMoveWithoutPlanarWords(String command, List<String> args, Set<Code> gCodes, GcodeState state) throws GcodeParserException {
        boolean isAlignable = hasWord(args, 'Z') && !gCodes.contains(G53) && state.inAbsoluteMode && state.plane == Plane.XY;
        if (!isAlignable) {
            return command;
        }

        Position current = state.currentPoint;
        if (current == null || !Double.isFinite(current.getX()) || !Double.isFinite(current.getY())) {
            return command;
        }
        return transformLine(command, state);
    }

    private String transformLine(String line, GcodeState state) throws GcodeParserException {
        GcodeMeta motion = findLinearMotion(GcodeParserUtils.processCommand(line, 0, state));
        if (motion == null) {
            return line;
        }

        Units units = Units.getUnits(motion.state.units);
        Position end = motion.point.point().getPositionIn(units);
        if (!Double.isFinite(end.getX()) || !Double.isFinite(end.getY())) {
            throw new GcodeParserException(ERROR_UNKNOWN_POSITION + line);
        }

        double scale = UnitUtils.scaleUnits(translationUnits, units);
        Point2D rotated = new RigidTransform2D(transform.rotation(), 0, 0).apply(new Point2D(end.getX(), end.getY()));
        PartialPosition aligned = PartialPosition.builder(units)
                .setX(rotated.x() + transform.translationX() * scale)
                .setY(rotated.y() + transform.translationY() * scale)
                .build();

        return GcodePreprocessorUtils.overridePosition(GcodePreprocessorUtils.removeComment(line).trim(), aligned);
    }

    private GcodeMeta findLinearMotion(List<GcodeMeta> metas) {
        if (metas == null) {
            return null;
        }
        for (GcodeMeta meta : metas) {
            if ((meta.code == G0 || meta.code == G1) && meta.point != null) {
                return meta;
            }
        }
        return null;
    }

    private boolean hasWord(List<String> args, char word) {
        return GcodePreprocessorUtils.extractWord(args, word) != null;
    }

    @Override
    public String getHelp() {
        return "Aligns the program in the XY plane using a rotation and translation";
    }
}
