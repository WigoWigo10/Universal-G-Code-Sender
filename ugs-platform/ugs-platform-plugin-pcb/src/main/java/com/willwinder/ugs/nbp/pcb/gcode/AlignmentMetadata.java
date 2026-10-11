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

import com.willwinder.ugs.nbp.pcb.geometry.RigidTransform2D;
import com.willwinder.universalgcodesender.gcode.util.Code;
import com.willwinder.universalgcodesender.services.jobvalidation.JobValidationService;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Describes where an aligned program comes from. It is stored as comments in the first lines of
 * the aligned file, which makes it possible to recognize an aligned file, to find the program it
 * was made from and to detect if either of them has been changed. They also declare that the
 * program must not be started without the job validator for aligned programs.
 *
 * @param sourceName           the file name of the original program
 * @param sourceSha256         the hash of the original program
 * @param transform            the applied transform with the translation in millimeters
 * @param workCoordinateSystem the work coordinate system that the transform was measured in
 * @param contentSha256        the hash of the aligned program, without the metadata
 */
public record AlignmentMetadata(String sourceName, String sourceSha256, RigidTransform2D transform,
                                Code workCoordinateSystem, String contentSha256) {
    public static final String MARKER = "(UGS-PCB-ALIGNMENT version=1)";
    static final String MARKER_PREFIX = "(UGS-PCB-ALIGNMENT";

    /**
     * The identity of the job validator that aligned programs must not be started without
     */
    public static final String JOB_VALIDATOR_ID = "pcb-alignment";
    static final String JOB_VALIDATOR_REQUIREMENT = JobValidationService.createRequirement(JOB_VALIDATOR_ID);

    private static final String SOURCE_NAME = "source-name";
    private static final String SOURCE_SHA256 = "source-sha256";
    private static final String ROTATION = "rotation-rad";
    private static final String TRANSLATION_X = "translation-x-mm";
    private static final String TRANSLATION_Y = "translation-y-mm";
    private static final String WORK_COORDINATE_SYSTEM = "wcs";
    private static final String CONTENT_SHA256 = "content-sha256";
    private static final int HEADER_LINES = 9;

    /**
     * Returns true if the program has been aligned, even if its metadata is incomplete
     */
    public static boolean isAligned(List<String> lines) {
        return lines.stream().anyMatch(line -> line.trim().startsWith(MARKER_PREFIX));
    }

    public static boolean isAligned(File file) throws IOException {
        return isAligned(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
    }

    /**
     * Returns true if the program starts as an aligned program does. This only reads the first lines of the
     * file, which makes it suitable for deciding if a program that is about to be started is an aligned program.
     */
    public static boolean hasAlignmentHeader(File file) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.ISO_8859_1)) {
            String line;
            for (int i = 0; i < JobValidationService.REQUIREMENT_HEADER_LINES && (line = reader.readLine()) != null; i++) {
                if (line.trim().startsWith(MARKER_PREFIX)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Reads the metadata of an aligned file and verifies that its program has not been changed.
     *
     * @return the metadata, or empty if the file does not have valid metadata or has been changed
     */
    public static Optional<AlignmentMetadata> read(File file) throws IOException {
        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        if (lines.size() < HEADER_LINES || !lines.get(0).equals(MARKER) || !lines.get(1).equals(JOB_VALIDATOR_REQUIREMENT)) {
            return Optional.empty();
        }

        try {
            AlignmentMetadata metadata = new AlignmentMetadata(
                    URLDecoder.decode(value(lines.get(2), SOURCE_NAME), StandardCharsets.UTF_8),
                    value(lines.get(3), SOURCE_SHA256),
                    new RigidTransform2D(
                            Double.parseDouble(value(lines.get(4), ROTATION)),
                            Double.parseDouble(value(lines.get(5), TRANSLATION_X)),
                            Double.parseDouble(value(lines.get(6), TRANSLATION_Y))),
                    Code.valueOf(value(lines.get(7), WORK_COORDINATE_SYSTEM)),
                    value(lines.get(8), CONTENT_SHA256));

            List<String> content = lines.subList(HEADER_LINES, lines.size());
            if (isAligned(content) || !metadata.contentSha256().equals(sha256(content))) {
                return Optional.empty();
            }
            return Optional.of(metadata);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Finds the original program of an aligned file. It is expected next to the aligned file and
     * is only returned if it is unchanged since the alignment was made.
     */
    public Optional<File> findSource(File alignedFile) throws IOException {
        File source = new File(alignedFile.getAbsoluteFile().getParentFile(), sourceName);
        if (!source.isFile() || !sourceSha256.equals(sha256(Files.readAllBytes(source.toPath())))) {
            return Optional.empty();
        }
        return Optional.of(source);
    }

    List<String> toHeader() {
        return List.of(
                MARKER,
                JOB_VALIDATOR_REQUIREMENT,
                comment(SOURCE_NAME, URLEncoder.encode(sourceName, StandardCharsets.UTF_8)),
                comment(SOURCE_SHA256, sourceSha256),
                comment(ROTATION, Double.toString(transform.rotation())),
                comment(TRANSLATION_X, Double.toString(transform.translationX())),
                comment(TRANSLATION_Y, Double.toString(transform.translationY())),
                comment(WORK_COORDINATE_SYSTEM, workCoordinateSystem.name()),
                comment(CONTENT_SHA256, contentSha256));
    }

    private static String comment(String key, String value) {
        return "(" + key + "=" + value + ")";
    }

    private static String value(String line, String key) {
        String prefix = "(" + key + "=";
        if (!line.startsWith(prefix) || !line.endsWith(")")) {
            throw new IllegalArgumentException("Expected " + key + " but found: " + line);
        }
        return line.substring(prefix.length(), line.length() - 1);
    }

    /**
     * The hash of the lines, independent of which line endings the file is stored with
     */
    static String sha256(List<String> lines) {
        return sha256((String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
