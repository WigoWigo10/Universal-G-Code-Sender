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

import com.willwinder.ugs.nbp.pcb.gcode.AlignedGcodeExporter;
import com.willwinder.ugs.nbp.pcb.gcode.AlignedGcodeFile;
import com.willwinder.ugs.nbp.pcb.gcode.AlignmentException;
import com.willwinder.ugs.nbp.pcb.gcode.AlignmentMetadata;
import com.willwinder.ugs.nbp.pcb.gcode.ToolPathValidator;
import com.willwinder.ugs.nbp.pcb.geometry.RigidTransform2D;
import com.willwinder.universalgcodesender.gcode.GcodeState;
import com.willwinder.universalgcodesender.gcode.util.Code;
import com.willwinder.universalgcodesender.gcode.util.GcodeParserException;
import com.willwinder.universalgcodesender.listeners.UGSEventListener;
import com.willwinder.universalgcodesender.model.BackendAPI;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UGSEvent;
import com.willwinder.universalgcodesender.model.events.FileState;
import com.willwinder.universalgcodesender.model.events.FileStateEvent;
import com.willwinder.universalgcodesender.services.BackendFileLoader;
import com.willwinder.universalgcodesender.services.FileLoader;
import com.willwinder.universalgcodesender.services.LookupService;
import com.willwinder.universalgcodesender.services.jobvalidation.JobRequest;
import com.willwinder.universalgcodesender.services.jobvalidation.JobValidationException;
import com.willwinder.universalgcodesender.utils.GUIHelpers;
import com.willwinder.universalgcodesender.utils.GcodeStreamReader;
import com.willwinder.universalgcodesender.utils.IGcodeStreamReader;
import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Aligns the program that is loaded in the backend. The aligned program is written to a file of
 * its own next to the original program and is then loaded in its place, so the backend and all
 * its processors only ever see a program that is already aligned.
 */
public class PcbAlignmentService implements UGSEventListener {
    /**
     * If the program that will be sent is known to be the complete aligned program
     */
    public enum ValidationState {
        /** No aligned program is loaded, or the program has been processed again since it was validated */
        NOT_VALIDATED,
        /** An aligned program is being loaded and will be validated when the backend has processed it */
        PENDING,
        VALID,
        INVALID
    }

    /**
     * The record of a validated program. A job is only the approved one if all of it is the same when
     * the job is started: the aligned program, the processed program and the work coordinate system.
     *
     * @param alignedFile     the aligned program that was loaded
     * @param metadata        identifies the aligned program, its original and the work coordinate system
     * @param processedSha256 the hash of the processed program that was validated, which is what may be sent
     * @param maxZChangeMM    the change of Z that was accepted
     */
    public record Approval(File alignedFile, AlignmentMetadata metadata, String processedSha256, double maxZChangeMM) {
    }

    public static final String ALIGNED_FILE_SUFFIX = "_aligned";

    public static final String ERROR_NO_FILE = "There is no program loaded to align.";
    public static final String ERROR_SENDING = "The program can not be aligned while it is being sent.";
    public static final String ERROR_SOURCE_MISSING = "The loaded program is already aligned and its original program was not found unchanged next to it: ";
    public static final String ERROR_WORK_COORDINATE_SYSTEM = "The machine is using another work coordinate system than the alignment was measured in: ";
    public static final String ERROR_NOT_ALIGNED = "The loaded program is not an aligned program, or it has been changed since it was aligned: ";
    public static final String ERROR_NOT_PROCESSED = "The aligned program has not been processed for sending.";
    public static final String ERROR_LOAD = "The aligned program could not be loaded: ";
    public static final String ERROR_CHANGED_DURING_VALIDATION = "The program was processed again while it was being validated, validate it again.";
    public static final String ERROR_NOT_APPROVED = "The aligned program has not been validated. Validate it with the PCB tools before it is started.";
    public static final String ERROR_PROCESSED_CHANGED = "The aligned program has been processed again since it was validated, for instance by the auto leveler. Validate it again with the PCB tools before it is started.";
    public static final String ERROR_WORK_COORDINATE_SYSTEM_UNKNOWN = "The work coordinate system of the machine is not known, so the aligned program can not be started. It must be ";

    /**
     * The largest accepted distance in millimeters between the program that will be sent and the
     * aligned program, which allows for the rounding of the coordinates.
     */
    public static final double VALIDATION_TOLERANCE_MM = 0.005;

    private final BackendAPI backend;
    private final FileLoader fileLoader;
    private final Consumer<AlignmentException> errorHandler;
    private final AlignedGcodeExporter exporter = new AlignedGcodeExporter();

    private ValidationState validationState = ValidationState.NOT_VALIDATED;
    private Approval approval;
    private File pendingFile;
    private boolean isLoading;

    /**
     * Creates a service that opens the aligned program in the same way as the application opens files,
     * which in the platform is in the editor, and shows an error dialog if it turns out to be invalid.
     */
    public PcbAlignmentService(BackendAPI backend) {
        this(backend,
                file -> LookupService.lookupOptional(FileLoader.class).orElseGet(() -> new BackendFileLoader(backend)).openFile(file),
                e -> GUIHelpers.displayErrorDialog(e.getMessage()));
    }

    /**
     * @param fileLoader   opens the aligned program. If it has loaded the program in the backend when it
     *                     returns the program is validated at once, otherwise when the backend has loaded it.
     * @param errorHandler is given the error if a program that was loaded later turned out to be invalid
     */
    public PcbAlignmentService(BackendAPI backend, FileLoader fileLoader, Consumer<AlignmentException> errorHandler) {
        this.backend = backend;
        this.fileLoader = fileLoader;
        this.errorHandler = errorHandler;
        this.backend.addUGSEventListener(this);
    }

    public synchronized ValidationState getValidationState() {
        return validationState;
    }

    /**
     * Returns the approval of the latest validated program, which may no longer be the loaded one
     */
    public synchronized Optional<Approval> getApproval() {
        return Optional.ofNullable(approval);
    }

    /**
     * Verifies that a job that is about to be started is the program that was validated. Nothing of
     * this relies on having been told about changes: the aligned program is read and verified again,
     * the processed program is identified by its content, and the machine is asked for its work
     * coordinate system.
     *
     * @throws AlignmentException if the job is not the validated program
     */
    public synchronized void verifyApproved(JobRequest request) throws AlignmentException {
        AlignmentMetadata metadata = readVerifiedMetadata(request.getGcodeFile());
        if (approval == null || !isSameFile(approval.alignedFile(), request.getGcodeFile()) || !approval.metadata().equals(metadata)) {
            throw new AlignmentException(ERROR_NOT_APPROVED);
        }

        String processedSha256;
        try {
            processedSha256 = request.getProcessedSha256();
        } catch (JobValidationException e) {
            throw new AlignmentException(e.getMessage(), e);
        }
        if (!approval.processedSha256().equals(processedSha256)) {
            throw new AlignmentException(ERROR_PROCESSED_CHANGED);
        }

        GcodeState state = backend.isConnected() ? backend.getGcodeState() : null;
        if (state == null || state.offset == null) {
            throw new AlignmentException(ERROR_WORK_COORDINATE_SYSTEM_UNKNOWN + metadata.workCoordinateSystem());
        }
        if (state.offset != metadata.workCoordinateSystem()) {
            throw new AlignmentException(ERROR_WORK_COORDINATE_SYSTEM + state.offset + " instead of " + metadata.workCoordinateSystem());
        }
    }

    /**
     * Aligns the original of the loaded program and loads the result. If the loaded program is
     * already aligned its original program is aligned again, so alignments are never added to
     * each other.
     *
     * @param transform            the alignment with the translation in millimeters
     * @param workCoordinateSystem the work coordinate system that the alignment was measured in
     * @throws AlignmentException if the program could not be aligned, in which case the loaded program is not changed
     */
    public synchronized AlignedGcodeFile alignLoadedFile(RigidTransform2D transform, Code workCoordinateSystem) throws AlignmentException {
        File loadedFile = backend.getGcodeFile();
        if (loadedFile == null) {
            throw new AlignmentException(ERROR_NO_FILE);
        }
        if (backend.isSendingFile()) {
            throw new AlignmentException(ERROR_SENDING);
        }
        verifyWorkCoordinateSystem(workCoordinateSystem);

        File source = findOriginalFile(loadedFile);
        String name = FilenameUtils.getBaseName(source.getName()) + ALIGNED_FILE_SUFFIX + "." + FilenameUtils.getExtension(source.getName());
        File target = new File(source.getAbsoluteFile().getParentFile(), name);

        AlignedGcodeFile alignedFile = exporter.export(source, transform, workCoordinateSystem, target);
        load(alignedFile);
        return alignedFile;
    }

    /**
     * Returns the original program of the given program, which is the program itself unless it is aligned
     */
    public File findOriginalFile(File file) throws AlignmentException {
        try {
            if (!AlignmentMetadata.isAligned(file)) {
                return file;
            }

            Optional<AlignmentMetadata> metadata = AlignmentMetadata.read(file);
            Optional<File> source = metadata.isPresent() ? metadata.get().findSource(file) : Optional.empty();
            return source.orElseThrow(() -> new AlignmentException(ERROR_SOURCE_MISSING + file));
        } catch (IOException e) {
            throw new AlignmentException(ERROR_SOURCE_MISSING + file, e);
        }
    }

    /**
     * Loads an aligned program in the backend and validates what the backend made of it.
     * <p>
     * Loading a program removes the processors that were applied to the previous one, and the auto
     * leveler applies its height map again to any program that is loaded. A height map may have
     * been made for another position of the board, so applying it is turned off and has to be
     * turned on again deliberately.
     */
    public synchronized void load(AlignedGcodeFile alignedFile) throws AlignmentException {
        verifyWorkCoordinateSystem(alignedFile.metadata().workCoordinateSystem());
        readVerifiedMetadata(alignedFile.file());

        backend.getSettings().getAutoLevelSettings().setApplyToGcode(false);
        pendingFile = alignedFile.file();
        validationState = ValidationState.PENDING;
        isLoading = true;
        try {
            fileLoader.openFile(alignedFile.file());
        } catch (Exception e) {
            rejectLoadedFile();
            throw new AlignmentException(ERROR_LOAD + alignedFile.file(), e);
        } finally {
            isLoading = false;
        }

        // Some loaders open the file later, it is then validated when the backend has loaded it
        if (isSameFile(pendingFile, backend.getGcodeFile())) {
            validatePendingFile();
        }
    }

    @Override
    public synchronized void UGSEvent(UGSEvent event) {
        if (!(event instanceof FileStateEvent fileStateEvent) || isLoading) {
            return;
        }

        // The program was unloaded or could not be processed, so there is nothing left that is validated
        if (fileStateEvent.getFileState() == FileState.FILE_UNLOADED) {
            validationState = ValidationState.NOT_VALIDATED;
            approval = null;
            return;
        }
        if (fileStateEvent.getFileState() != FileState.FILE_LOADED) {
            return;
        }

        if (pendingFile != null && isSameFile(pendingFile, backend.getGcodeFile())) {
            try {
                validatePendingFile();
            } catch (AlignmentException e) {
                errorHandler.accept(e);
            }
        } else {
            // The program has been replaced or processed again, such as when the auto leveler is applied
            validationState = ValidationState.NOT_VALIDATED;
            approval = null;
        }
    }

    private void validatePendingFile() throws AlignmentException {
        pendingFile = null;
        try {
            validateProcessedFile(0);
        } catch (AlignmentException e) {
            rejectLoadedFile();
            throw e;
        }
    }

    private void rejectLoadedFile() {
        pendingFile = null;
        validationState = ValidationState.INVALID;
        approval = null;
        try {
            backend.unsetGcodeFile();
        } catch (Exception e) {
            // The failure that caused the unload is the one that is reported
        }
        validationState = ValidationState.INVALID;
    }

    private static boolean isSameFile(File first, File second) {
        return first != null && second != null && first.getAbsoluteFile().equals(second.getAbsoluteFile());
    }

    /**
     * Validates the program that will be sent against the loaded aligned program, and approves it
     * for sending if it is valid. This must be done again after anything has been applied to the
     * program, such as the auto leveler: if a processor fails the backend is left with the part of
     * the program that it had processed.
     * <p>
     * The approval is for the content of the processed program that was validated. The job validator
     * only lets a job start if it is streamed from that same content.
     *
     * @param maxZChangeMM the largest accepted change of Z, zero if nothing may have changed Z
     * @throws AlignmentException if the program that will be sent is not the complete aligned program
     */
    public synchronized void validateProcessedFile(double maxZChangeMM) throws AlignmentException {
        validationState = ValidationState.INVALID;
        approval = null;
        File loadedFile = backend.getGcodeFile();
        if (loadedFile == null) {
            throw new AlignmentException(ERROR_NO_FILE);
        }
        AlignmentMetadata metadata = readVerifiedMetadata(loadedFile);
        verifyWorkCoordinateSystem(metadata.workCoordinateSystem());

        File processedFile = backend.getProcessedGcodeFile();
        if (processedFile == null || !processedFile.isFile()) {
            throw new AlignmentException(ERROR_NOT_PROCESSED);
        }

        try {
            String processedSha256 = JobRequest.sha256(processedFile);
            List<Position> expected = ToolPathValidator.trace(Files.readAllLines(loadedFile.toPath(), StandardCharsets.UTF_8));
            List<Position> processed = ToolPathValidator.trace(readProcessedCommands(processedFile));
            ToolPathValidator.validate(expected, processed, VALIDATION_TOLERANCE_MM, maxZChangeMM);

            // What was validated must be what is approved
            if (!processedSha256.equals(JobRequest.sha256(processedFile)) || !metadata.equals(readVerifiedMetadata(loadedFile))) {
                throw new AlignmentException(ERROR_CHANGED_DURING_VALIDATION);
            }
            approval = new Approval(loadedFile.getAbsoluteFile(), metadata, processedSha256, maxZChangeMM);
            validationState = ValidationState.VALID;
        } catch (IOException | GcodeParserException | GcodeStreamReader.NotGcodeStreamFile | JobValidationException e) {
            throw new AlignmentException(ERROR_NOT_PROCESSED, e);
        }
    }

    private AlignmentMetadata readVerifiedMetadata(File file) throws AlignmentException {
        try {
            return AlignmentMetadata.read(file).orElseThrow(() -> new AlignmentException(ERROR_NOT_ALIGNED + file));
        } catch (IOException e) {
            throw new AlignmentException(ERROR_NOT_ALIGNED + file, e);
        }
    }

    /**
     * The alignment is only valid in the work coordinate system that the reference points were
     * measured in. This can only be verified while connected to the machine.
     */
    private void verifyWorkCoordinateSystem(Code workCoordinateSystem) throws AlignmentException {
        if (!backend.isConnected()) {
            return;
        }

        GcodeState state = backend.getGcodeState();
        if (state != null && state.offset != workCoordinateSystem) {
            throw new AlignmentException(ERROR_WORK_COORDINATE_SYSTEM + state.offset + " instead of " + workCoordinateSystem);
        }
    }

    private List<String> readProcessedCommands(File processedFile) throws IOException, GcodeStreamReader.NotGcodeStreamFile {
        List<String> result = new ArrayList<>();
        try (IGcodeStreamReader reader = new GcodeStreamReader(processedFile, backend.getCommandCreator())) {
            while (reader.getNumRowsRemaining() > 0) {
                result.add(reader.getNextCommand().getCommandString());
            }
        }
        return result;
    }
}
