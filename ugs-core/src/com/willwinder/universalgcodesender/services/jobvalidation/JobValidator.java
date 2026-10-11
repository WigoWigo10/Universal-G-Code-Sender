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

/**
 * Decides if a job may be started. Validators are registered with the {@link JobValidationService}
 * and are asked every time a job is about to be streamed to the controller.
 * <p>
 * A validator must return quickly for the jobs it has no opinion about, as it is asked about every job.
 */
public interface JobValidator {

    /**
     * The identity of the validator, which programs use to declare that they must not be
     * started without it, see {@link JobValidationService#createRequirement(String)}.
     */
    String getId();

    /**
     * @param request the job that is about to be started
     * @throws JobValidationException if the job must not be started
     */
    void validate(JobRequest request) throws JobValidationException;
}
