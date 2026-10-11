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
package com.willwinder.universalgcodesender.services.jobvalidation;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A job that is about to be streamed to the controller
 */
public class JobRequest {
    public static final String ERROR_NO_PROCESSED_PROGRAM = "There is no processed program to start.";
    public static final String ERROR_PROCESSED_PROGRAM_NOT_READABLE = "The processed program could not be read: ";

    private final JobType type;
    private final File gcodeFile;
    private final File processedGcodeFile;
    private String processedSha256;

    /**
     * @param type               what is going to be streamed
     * @param gcodeFile          the loaded program
     * @param processedGcodeFile the processed program that the job is streamed from. For a program job it is
     *                           the exact file that has been opened for streaming.
     */
    public JobRequest(JobType type, File gcodeFile, File processedGcodeFile) {
        this.type = type;
        this.gcodeFile = gcodeFile;
        this.processedGcodeFile = processedGcodeFile;
    }

    public JobType getType() {
        return type;
    }

    public File getGcodeFile() {
        return gcodeFile;
    }

    public File getProcessedGcodeFile() {
        return processedGcodeFile;
    }

    /**
     * The hash of the content of the processed program, which identifies exactly what the job is streamed from.
     * It is calculated the first time it is asked for and is then the same for all validators.
     *
     * @throws JobValidationException if the processed program can not be read
     */
    public synchronized String getProcessedSha256() throws JobValidationException {
        if (processedSha256 == null) {
            processedSha256 = sha256(processedGcodeFile);
        }
        return processedSha256;
    }

    public static String sha256(File file) throws JobValidationException {
        if (file == null) {
            throw new JobValidationException(ERROR_NO_PROCESSED_PROGRAM);
        }

        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath())));
        } catch (IOException e) {
            throw new JobValidationException(ERROR_PROCESSED_PROGRAM_NOT_READABLE + file, e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
