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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Asks the registered {@link JobValidator}s if a job may be started. The backend does this for
 * every program it sends, and anything else that streams a job from the loaded program to the
 * controller must do the same before queueing the stream.
 * <p>
 * A program can declare that it must not be started without a certain validator, by having a
 * comment made with {@link #createRequirement(String)} in its first lines. Such a program is
 * rejected if that validator is not registered, so a program that needs protection is not released
 * because the plugin that protects it is missing or failed to start.
 * <p>
 * Without registered validators and without such a comment, every job is accepted.
 * <p>
 * Jobs are only validated when they are started. Pausing and resuming continues the stream that
 * was accepted when the job started, which relies on that stream not being changed while it is
 * paused: the backend opens the stream from a processed file that it never writes to again, and
 * processing the program again creates a new file that only is used by the next job.
 */
public class JobValidationService {
    /**
     * The number of lines in the beginning of a program that are searched for required validators
     */
    public static final int REQUIREMENT_HEADER_LINES = 50;

    public static final String ERROR_PROGRAM_NOT_READABLE = "The loaded program could not be read to verify if it can be started: ";
    public static final String ERROR_VALIDATOR_MISSING = "The program can not be started because it requires a validation that is not available: ";
    public static final String ERROR_VALIDATOR_FAILED = "The program can not be started because its validation failed unexpectedly: ";

    private static final Logger LOGGER = Logger.getLogger(JobValidationService.class.getName());
    private static final String REQUIREMENT_PREFIX = "(UGS-REQUIRES-JOB-VALIDATOR id=";

    private final List<JobValidator> validators = new CopyOnWriteArrayList<>();

    /**
     * Creates the comment that a program uses to declare that it must not be started without the given validator
     */
    public static String createRequirement(String validatorId) {
        return REQUIREMENT_PREFIX + validatorId + ")";
    }

    public void register(JobValidator validator) {
        if (!validators.contains(validator)) {
            validators.add(validator);
        }
    }

    public void unregister(JobValidator validator) {
        validators.remove(validator);
    }

    /**
     * @param type               what is going to be streamed
     * @param gcodeFile          the loaded program
     * @param processedGcodeFile the processed program that the job is streamed from
     * @throws JobValidationException if the job must not be started
     */
    public void validate(JobType type, File gcodeFile, File processedGcodeFile) throws JobValidationException {
        for (String requiredId : findRequiredValidators(gcodeFile)) {
            if (validators.stream().noneMatch(validator -> requiredId.equals(validator.getId()))) {
                throw new JobValidationException(ERROR_VALIDATOR_MISSING + requiredId);
            }
        }

        JobRequest request = new JobRequest(type, gcodeFile, processedGcodeFile);
        for (JobValidator validator : validators) {
            try {
                validator.validate(request);
            } catch (JobValidationException e) {
                throw e;
            } catch (RuntimeException e) {
                // A validator that can not give an answer has not accepted the job
                LOGGER.log(Level.SEVERE, "The job validator " + validator.getId() + " failed", e);
                throw new JobValidationException(ERROR_VALIDATOR_FAILED + validator.getId(), e);
            }
        }
    }

    private Set<String> findRequiredValidators(File gcodeFile) throws JobValidationException {
        Set<String> result = new LinkedHashSet<>();
        if (gcodeFile == null) {
            return result;
        }

        try (BufferedReader reader = Files.newBufferedReader(gcodeFile.toPath(), StandardCharsets.ISO_8859_1)) {
            String line;
            for (int i = 0; i < REQUIREMENT_HEADER_LINES && (line = reader.readLine()) != null; i++) {
                line = line.trim();
                if (line.startsWith(REQUIREMENT_PREFIX) && line.endsWith(")")) {
                    result.add(line.substring(REQUIREMENT_PREFIX.length(), line.length() - 1));
                }
            }
        } catch (IOException e) {
            throw new JobValidationException(ERROR_PROGRAM_NOT_READABLE + gcodeFile, e);
        }
        return result;
    }
}
