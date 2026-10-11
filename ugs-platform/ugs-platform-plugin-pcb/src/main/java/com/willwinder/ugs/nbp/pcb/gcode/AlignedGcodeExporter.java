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
import com.willwinder.universalgcodesender.gcode.GcodeParser;
import com.willwinder.universalgcodesender.gcode.util.Code;
import com.willwinder.universalgcodesender.gcode.util.GcodeParserException;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UnitUtils.Units;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Writes an aligned copy of a program. The program is aligned in a parser of its own, as a
 * separate step before any other processing, so that everything that is done to the aligned
 * program afterward, such as auto leveling, works with the aligned positions.
 * <p>
 * The export either results in a complete and verified file or in no file at all. The original
 * program is never changed.
 */
public class AlignedGcodeExporter {
    public static final String ERROR_SOURCE_NOT_FOUND = "The program to align could not be read: ";
    public static final String ERROR_ALREADY_ALIGNED = "The program is already aligned, align the original program instead: ";
    public static final String ERROR_TARGET_IS_SOURCE = "The aligned program can not replace the original program: ";
    public static final String ERROR_TARGET_NOT_ALIGNED = "The file already exists and is not an aligned program, it will not be replaced: ";
    public static final String ERROR_INVALID_TRANSFORM = "The alignment is not valid: ";
    public static final String ERROR_INVALID_WORK_COORDINATE_SYSTEM = "Not a work coordinate system: ";
    public static final String ERROR_LINE = "Could not align line ";
    public static final String ERROR_VERIFICATION = "The aligned program does not match the original program at line ";
    public static final String ERROR_WRITE = "Could not write the aligned program: ";

    /**
     * The largest accepted difference in millimeters between an aligned position and the position
     * calculated from the original program, which allows for the rounding of the coordinates.
     */
    static final double VERIFICATION_TOLERANCE_MM = 0.005;

    /**
     * @param source               the original program
     * @param transform            the alignment with the translation in millimeters
     * @param workCoordinateSystem the work coordinate system that the alignment was measured in
     * @param target               the file to write, which may only exist if it is an aligned program
     * @throws AlignmentException if the program could not be aligned, in which case the target is not changed
     */
    public AlignedGcodeFile export(File source, RigidTransform2D transform, Code workCoordinateSystem, File target) throws AlignmentException {
        validateArguments(source, transform, workCoordinateSystem, target);

        byte[] sourceBytes;
        try {
            sourceBytes = Files.readAllBytes(source.toPath());
        } catch (IOException e) {
            throw new AlignmentException(ERROR_SOURCE_NOT_FOUND + source, e);
        }

        List<String> sourceLines = new String(sourceBytes, StandardCharsets.UTF_8).lines().toList();
        if (AlignmentMetadata.isAligned(sourceLines)) {
            throw new AlignmentException(ERROR_ALREADY_ALIGNED + source);
        }

        List<String> alignedLines = align(sourceLines, transform, workCoordinateSystem);
        AlignmentMetadata metadata = new AlignmentMetadata(
                source.getName(),
                AlignmentMetadata.sha256(sourceBytes),
                transform,
                workCoordinateSystem,
                AlignmentMetadata.sha256(alignedLines));

        List<String> lines = new ArrayList<>(metadata.toHeader());
        lines.addAll(alignedLines);
        write(lines, metadata, target);
        return new AlignedGcodeFile(target, metadata);
    }

    private void validateArguments(File source, RigidTransform2D transform, Code workCoordinateSystem, File target) throws AlignmentException {
        if (!Double.isFinite(transform.rotation()) || !Double.isFinite(transform.translationX()) || !Double.isFinite(transform.translationY())) {
            throw new AlignmentException(ERROR_INVALID_TRANSFORM + transform);
        }
        if (workCoordinateSystem == null || workCoordinateSystem.getType() != Code.ModalGroup.WCS) {
            throw new AlignmentException(ERROR_INVALID_WORK_COORDINATE_SYSTEM + workCoordinateSystem);
        }
        if (!source.isFile()) {
            throw new AlignmentException(ERROR_SOURCE_NOT_FOUND + source);
        }

        try {
            if (target.exists() && Files.isSameFile(source.toPath(), target.toPath())) {
                throw new AlignmentException(ERROR_TARGET_IS_SOURCE + target);
            }
            if (target.exists() && !AlignmentMetadata.isAligned(target)) {
                throw new AlignmentException(ERROR_TARGET_NOT_ALIGNED + target);
            }
        } catch (IOException e) {
            throw new AlignmentException(ERROR_TARGET_NOT_ALIGNED + target, e);
        }
    }

    private List<String> align(List<String> sourceLines, RigidTransform2D transform, Code workCoordinateSystem) throws AlignmentException {
        AlignmentProcessor processor = new AlignmentProcessor(transform, Units.MM, workCoordinateSystem);
        GcodeParser sourceParser = new GcodeParser();
        GcodeParser alignedParser = new GcodeParser();

        List<String> result = new ArrayList<>();
        for (int i = 0; i < sourceLines.size(); i++) {
            String line = sourceLines.get(i);
            try {
                List<String> aligned = processor.processCommand(line, sourceParser.getCurrentState());
                sourceParser.addCommand(line);
                for (String alignedLine : aligned) {
                    alignedParser.addCommand(alignedLine);
                }
                result.addAll(aligned);
            } catch (GcodeParserException | RuntimeException e) {
                throw new AlignmentException(ERROR_LINE + (i + 1) + ": " + e.getMessage(), e);
            }

            verifyPosition(i + 1, transform,
                    sourceParser.getCurrentState().currentPoint.getPositionIn(Units.MM),
                    alignedParser.getCurrentState().currentPoint.getPositionIn(Units.MM));
        }
        return result;
    }

    /**
     * Verifies the aligned program without relying on how the lines were rewritten: after every
     * line of the original program, the position that the aligned program has reached must be the
     * transformed position of the original program.
     */
    private void verifyPosition(int lineNumber, RigidTransform2D transform, Position source, Position aligned) throws AlignmentException {
        boolean isXYValid;
        if (Double.isFinite(source.getX()) && Double.isFinite(source.getY())) {
            Point2D expected = transform.apply(new Point2D(source.getX(), source.getY()));
            isXYValid = expected.distance(new Point2D(aligned.getX(), aligned.getY())) <= VERIFICATION_TOLERANCE_MM;
        } else {
            // Until both X and Y are known nothing can be transformed, so nothing may have changed
            isXYValid = Double.compare(source.getX(), aligned.getX()) == 0 && Double.compare(source.getY(), aligned.getY()) == 0;
        }

        boolean isZValid = (Double.isNaN(source.getZ()) && Double.isNaN(aligned.getZ()))
                || Math.abs(source.getZ() - aligned.getZ()) <= VERIFICATION_TOLERANCE_MM;

        if (!isXYValid || !isZValid) {
            throw new AlignmentException(ERROR_VERIFICATION + lineNumber);
        }
    }

    /**
     * Writes the file under a temporary name, reads it back to verify it and only then gives it
     * its real name. An interrupted or failed export can therefore never leave a file that looks
     * like an aligned program.
     */
    private void write(List<String> lines, AlignmentMetadata metadata, File target) throws AlignmentException {
        Path targetPath = target.getAbsoluteFile().toPath();
        Path temporaryPath = null;
        try {
            temporaryPath = Files.createTempFile(targetPath.getParent(), target.getName(), ".tmp");
            byte[] bytes = (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporaryPath, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }

            Optional<AlignmentMetadata> written = AlignmentMetadata.read(temporaryPath.toFile());
            if (written.isEmpty() || !written.get().equals(metadata)) {
                throw new AlignmentException(ERROR_WRITE + target);
            }

            try {
                Files.move(temporaryPath, targetPath, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporaryPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new AlignmentException(ERROR_WRITE + target, e);
        } finally {
            deleteQuietly(temporaryPath);
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // Nothing more to do, the temporary file can never be mistaken for an aligned program
        }
    }
}
