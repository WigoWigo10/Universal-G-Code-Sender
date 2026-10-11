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

import com.willwinder.ugs.nbp.pcb.gcode.AlignmentException;
import com.willwinder.ugs.nbp.pcb.gcode.AlignmentMetadata;
import com.willwinder.universalgcodesender.services.jobvalidation.JobRequest;
import com.willwinder.universalgcodesender.services.jobvalidation.JobType;
import com.willwinder.universalgcodesender.services.jobvalidation.JobValidationException;
import com.willwinder.universalgcodesender.services.jobvalidation.JobValidator;

import java.io.File;
import java.io.IOException;
import java.util.function.IntSupplier;

/**
 * Only lets an aligned program start if it is the program that was validated. Programs that are
 * not aligned are not affected.
 * <p>
 * Running from a line and tracing the outline are not available for aligned programs: neither of
 * them sends the validated program, so there is nothing that says that what they send is safe.
 */
public class PcbJobValidator implements JobValidator {
    public static final String ERROR_NOT_READABLE = "The loaded program could not be read to verify if it is an aligned program: ";
    public static final String ERROR_OUTLINE = "The outline can not be traced for an aligned program. Trace the outline of the original program instead.";
    public static final String ERROR_RUN_FROM = "An aligned program can not be run from a line, it must be run from the beginning. It is set to run from line ";

    private final PcbAlignmentService alignmentService;
    private final IntSupplier runFromLine;

    /**
     * @param runFromLine gives the line that the program is set to run from, or zero if it runs from the beginning
     */
    public PcbJobValidator(PcbAlignmentService alignmentService, IntSupplier runFromLine) {
        this.alignmentService = alignmentService;
        this.runFromLine = runFromLine;
    }

    @Override
    public String getId() {
        return AlignmentMetadata.JOB_VALIDATOR_ID;
    }

    @Override
    public void validate(JobRequest request) throws JobValidationException {
        if (!isAlignedProgram(request.getGcodeFile())) {
            return;
        }

        if (request.getType() != JobType.PROGRAM) {
            throw new JobValidationException(ERROR_OUTLINE);
        }

        int line = runFromLine.getAsInt();
        if (line > 0) {
            throw new JobValidationException(ERROR_RUN_FROM + line);
        }

        try {
            alignmentService.verifyApproved(request);
        } catch (AlignmentException e) {
            throw new JobValidationException(e.getMessage(), e);
        }
    }

    private boolean isAlignedProgram(File gcodeFile) throws JobValidationException {
        if (gcodeFile == null) {
            return false;
        }

        try {
            return AlignmentMetadata.hasAlignmentHeader(gcodeFile);
        } catch (IOException e) {
            throw new JobValidationException(ERROR_NOT_READABLE + gcodeFile, e);
        }
    }
}
